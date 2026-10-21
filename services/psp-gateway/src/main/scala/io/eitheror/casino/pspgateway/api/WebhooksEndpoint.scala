package io.eitheror.casino.pspgateway.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.pspgateway.deposits.Webhooks
import io.eitheror.casino.pspgateway.http.Raw
import io.eitheror.casino.pspgateway.merchant.MerchantClient
import io.eitheror.casino.pspgateway.providers.morphosis
import io.eitheror.casino.pspgateway.sessions.Provider

/**
 * `/v1/webhooks`: the providers (contracts/routes.md). Every body is taken raw and verified in the
 * handler before anything is read from it (FR-010, FR-012).
 */
final class WebhooksEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/webhooks"):
  val acl: Acl = Routes.public

  private val webhooks = Webhooks(ctx.components, MerchantClient.http(ctx.clients.services, ctx.settings.merchantService))

  /** The body untouched, with what the providers read beside it: Gigadat's query, Morphosis's token. */
  private def raw(body: String, extra: Map[String, String] = Map.empty): Raw =
    val query = WebhooksEndpoint.QueryNames.flatMap(n => request.query.raw(n).map(n -> _)).toMap
    Raw(body, request.header("content-type"), request.headers, query ++ extra)

  private def deposit(provider: Provider, r: => Raw) = Refusal.guarded(Refusal.reply(webhooks.deposit(provider, r)))
  private def payout(provider: Provider, r: => Raw)  = Refusal.guarded(Refusal.reply(webhooks.payout(provider, r)))

  postBody("/dreamzpay") { (body: String) => deposit(Provider.DreamzPay, raw(body)) }
  postBody("/unityfinance") { (body: String) => deposit(Provider.UnityFinance, raw(body)) }
  postBody("/unityfinance/withdrawal") { (body: String) => payout(Provider.UnityFinance, raw(body)) }
  postBody("/t365") { (body: String) => deposit(Provider.T365, raw(body)) }
  postBody("/cozeni") { (body: String) => deposit(Provider.Cozeni, raw(body)) }
  postBody("/cozeni/withdrawal") { (body: String) => payout(Provider.Cozeni, raw(body)) }
  postBody("/eupaymentz") { (body: String) => deposit(Provider.EuPaymentz, raw(body)) }
  postBody("/gigadat") { (body: String) => deposit(Provider.Gigadat, raw(body)) }
  postBody("/bouncemoney") { (body: String) => deposit(Provider.BounceMoney, raw(body)) }
  postBody("/digitarium") { (body: String) => deposit(Provider.Digitarium, raw(body)) }
  postBody("/morphosis/deposit/{token}") { (token: String, body: String) =>
    deposit(Provider.Morphosis, raw(body, Map(morphosis.Morphosis.TokenKey -> token)))
  }
  postBody("/morphosis/withdrawal/{token}") { (token: String, body: String) =>
    payout(Provider.Morphosis, raw(body, Map(morphosis.Morphosis.TokenKey -> token)))
  }

object WebhooksEndpoint:
  /** The query parameters a provider's webhook reads (Gigadat's notice carries its reference there). */
  val QueryNames: Vector[String] = Vector("transactionId", "status", "type", "merchantId", "brandId")
