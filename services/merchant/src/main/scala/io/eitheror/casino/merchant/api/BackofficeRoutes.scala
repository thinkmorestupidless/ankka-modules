package io.eitheror.casino.merchant.api

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import io.eitheror.casino.contract.money.{Amounts, Currency}
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.api.Answers.*
import io.eitheror.casino.merchant.deposits.*
import io.eitheror.casino.merchant.hierarchy.SubBrandsView
import io.eitheror.casino.merchant.operators.Target
import io.eitheror.casino.merchant.payments.*

/**
 * The payments backoffice's reads over payments (FR-023, FR-029; the original's `CashierAdminRoutes`):
 * the list, the summary and the payments captured but not credited, over the sub-brands the operator's
 * level covers; a sub-brand with no parent is the installation level's alone.
 */
final class BackofficeRoutes(ctx: Ctx):

  /** The sub-brands an operator may read, or the one named, or a scope violation. */
  private def scoped(op: Operator, merchantId: Option[String]): Either[Respond[Bytes], Set[String]] =
    merchantId.map(_.trim).filter(_.nonEmpty) match
      case Some(id) =>
        val s = ctx.subBrand(id)
        if s.exists && !op.permits(ctx.subBrandTarget(s)) then Left(scopeViolation)
        else if !s.exists && !op.platform then Left(scopeViolation)
        else Right(Set(id))
      case None =>
        Right(ctx.views.forView(SubBrandsView).all(100_000)
          .filter(r => op.permits(Target(Option(r.holdingId).filter(_.nonEmpty), Option(r.companyId).filter(_.nonEmpty), Some(r.id))))
          .map(_.id).toSet)

  private def rows(subBrands: Set[String]): Vector[PaymentRow] =
    subBrands.toVector.flatMap(id => ctx.views.forView(PaymentsView).where(jsonText("subBrandId") ++ sql" = $id", 100_000))

  private def bucket(status: String) = if Status.isTerminal(status) then status else "pending"

  private def decimal(amount: Long, currency: String) = Currency.from(currency).toOption.map(Amounts.render(amount, _)).getOrElse(amount.toString)

  private def rowJson(r: PaymentRow): ObjectNode =
    Json.obj("paymentId" -> r.paymentId, "merchantId" -> r.subBrandId, "customerRef" -> r.customerId, "amount" -> decimal(r.amount, r.currency),
      "amountCents" -> r.amount, "currency" -> r.currency, "status" -> r.status, "psp" -> r.psp, "pspTransactionId" -> r.pspTransactionId,
      "terminalId" -> r.terminalId, "failureReason" -> r.failureReason, "createdAt" -> r.createdAt.toString)

  /** Search, then counts by status, then the status filter, newest first, paged (`buildCashierPaymentsPage`). */
  def list(op: Operator, merchantId: Option[String], status: Option[String], search: Option[String], page: Option[String],
      pageSize: Option[String]): Respond[Bytes] =
    val st = status.map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("all")
    if !Status.Filters(st) then refuse(400, "invalid_status", s"status must be one of ${Status.Filters.toVector.sorted.mkString(", ")}")
    else
      scoped(op, merchantId).fold(identity, ids =>
        val all = rows(ids)
        val searched = search.map(_.trim.toLowerCase).filter(_.nonEmpty).fold(all)(q =>
          all.filter(r => Seq(r.paymentId, r.customerId, r.psp, r.pspTransactionId).exists(_.toLowerCase.contains(q))))
        val grouped  = searched.groupBy(r => bucket(r.status)).view.mapValues(_.size).toMap
        val counts   = Json.obj(Vector("pending", "captured", "failed", "expired").map(k => k -> grouped.getOrElse(k, 0))*)
        val filtered = searched.filter(r => Status.matches(st, r.status)).sortBy(_.createdAt).reverse
        val p        = page.flatMap(_.toIntOption).map(math.max(1, _)).getOrElse(1)
        val size     = pageSize.flatMap(_.toIntOption).filter(n => n >= 1 && n <= 200).getOrElse(25)
        ok(Json.obj("payments" -> Json.arr(filtered.slice((p - 1) * size, p * size).map(rowJson)*), "total" -> filtered.size, "page" -> p,
          "pageSize" -> size, "statusCounts" -> counts)))

  /** The count, counts by status, captured volume per currency, the daily and per-provider series (`buildCashierSummary`). */
  def summary(op: Operator, merchantId: Option[String]): Respond[Bytes] =
    scoped(op, merchantId).fold(identity, ids =>
      val all      = rows(ids)
      val grouped  = all.groupBy(r => bucket(r.status)).view.mapValues(_.size).toMap
      val captured = all.filter(_.status == Status.Captured)
      val volume = captured.filter(_.amount > 0).groupMapReduce(_.currency)(_.amount)(_ + _).toVector.sortBy((c, a) => (-a, c))
      val daily  = all.groupBy(_.createdAt.toString.take(10)).toVector.sortBy(_._1)
      val psps   = all.groupBy(r => if r.psp.isEmpty then "unknown" else r.psp).toVector.sortBy((p, rs) => (-rs.size, p))
      ok(Json.obj(
        "totalCount" -> all.size, "count" -> all.size,
        "statusCounts" -> Json.obj(Vector("pending", "captured", "failed", "expired").map(k => k -> grouped.getOrElse(k, 0))*),
        "capturedVolume" -> Json.arr(volume.map((c, a) => Json.obj("currency" -> c, "amount" -> decimal(a, c), "amountCents" -> a))*),
        "daily" -> Json.arr(daily.map((d, rs) => Json.obj("date" -> d, "total" -> rs.size, "captured" -> rs.count(_.status == Status.Captured)))*),
        "psps" -> Json.arr(psps.map((p, rs) => Json.obj("psp" -> p, "total" -> rs.size, "captured" -> rs.count(_.status == Status.Captured)))*),
        "capturedWithoutAmount" -> captured.count(_.amount <= 0)
      )))

  /** Captured and not credited, with when, the attempts and the wallet's last answer (FR-023). */
  def uncredited(op: Operator): Respond[Bytes] =
    scoped(op, None).fold(identity, ids =>
      val listed = BackofficeRoutes.uncreditedRows(ctx).filter(r => ids(r.subBrandId)).sortBy(_.capturedAt)
      ok(Json.obj("payments" -> Json.arr(listed.map(r => Json.obj("paymentId" -> r.paymentId, "merchantId" -> r.subBrandId,
        "capturedAt" -> r.capturedAt.map(_.toString), "attempts" -> r.attempts, "lastAnswer" -> r.lastAnswer, "creditStatus" -> r.creditStatus))*))))

  /** An operator's retry of a credit the wallet refused finally (FR-023). */
  def retryCredit(op: Operator, paymentId: String): Respond[Bytes] = guarded {
    val p = ctx.components.forEventSourcedEntity(EntityId(paymentId)).call(Payment.get).invoke()
    if !p.exists then refuse(404, "PAYMENT_NOT_FOUND")
    else
      val s = ctx.subBrand(p.opened.get.subBrandId)
      if !op.permits(ctx.subBrandTarget(s)) then scopeViolation
      else
        val flow = ctx.components.forWorkflow(EntityId(paymentId))
        flow.call(DepositWorkflow.retry).invoke(op.subject): Unit
        io.eitheror.casino.merchant.cashier.Commands.await(flow.call(DepositWorkflow.status).invoke())(f => f.status != DepositFlow.Crediting): Unit
        ok(PaymentRead.json(ctx.components.forEventSourcedEntity(EntityId(paymentId)).call(Payment.get).invoke(), ctx.now()))
  }

object BackofficeRoutes:
  def uncreditedRows(ctx: Ctx): Vector[PaymentRow] =
    ctx.views.forView(PaymentsView).where(jsonText("uncredited") ++ sql" = ${"true"}", 100_000)
