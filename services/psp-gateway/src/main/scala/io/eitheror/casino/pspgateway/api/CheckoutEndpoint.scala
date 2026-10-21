package io.eitheror.casino.pspgateway.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.pspgateway.deposits.Initiate
import io.eitheror.casino.pspgateway.http.{Form, Pages}
import io.eitheror.casino.pspgateway.sessions.{DepositSession, Provider}
import org.slf4j.LoggerFactory

import scala.util.Try

/**
 * `/v1/psp/checkout`: the player's browser (US6). The relay pages POST the session's form to the
 * provider; every return route answers the neutral processing page, an unknown reference too, and
 * settles nothing. A return logs the names of what it was sent, never the values: the outcome is the
 * webhook's, and a return's query or form can carry the player's details (FR-022).
 */
final class CheckoutEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/psp/checkout"):
  private val log = LoggerFactory.getLogger(classOf[CheckoutEndpoint])
  val acl: Acl = Routes.public

  get("/dreamzpay/{reference}") { (reference: String) => relay(Provider.DreamzPay, reference) }
  get("/eupaymentz/{reference}") { (reference: String) => relay(Provider.EuPaymentz, reference) }

  // Unity Finance's brand-neutral return and its legacy one (`main.go:170-172`).
  get("/{reference}/return") { (reference: String) => returned(Provider.UnityFinance, reference) }
  get("/unityfinance/{reference}/return") { (reference: String) => returned(Provider.UnityFinance, reference) }

  get("/dreamzpay/{reference}/return") { (reference: String) => returned(Provider.DreamzPay, reference) }
  // The browser's POST back: its form is logged and ignored (spec correction 8).
  postBody("/dreamzpay/{reference}/return") { (reference: String, body: String) =>
    returned(Provider.DreamzPay, reference, Form.decode(body).keys)
  }
  get("/t365/{reference}/return") { (reference: String) => returned(Provider.T365, reference) }
  get("/eupaymentz/{reference}/return") { (reference: String) => returned(Provider.EuPaymentz, reference) }
  get("/gigadat/{reference}/return") { (reference: String) => returned(Provider.Gigadat, reference) }
  get("/bouncemoney/{reference}/return") { (reference: String) => returned(Provider.BounceMoney, reference) }
  get("/digitarium/{reference}/return") { (reference: String) => returned(Provider.Digitarium, reference) }

  private def returned(provider: Provider, reference: String, form: Vector[String] = Vector.empty): Html =
    val query = request.query.toSeq.map(_._1).distinct
    log.info(s"return ${provider.wire} $reference: query [${query.mkString(",")}] form [${form.mkString(",")}]: settles nothing")
    Pages.processing

  /**
   * The relay page for the session's stored form, when the session is this provider's and holds one; the
   * processing page otherwise, an unknown reference included (the original answers `404`; a player's
   * browser is shown the neutral page rather than an error, as every return route does).
   */
  private def relay(provider: Provider, reference: String): Html =
    val page = for
      state  <- Try(ctx.components.forEventSourcedEntity(EntityId(reference)).call(DepositSession.get).invoke()).toOption
      claim  <- state.claim if claim.provider == provider
      i      <- state.initiation
      action <- i.providerData.get("relayAction")
      form   <- i.providerData.get(Initiate.RelayForm)
    yield Pages.relay(action, Form.decode(form))
    if page.isEmpty then log.info(s"relay ${provider.wire} $reference: no relay form for it; the processing page")
    page.getOrElse(Pages.processing)
