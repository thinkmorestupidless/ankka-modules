package io.eitheror.casino.pspgateway.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.deposits.Initiate
import io.eitheror.casino.pspgateway.merchant.MerchantClient
import io.eitheror.casino.pspgateway.sessions.DepositSession

/** The project's services this one serves by identity: `merchant`, of `payments`. */
object Identities:
  val Project       = "payments"
  val merchant: Acl = Acl.allowCallers(Callers.service(Project, "merchant"))

  /** A brand's `payouts`, of any project (the grant it was made), or this machine (contracts/payouts.md). */
  val payouts: Acl = Acl.AllowIf(r => r.caller match
    case Caller.Service(_, "payouts") | Caller.Local => true
    case _                                            => false)

/** `/v1/psp`: `merchant` initiates a deposit and reads a session's status (contracts/merchant.md §1, §2). */
final class PspEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/psp"):
  val acl: Acl = Identities.merchant

  private val initiate =
    Initiate(ctx.components, MerchantClient.http(ctx.clients.services, ctx.settings.merchantService), ctx.settings)

  postBody("/hpp/initiate") { (body: String) =>
    Refusal.guarded {
      val answer = initiate(body)
      Refusal.json(answer.status, answer.body)
    }
  }

  get("/sessions/{reference}") { (reference: String) =>
    Refusal.guarded {
      val state = ctx.components.forEventSourcedEntity(EntityId(reference)).call(DepositSession.get).invoke()
      if !state.exists then Refusal(404, "not_found")
      else
        val s = state.status
        Refusal.ok(Json.obj("state" -> s.state, "outcome" -> s.outcome, "delivered" -> s.delivered,
          "undelivered" -> s.undelivered, "refused" -> s.refused, "updatedAt" -> s.updatedAt.map(_.toString)))
    }
  }
