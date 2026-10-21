package io.eitheror.casino.pspgateway.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.pspgateway.merchant.MerchantClient
import io.eitheror.casino.pspgateway.payouts.{Initiate, Status, Terminals}

/** The brand a caller is: its project for a brand's `payouts`; none for this machine, which may name any. */
private def brandOf(c: Caller): Option[String] = c match
  case Caller.Service(project, _) => Some(project)
  case _                          => None

/** `/v1/payments/withdrawals`: a brand's `payouts` initiates a payout and reads its status (contracts/payouts.md). */
final class WithdrawalsEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/payments/withdrawals"):
  val acl: Acl = Identities.payouts

  private val merchant = MerchantClient.http(ctx.clients.services, ctx.settings.merchantService)
  private val initiate = Initiate(ctx.components, merchant, ctx.settings)
  private val status   = Status(ctx.components, merchant, ctx.settings)

  postBody("/") { (body: String) =>
    Refusal.guarded {
      val a = initiate(body, brandOf(request.caller))
      Refusal.json(a.status, a.body)
    }
  }

  get("/{requestId}") { (requestId: String) =>
    Refusal.guarded {
      val a = status(requestId, brandOf(request.caller))
      Refusal.json(a.status, a.body)
    }
  }

/** `/v1/payments/withdrawal-terminals`: the brand's withdrawal-capable terminals (FR-009a). */
final class WithdrawalTerminalsEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/payments/withdrawal-terminals"):
  val acl: Acl = Identities.payouts

  private val terminals = Terminals(MerchantClient.http(ctx.clients.services, ctx.settings.merchantService))

  get("/") { () =>
    Refusal.guarded {
      brandOf(request.caller).orElse(request.query.raw("brand")) match
        case None => Refusal(400, "bad_request", Some("brand is required from this machine"))
        case Some(brand) =>
          val a = terminals(brand, request.query.raw("currency"))
          Refusal.json(a.status, a.body)
    }
  }
