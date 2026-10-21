package io.eitheror.casino.merchant.cashier

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.api.Answers.*
import io.eitheror.casino.merchant.api.{Ctx, MerchantOps}
import io.eitheror.casino.merchant.deposits.*
import io.eitheror.casino.merchant.hierarchy.{OrgStatus, SubBrandState}
import io.eitheror.casino.merchant.payments.*
import io.eitheror.casino.merchant.routing.*
import io.eitheror.casino.merchant.terminals.TerminalState

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * The cashier's commands, served to `ingest` (spec 006) by its identity (FR-013 to FR-017, FR-024;
 * contracts/commands.md). `ingest` has verified the merchant key and resolved the sub-brand; this
 * service trusts the sub-brand the body names because only `ingest` can call.
 */
final class Commands(ctx: Ctx):

  private def components = ctx.components
  private def now()      = ctx.now()

  // ── create a session (FR-013, FR-014) ────────────────────────────────────────────────────────

  def createSession(text: String): Respond[Bytes] = guarded {
    body(text).fold(identity, b =>
      val subBrandId = b.textOr("subBrandId", "")
      val sb         = ctx.subBrand(subBrandId)
      val amountNode = Option(b.get("amount")).orElse(Option(b.get("amountCents")))
      val amount     = amountNode.filter(n => n.isIntegralNumber && n.canConvertToLong).map(_.asLong)
      val currency   = Currency.from(b.textOr("currency", ""))
      val country    = b.text("country")
      val customer   = b.text("customerId")
      if !sb.exists then refuse(404, "SUB_BRAND_NOT_FOUND")
      else if sb.status != OrgStatus.Active then refuse(409, "SUB_BRAND_INACTIVE")
      else if amount.forall(_ <= 0) then
        refuse(400, "INVALID_AMOUNT", "amount must be a positive whole number of minor units", "field" -> "amount")
      else if currency.isLeft then refuse(400, "INVALID_REQUEST", s"'${b.textOr("currency", "")}' is not a currency", "field" -> "currency")
      else if country.isEmpty || customer.isEmpty then refuse(400, "MISSING_ROUTING_CONTEXT")
      else if Commands.wrongBrand(sb, customer.get) then refuse(400, "WRONG_BRAND", s"the customer is not a player of ${sb.brand.getOrElse("")}")
      else
        Resolution.resolve(components, subBrandId) match
          case None => refuse(400, "NO_ROUTING_TREE")
          case Some(resolved) =>
            val count = depositCount(subBrandId, customer.get)
            Evaluator.evaluate(resolved.tree, RoutingContext(currency.toOption.get.code, amount.get, country.get, customer.get, count)) match
              case Outcome.NoTree  => refuse(400, "NO_ROUTING_TREE")
              case Outcome.NoMatch => refuse(400, "NO_ROUTING_MATCH")
              case Outcome.Match(ids) =>
                val offered = offer(sb, ids, currency.toOption.get.code)
                if offered.isEmpty then refuse(400, "NO_ROUTING_MATCH")
                else
                  val token    = UUID.randomUUID().toString
                  val deadline = now().plus(ctx.live.paymentDeadline)
                  val state = SessionState(token, subBrandId, sb.brand, b.text("transactionId").getOrElse(UUID.randomUUID().toString), amount.get,
                    currency.toOption.get.code, country.get, customer.get, b.textOr("redirectUrl", ""), CustomerFields.from(b.text), offered,
                    Some(resolved.from), deadline = Some(deadline))
                  components.forKeyValueEntity(EntityId(token)).call(Session.open).invoke(OpenSession(state, now())): Unit
                  ok(Json.obj("session_token" -> token, "transactionId" -> state.transactionId, "redirectUrl" -> state.redirectUrl,
                    "available_terminals" -> Json.arr(offered.map(Commands.offeredJson)*),
                    "resolvedFrom" -> Json.obj("level" -> resolved.from.level.api, "entityId" -> resolved.from.entityId, "name" -> resolved.from.name))))
  }

  /** The player's captured deposits at the sub-brand (FR-012), from the payments view. */
  def depositCount(subBrandId: String, customerId: String): Long = PaymentsView.depositCount(ctx.views, subBrandId, customerId)

  /**
   * What the tree reached, as the session offers it: a credential named so when it is active, a
   * terminal when it is `ACTIVE` and takes deposits (`CashierRoutes.scala:1540-1570`).
   */
  private def offer(sb: SubBrandState, ids: Vector[String], currency: String): Vector[OfferedTerminal] =
    val ops = MerchantOps(ctx)
    // A tree names a terminal by its id (read from the entity, so one just made is offered) or by its name (the view).
    lazy val terminals = ops.terminalsOf(sb.id)
    ids.distinct.flatMap { id =>
      sb.liveCredentials.find(c => c.name == id && c.active).map(c =>
        OfferedTerminal(c.name, c.name, c.scheme.getOrElse(c.provider), Some(c.provider), Vector.empty, Some(c.id))
      ).orElse(ops.terminalOf(sb.id, id).orElse(terminals.find(_.name == id)).filter(t => t.status == TerminalState.Active && t.supportsDeposit).map(t =>
        OfferedTerminal(t.id, t.name, t.scheme, Some(t.gateway),
          t.properties.get("supportedCurrencies").toVector.flatMap(_.split(',').map(_.trim).filter(_.nonEmpty)), None)))
    }

  // ── submit (FR-015) ──────────────────────────────────────────────────────────────────────────

  def submit(token: String, text: String): Respond[Bytes] = guarded {
    body(text).fold(identity, b =>
      val session = components.forKeyValueEntity(EntityId(token)).call(Session.get).invoke()
      if !session.exists then refuse(404, "SESSION_NOT_FOUND")
      else
        session.paymentId match
          case Some(paymentId) => answerSubmit(token, paymentId)
          case None =>
            val selected = b.textOr("selectedTerminalEntityId", "")
            session.offered.find(_.terminalEntityId == selected) match
              case None => refuse(400, "terminal_not_found", s"terminal '$selected' was not offered", "terminal" -> selected)
              case Some(t) =>
                val crypto = b.text("cryptoCurrency")
                if t.scheme.equalsIgnoreCase("CRYPTO") && crypto.isEmpty then refuse(400, "missing_crypto_currency")
                else
                  val candidate = UUID.randomUUID().toString
                  val paymentId = components.forKeyValueEntity(EntityId(token)).call(Session.claim).invoke(candidate)
                  if paymentId == candidate then start(session, t, crypto, CustomerFields.from(b.text), paymentId)
                  answerSubmit(token, paymentId))
  }

  private def start(s: SessionState, t: OfferedTerminal, crypto: Option[String], entered: CustomerFields, paymentId: String): Unit =
    val retention = ctx.live.customerRetention.toMillis.millis
    components.forKeyValueEntity(EntityId(paymentId)).call(PaymentCustomer.keep).invoke(KeepCustomer(s.customerId, s.customer.overriddenBy(entered), retention)): Unit
    val scheme = if t.scheme.equalsIgnoreCase("CRYPTO") then crypto.get else t.scheme
    val job = DepositJob(paymentId, s.subBrandId, s.brand, s.customerId, Commands.playerOf(s.customerId), s.amount, s.currency,
      t.gateway.getOrElse(""), scheme, t.terminalEntityId, if t.credentialId.isDefined then None else Some(t.terminalEntityId),
      s.transactionId, s.redirectUrl, now(), now().plus(ctx.live.paymentDeadline))
    try components.forWorkflow(EntityId(paymentId)).call(DepositWorkflow.start).invoke(job): Unit
    catch case e: CommandError if e.code == ErrorCode.Conflict => ()

  /** The submit's answer, from the payment once the gateway has initiated it, polled up to 2 s. */
  private def answerSubmit(token: String, paymentId: String): Respond[Bytes] =
    val flow = components.forWorkflow(EntityId(paymentId))
    Commands.await(flow.call(DepositWorkflow.status).invoke())(f => f.status != DepositFlow.Initiating && f.status != DepositFlow.New) match
      case None => refuse(503, "OUTCOME_PENDING", "The outcome is still being decided; retry")
      case Some(f) if f.status == DepositFlow.Refused =>
        components.forKeyValueEntity(EntityId(token)).call(Session.release).invoke(paymentId): Unit
        val r = f.refusal.get
        refuse(r.status, r.error, r.message.getOrElse(""))
      case Some(_) =>
        val p = components.forEventSourcedEntity(EntityId(paymentId)).call(Payment.get).invoke()
        if !p.exists then refuse(503, "OUTCOME_PENDING", "The outcome is still being decided; retry")
        else
          val o = p.opened.get
          val answer = Json.obj("paymentId" -> paymentId, "status" -> p.status)
          o.crypto.foreach { c =>
            Json.set(answer, "paymentAddress", c.paymentAddress); Json.set(answer, "cryptoAmount", c.cryptoAmount)
            Json.set(answer, "cryptoCurrency", c.cryptoCurrency); Json.set(answer, "validUntil", c.validUntil)
          }
          ok(answer)

  // ── abandon (FR-024) and read (FR-017) ───────────────────────────────────────────────────────

  def abandon(token: String): Respond[Bytes] = guarded {
    val session = components.forKeyValueEntity(EntityId(token)).call(Session.get).invoke()
    session.paymentId.filter(_ => session.exists) match
      case None => refuse(404, if session.exists then "PAYMENT_NOT_FOUND" else "SESSION_NOT_FOUND")
      case Some(paymentId) =>
        val flow = components.forWorkflow(EntityId(paymentId))
        val f = flow.call(DepositWorkflow.fail).invoke(FailInput(Some(FailureReason.AbandonedCode), FailureReason.Abandoned, None, now()))
        if f.status == DepositFlow.Failing then Commands.await(flow.call(DepositWorkflow.status).invoke())(_.status != DepositFlow.Failing): Unit
        read(paymentId)
  }

  def read(paymentId: String): Respond[Bytes] = guarded {
    val p = components.forEventSourcedEntity(EntityId(paymentId)).call(Payment.get).invoke()
    if !p.exists then refuse(404, "PAYMENT_NOT_FOUND") else ok(PaymentRead.json(p, now()))
  }

object Commands:
  /** The player part of `brand:player`; a bare id is the player. */
  def playerOf(customerId: String): String = customerId.split(':').lastOption.getOrElse(customerId)

  /** A casino sub-brand serves only its own brand's players (Clarifications); `brand:player` names it. */
  def wrongBrand(sb: SubBrandState, customerId: String): Boolean =
    sb.brand.exists(brand => customerId.contains(':') && customerId.takeWhile(_ != ':') != brand)

  def offeredJson(t: OfferedTerminal): ObjectNode =
    val o = Json.obj("terminalEntityId" -> t.terminalEntityId, "name" -> t.name, "scheme" -> t.scheme)
    if t.credentialId.isEmpty then
      Json.set(o, "gateway", t.gateway)
      if t.supportedCurrencies.nonEmpty then Json.set(o, "supportedCurrencies", t.supportedCurrencies)
    o

  /** Polls `read` every millisecond until `done`, for up to 2 s (the wallet's `Flows`, research R7). */
  def await[A](read: => A, bound: FiniteDuration = 2.seconds)(done: A => Boolean): Option[A] =
    val deadline = System.nanoTime() + bound.toNanos
    var last     = read
    while !done(last) && System.nanoTime() < deadline do
      Thread.sleep(1)
      last = read
    Option.when(done(last))(last)
