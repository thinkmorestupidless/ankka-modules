package io.eitheror.casino.payouts.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.player.{Iban, Kyc}
import io.eitheror.casino.payouts.queue.{QueueRow, WithdrawalQueue}
import io.eitheror.casino.payouts.request.*
import io.eitheror.casino.payouts.workflow.{ByOperator, Flow, FlowAnswer, FlowCodes, WithdrawalWorkflow}

import java.time.Duration

import Answers.*

/**
 * `/v1/casino/brands/<slug>/withdrawal-requests` (contracts/routes.md; R6): the brand's queue, a request, and
 * the operator's decisions and settlements. The ACL admits an operator of the brand; each route asks for a
 * finance or admin role, and any other is `forbidden`.
 */
final class OperatorEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/withdrawal-requests"):
  val acl: Acl = Routes.operator(ctx.slug, ctx.authenticate())

  private def finance(f: => Reply): Reply = if principal.roles.exists(FinanceRoles) then f else forbidden
  private def by(reason: Option[String]) = ByOperator(principal.subject, reason.map(Personal(_)))
  private def reason(text: String, field: String): Option[String] =
    Json.parseObject(text).flatMap(_.text(field)).map(_.trim).filter(_.nonEmpty)

  get("/") { () => finance(Operator.queue(ctx, request.query.raw)) }
  get("/{requestId}") { (id: String) => finance(Operator.detail(ctx, id)) }

  postBody("/{requestId}/approve") { (id: String, _: String) =>
    finance(Operator.decide(ctx, id)(ctx.workflow(id).call(WithdrawalWorkflow.approve).invoke(by(None))))
  }
  postBody("/{requestId}/reject") { (id: String, text: String) =>
    finance(Operator.decide(ctx, id)(ctx.workflow(id).call(WithdrawalWorkflow.reject).invoke(by(reason(text, "reason")))))
  }
  postBody("/{requestId}/confirm-payout") { (id: String, text: String) =>
    finance(Operator.settle(ctx, id)(ctx.workflow(id).call(WithdrawalWorkflow.confirmPayout).invoke(by(reason(text, "note")))))
  }
  postBody("/{requestId}/mark-payout-failed") { (id: String, text: String) =>
    val why = reason(text, "reason").orElse(Some(Operator.DefaultFailure))
    finance(Operator.settle(ctx, id)(ctx.workflow(id).call(WithdrawalWorkflow.markFailed).invoke(by(why))))
  }

/** The operator routes' work, apart from the endpoint so a suite can read it. */
object Operator:
  /** The original's reason when the operator gives none (`WithdrawalRoutes.scala`). */
  val DefaultFailure = "Manual payout not completed"

  val Stale: Duration = Duration.ofHours(24)

  private val Bands = Set("lt500", "500to5000", "gt5000")

  private def notFound = refusal(404, RequestCodes.NotFound, "no such withdrawal request")
  private def invalid(parameter: String) = refusal(400, "INVALID_REQUEST", s"$parameter is not a valid value", "field" -> parameter)

  /** The query as a filter and a page, or the first parameter that is not valid. */
  def filter(q: String => Option[String], now: java.time.Instant): Either[String, (WithdrawalQueue.Filter, Int, Int)] =
    def opt(name: String) = q(name).map(_.trim).filter(_.nonEmpty)
    def number(name: String, default: Int) = opt(name).fold[Either[String, Int]](Right(default))(v => v.toIntOption.toRight(name))
    for
      status <- opt("status") match
        case None                                  => Right(Some(Status.PendingReview.wire))
        case Some(v) if v.equalsIgnoreCase("ALL")  => Right(None)
        case Some(v) => Status.from(v).map(s => Some(s.wire)).toRight("status")
      payout <- opt("payoutStatus").fold[Either[String, Option[String]]](Right(None))(v =>
        PayoutStatus.from(v).map(p => Some(p.wire)).toRight("payoutStatus"))
      kyc <- opt("kyc").fold[Either[String, Option[String]]](Right(None))(v => Kyc.from(v.toLowerCase).map(k => Some(k.wire)).toRight("kyc"))
      band <- opt("amountRange").fold[Either[String, Option[String]]](Right(None))(v => Either.cond(Bands(v), Some(v), "amountRange"))
      stale <- opt("stale").fold[Either[String, Boolean]](Right(false))(v => v.toBooleanOption.toRight("stale"))
      page <- number("page", 1)
      size <- number("pageSize", 50)
      _    <- Either.cond(page >= 1, (), "page")
      _    <- Either.cond(size >= 1 && size <= WithdrawalQueue.MaxPageSize, (), "pageSize")
    yield
      val waiting = if stale then payout.orElse(Some(PayoutStatus.Initiated.wire)) else payout
      (WithdrawalQueue.Filter(status, waiting, kyc, opt("method"), band, opt("playerSearch"),
        Option.when(stale)(now.minus(Stale))), page, size)

  def queue(ctx: Ctx, q: String => Option[String]): Reply =
    filter(q, ctx.now()) match
      case Left(parameter) => invalid(parameter)
      case Right((f, page, size)) =>
        val p = WithdrawalQueue.page(ctx.queue, f, page, size)
        ok(Json.obj("requests" -> Json.arr(p.rows.map(row)*), "total" -> p.total, "page" -> p.page, "pageSize" -> p.pageSize))

  /** A row as contracts/routes.md has it. */
  def row(r: QueueRow): JsonNode =
    Json.obj("requestId" -> r.requestId, "playerId" -> r.playerId, "kycStatus" -> r.kyc, "amount" -> r.amount, "currency" -> r.currency,
      "terminalName" -> r.terminalName, "method" -> r.method, "destinationLastFour" -> r.destinationLastFour,
      "createdAt" -> r.createdAt.toString, "status" -> r.status.toUpperCase, "payoutStatus" -> r.payoutStatus.toUpperCase)

  /** The request with no `Personal` value but the holder's names and the IBAN's last four. */
  def detail(ctx: Ctx, id: String): Reply =
    val s = ctx.requestState(id)
    if !s.opened then notFound
    else
      val t = s.terminal
      ok(Json.obj(
        "requestId" -> s.requestId, "playerId" -> s.playerId, "kycStatus" -> s.kyc, "amount" -> s.amount, "paidAmount" -> s.paidAmount,
        "currency" -> s.currency, "status" -> s.status.upper, "payoutStatus" -> s.payoutStatus.upper,
        "terminal" -> t.map(t => Json.obj("id" -> t.id, "name" -> t.name, "gateway" -> t.gateway, "scheme" -> t.scheme)),
        "destination" -> s.payee.destinationId.map(d => Json.obj("destinationId" -> d,
          "firstName" -> s.payee.holder.map(_.givenName.reveal), "lastName" -> s.payee.holder.map(_.familyName.reveal),
          "ibanLastFour" -> s.payee.iban.map(i => Iban.lastFour(i.reveal)))),
        "bonusForfeited" -> s.forfeit.bonusForfeited, "forfeitedAmount" -> s.forfeit.forfeitedAmount,
        "clawbackAmount" -> s.forfeit.clawbackAmount, "providerReference" -> s.providerReference,
        "decisions" -> Json.arr(s.decisions.map(d => Json.obj("approved" -> d.approved, "operatorId" -> d.operatorId, "at" -> d.at.toString))*),
        "outcomes" -> Json.arr(s.outcomes.map(o => Json.obj("source" -> o.source.wire, "answer" -> o.answer, "reasonCode" -> o.reasonCode,
          "reference" -> o.reference, "at" -> o.at.toString))*),
        "createdAt" -> s.createdAt.toString, "initiatedAt" -> s.initiatedAt.map(_.toString), "settledAt" -> s.settledAt.map(_.toString)))

  private def answer(s: RequestState): Reply =
    ok(Json.obj((Seq[(String, Any)]("requestId" -> s.requestId, "status" -> s.status.upper, "payoutStatus" -> s.payoutStatus.upper)
      ++ returned(s))*))

  /** Waits, up to the request bound, until the workflow is past `running`; then the request as it stands. */
  private def after(ctx: Ctx, id: String, running: Set[String]): RequestState =
    val until = System.nanoTime() + ctx.settings.requestBound.toNanos
    def poll(): Unit =
      if running(ctx.flow(id).step) && System.nanoTime() < until then
        Thread.sleep(10)
        poll()
    poll()
    ctx.requestState(id)

  /** An approval or rejection: a request decided, or one whose decision is running, is not pending (R1). */
  def decide(ctx: Ctx, id: String)(call: => FlowAnswer): Reply =
    if !ctx.requestState(id).opened then notFound
    else
      val a = call
      a.refusal match
        case Some(_) => refusal(409, RequestCodes.NotPending, "the withdrawal request is not pending review")
        // Through the payout's decision, as the original answered: parked for manual payout, or initiated.
        case None    => answer(after(ctx, id, Set(Flow.Approving, Flow.Paying, Flow.Initiating, Flow.Releasing)))

  /** A settlement by hand of a request awaiting manual payout or marked needs review (FR-016). */
  def settle(ctx: Ctx, id: String)(call: => FlowAnswer): Reply =
    if !ctx.requestState(id).opened then notFound
    else
      val a = call
      a.refusal match
        case Some(FlowCodes.Busy) => unavailable
        case Some(_)              => refusal(409, RequestCodes.NotAwaitingPayout, "the withdrawal request is not awaiting payout")
        case None =>
          val s = after(ctx, id, Set(Flow.Confirming, Flow.Releasing))
          ok(Json.obj((Seq[(String, Any)]("requestId" -> s.requestId, "status" -> s.status.upper) ++ returned(s))*))
