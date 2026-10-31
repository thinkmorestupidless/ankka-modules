package io.eitheror.casino.payouts.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.Layout.GrantTarget
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.request.*
import io.eitheror.casino.payouts.workflow.{Flow, FlowCodes, Outcome, WithdrawalWorkflow}

import Answers.*

/**
 * `/v1/casino/brands/<slug>/withdrawals` (contracts/routes.md; R6, R8): the provider's outcome as the gateway
 * delivers it, unsigned and admitted by the grant `payments`/`psp-gateway` holds. The gateway retries a
 * 429, a 5xx or no answer; any other 4xx is final, so a refusal here is one it should not send again.
 */
final class OutcomeEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/withdrawals"):
  val acl: Acl = Routes.service(Some(OutcomeEndpoint.Grant))

  postBody("/{requestId}/outcome") { (id: String, text: String) =>
    Json.parseObject(text).fold(badBody)(b => Outcomes.deliver(ctx, id, b.text("outcome"), b.text("providerReference"), b.text("reason")))
  }

object OutcomeEndpoint:
  val Grant: GrantTarget.Route = GrantTarget.Route("payouts", "POST", "/v1/casino/brands/{brand}/withdrawals/{requestId}/outcome")

object Outcomes:
  val Known: Set[String] = Set("completed", "failed", "reversed")

  /** The settling steps an accepted outcome runs; the answer waits for them, up to the request bound. */
  private val Settling = Set(Flow.Confirming, Flow.Releasing, Flow.Reversing)

  def deliver(ctx: Ctx, id: String, outcome: Option[String], reference: Option[String], reason: Option[String]): Reply =
    val word = outcome.map(_.trim.toLowerCase)
    val s    = ctx.requestState(id)
    def record(answer: String) =
      ctx.request(id).call(WithdrawalRequest.record)
        .invoke(Record(OutcomeRecord(Source.Outcome, answer, reason, reference, None, ctx.now()))): Unit
    if !word.exists(Known) then refusal(400, "UNRECOGNISED_OUTCOME", "the outcome is not completed, failed or reversed")
    else if !s.opened then refusal(404, RequestCodes.NotFound, "no such withdrawal request")
    else if reference.exists(r => s.providerReference.exists(_ != r)) then
      // Recorded, so the operator sees a provider naming another payout (FR-020).
      ctx.request(id).call(WithdrawalRequest.referenceMismatch).invoke(ReferenceMismatch(reference.get, ctx.now())): Unit
      refusal(409, "REFERENCE_MISMATCH", "the provider's reference is not this request's")
    else
      val before = ctx.flow(id).step
      val a = ctx.workflow(id).call(WithdrawalWorkflow.outcome).invoke(Outcome(word.get, reference, reason))
      a.refusal match
        case Some(FlowCodes.Busy) => unavailable
        case Some(code) =>
          record(word.get)
          if code == RequestCodes.NotCompleted then refusal(409, code, "only a completed withdrawal can be reversed")
          else refusal(409, FlowCodes.AlreadyResolved, "the withdrawal request was settled with another outcome")
        case None =>
          // A repeat of the outcome that settled the request moves nothing and is not recorded again.
          if Flow.Pursued(before) || before == Flow.Completed then record(word.get)
          val until = System.nanoTime() + ctx.settings.requestBound.toNanos
          def poll(): Unit =
            if Settling(ctx.flow(id).step) && System.nanoTime() < until then
              Thread.sleep(10)
              poll()
          poll()
          ok(Json.obj("requestId" -> id, "status" -> ctx.requestState(id).status.upper))
