package io.eitheror.casino.payouts.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.limits.Counted
import io.eitheror.casino.payouts.player.{CountRequest, PlayerPayouts}
import io.eitheror.casino.payouts.request.*
import io.eitheror.casino.payouts.terminals.{Catalogue, Terminal}
import io.eitheror.casino.payouts.workflow.{Flow, Job, WithdrawalWorkflow}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat
import scala.util.Try

import Answers.*

/**
 * `/v1/casino/brands/<slug>/players` (contracts/routes.md): the player's own withdrawal requests. The
 * `{playerId}` segment must be the token's player. A request passes R4's gates, then its workflow holds the
 * amount; the answer waits for the hold up to the request bound, then is 503.
 */
final class PlayerEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/players"):
  val acl: Acl = Routes.player(ctx.slug, ctx.authenticate())

  private def mine(playerId: String)(f: => Reply): Reply =
    if !Routes.playerId(principal).contains(playerId) then notYours else f

  private def body(text: String)(f: JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)

  postBody("/{playerId}/withdrawal-requests") { (p: String, text: String) => mine(p)(body(text)(Requests.request(ctx, p, _))) }
  get("/{playerId}/withdrawal-requests") { (p: String) => mine(p)(Requests.list(ctx, p)) }
  delete("/{playerId}/withdrawal-requests/{requestId}") { (p: String, r: String) => mine(p)(Requests.cancel(ctx, p, r)) }

  get("/{playerId}/withdrawal-terminals") { (p: String) => mine(p)(Accounts.terminals(ctx, p, request.query.raw("currency"))) }

  get("/{playerId}/payout-destinations") { (p: String) => mine(p)(Accounts.destinations(ctx, p)) }
  postBody("/{playerId}/payout-destinations") { (p: String, text: String) => mine(p)(body(text)(Accounts.save(ctx, p, _))) }
  putBody("/{playerId}/payout-destinations/{id}") { (p: String, id: String, text: String) =>
    mine(p)(body(text)(Accounts.setDefault(ctx, p, id, _)))
  }
  delete("/{playerId}/payout-destinations/{id}") { (p: String, id: String) => mine(p)(Accounts.delete(ctx, p, id)) }

  // The operator's view of one player's requests (FR-014): the brand's operators, with a finance or admin role.
  withAcl(Routes.operator(ctx.slug, ctx.authenticate())) {
    get("/{playerId}/withdrawal-history") { (p: String) =>
      if !principal.roles.exists(FinanceRoles) then forbidden
      else ok(Json.obj("requests" -> Json.arr(ctx.byPlayer(p).map(Operator.row)*)))
    }
  }

/** The request routes' work, apart from the endpoint so a suite can read it. */
object Requests:

  /** The body in the original's names; an amount that is not a whole number of minor units is told apart from none. */
  def draft(b: JsonNode): Draft =
    val amount = Option(b.get("amount")).filterNot(_.isNull) match
      case None                                       => Amount.Absent
      case Some(n) if n.isIntegralNumber && n.canConvertToLong => Amount.Whole(n.asLong)
      case Some(n) if n.isTextual => n.asText.trim.toLongOption.fold(Amount.NotWhole)(Amount.Whole(_))
      case Some(_)                                    => Amount.NotWhole
    Draft(b.text("key"), amount, b.text("currency").map(_.toUpperCase), b.text("terminalId"), b.text("destinationId"),
      b.text("walletAddress"), b.text("cryptoCurrency").map(_.toUpperCase), b.text("payoutMethod"), b.text("mobile"),
      b.text("bankAccount"), b.text("institutionCode"), b.text("transitNumber"), b.bool("confirmForfeit").getOrElse(false))

  /**
   * The digest of a body under its key: the same body is the same request, another is `KEY_REUSED`. The fields
   * are named and ordered here, so a field added to `Draft` changes no digest of a request in flight.
   */
  def fingerprint(d: Draft): String =
    val amount = d.amount match
      case Amount.Whole(v) => v.toString
      case other           => other.toString
    val fields = Vector(amount, d.currency, d.terminalId, d.destinationId, d.walletAddress, d.cryptoCurrency, d.payoutMethod,
      d.mobile, d.bankAccount, d.institutionCode, d.transitNumber, d.confirmForfeit).map {
      case o: Option[?] => o.fold("\u0000")(_.toString)
      case v            => v.toString
    }
    HexFormat.of.formatHex(MessageDigest.getInstance("SHA-256").digest(fields.mkString("\u001f").getBytes(UTF_8)))

  def ref(t: Terminal): TerminalRef =
    TerminalRef(t.id, t.subBrandId, t.name, t.gateway, t.scheme, t.supportsWithdrawal, t.hasStatus, t.reviewWindow)

  /** The wallet's refusal of the hold in the original's terms (R4): its code, with the figures the preview gave. */
  def holdRefusal(code: String, status: Int, message: String, preview: Preview): Refusal = code match
    case "WITHDRAWAL_REQUEST_ALREADY_PENDING"          => Refusals.AlreadyPending
    case "INSUFFICIENT_FUNDS"                          => Refusals.Insufficient
    case "WITHDRAWAL_BLOCKED_TURNOVER"                 => Refusals.Turnover
    case "WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT" => Refusals.afterForfeit(preview.mostWithdrawable, preview.totalForfeit)
    case "KEY_REUSED"                                  => Refusals.KeyReused
    case other                                         => Refusal(status, other, message)

  def request(ctx: Ctx, playerId: String, b: JsonNode): Reply =
    val d = draft(b)
    d.key match
      case None => refused(Refusals.MissingKey)
      case Some(key) =>
        val id    = WithdrawalWorkflow.requestId(playerId, key)
        val first = ctx.flow(id)
        if first.started then
          if first.job.exists(_.fingerprint != fingerprint(d)) then refused(Refusals.KeyReused) else answered(ctx, id)
        else gated(ctx, playerId, id, d)

  private def gated(ctx: Ctx, playerId: String, id: String, d: Draft): Reply =
    val at        = ctx.now()
    val state     = ctx.playerState(playerId)
    val currency  = d.currency.flatMap(c => Try(Currency.unsafe(c)).toOption)
    val terminals = currency.fold(Vector.empty[Terminal])(c => Catalogue.read(ctx.components, ctx.gateway, ctx.slug, c, at))
    val limits    = ctx.limits
    val standing = Option.when(state.known && !state.erased)(Standing(state.kyc, state.deposits, state.recent,
      did => state.destination(did).filter(_.active).map(dest => (Holder(dest.givenName, dest.familyName), dest.iban))))
    val wallet = ctx.wallet
    Gates.check(Inputs(d, standing, terminals, limits.of, at, (c, amount) => wallet.preview(playerId, c, amount))) match
      case Left(refusal) => refused(refusal)
      case Right(passed) =>
        val job = Job(id, playerId, passed.currency.code, passed.amount, ref(passed.terminal), passed.payee, state.kyc.wire,
          passed.confirmForfeit, passed.preview, fingerprint(d))
        val started = ctx.workflow(id).call(WithdrawalWorkflow.start).invoke(job)
        if started.refusal.contains(Refusals.KeyReused.code) then refused(Refusals.KeyReused) else answered(ctx, id)

  /** The workflow's outcome once the hold is answered, waited for up to the request bound (SC-005). */
  private def answered(ctx: Ctx, id: String): Reply =
    val until = System.nanoTime() + ctx.settings.requestBound.toNanos
    // Past the review pause the request exists whatever the workflow does next (a cancel, an approval).
    def settled(f: Flow) = f.step != Flow.New && f.step != Flow.Holding && f.step != Flow.Opening
    def poll(): Option[Flow] =
      val f = ctx.flow(id)
      if settled(f) then Some(f)
      else if System.nanoTime() > until then None
      else { Thread.sleep(10); poll() }
    poll() match
      case None => unavailable
      case Some(f) if f.step == Flow.Refused =>
        val r = f.refusal.get
        val refusal = holdRefusal(r.code, r.status, r.message, f.job.fold(Preview())(_.preview))
        refused(f.job.fold(refusal)(j => Refusals.inCurrency(refusal, j.currency)))
      case Some(_) =>
        val s = ctx.requestState(id)
        if !s.opened then unavailable
        else
          // Counted before the answer, so the next request sees it; RequestsConsumer counts it too, should this be lost.
          ctx.player(s.playerId).call(PlayerPayouts.countRequest)
            .invoke(CountRequest(Counted(s.requestId, s.amount, s.currencyOf, s.createdAt), ctx.now())): Unit
          created(Json.obj("requestId" -> s.requestId, "status" -> s.status.upper, "amount" -> s.amount, "currency" -> s.currency,
            "terminalId" -> s.terminal.map(_.id), "createdAt" -> s.createdAt.toString, "bonusForfeited" -> s.forfeit.bonusForfeited,
            "forfeitedAmount" -> s.forfeit.forfeitedAmount, "clawbackAmount" -> s.forfeit.clawbackAmount))

  def list(ctx: Ctx, playerId: String): Reply =
    ok(Json.obj("requests" -> Json.arr(ctx.byPlayer(playerId).map(r =>
      Json.obj("requestId" -> r.requestId, "amount" -> r.amount, "currency" -> r.currency, "terminalId" -> r.terminalId,
        "terminalName" -> r.terminalName, "terminalScheme" -> r.method, "createdAt" -> r.createdAt.toString, "status" -> r.status.toUpperCase, "payoutStatus" -> r.payoutStatus.toUpperCase))*)))

  /** The player's cancel, through the workflow, which releases the hold before the request records it (FR-014). */
  def cancel(ctx: Ctx, playerId: String, requestId: String): Reply =
    val s = ctx.requestState(requestId)
    if !s.opened || s.playerId != playerId then refused(Refusal(404, RequestCodes.NotFound, "no such withdrawal request"))
    else
      val answer = ctx.workflow(requestId).call(WithdrawalWorkflow.cancel).invoke(Actor.Player)
      answer.refusal match
        case Some(_) if s.status == Status.Cancelled => ok(cancelled(s))
        case Some(code) => refused(Refusal(409, code, "the withdrawal request can no longer be cancelled"))
        case None =>
          val until = System.nanoTime() + ctx.settings.requestBound.toNanos
          def done(): Boolean =
            ctx.requestState(requestId).status == Status.Cancelled || (System.nanoTime() < until && { Thread.sleep(10); done() })
          if done() then ok(cancelled(ctx.requestState(requestId))) else unavailable

  /** The original's cancel answer: the amount asked, and what the release gave back. */
  private def cancelled(s: RequestState): JsonNode =
    Json.obj((Seq[(String, Any)]("requestId" -> s.requestId, "status" -> Status.Cancelled.upper, "amount" -> s.amount) ++ returned(s))*)
