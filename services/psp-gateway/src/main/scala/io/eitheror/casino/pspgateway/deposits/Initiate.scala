package io.eitheror.casino.pspgateway.deposits

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.{GatewayRuntime, Json, Settings}
import io.eitheror.casino.pspgateway.Json.*
import io.eitheror.casino.pspgateway.merchant.{MerchantClient, Unread}
import io.eitheror.casino.pspgateway.money.Render
import io.eitheror.casino.pspgateway.providers.{Catalogue, Charge, Credential, Customer, Operation, Initiation as Initiated}
import io.eitheror.casino.pspgateway.sessions.*
import org.slf4j.LoggerFactory

/** The initiate's answer: its status and body, as contracts/merchant.md §1 gives them. */
final case class Answer(status: Int, body: ObjectNode)

object Answer:
  def refusal(status: Int, error: String, message: Option[String] = None, provider: Option[String] = None): Answer =
    Answer(status, Json.obj("error" -> error, "message" -> message, "provider" -> provider))

  def of(r: Refusal): Answer = refusal(r.status, r.error, r.message, r.provider)

/**
 * A hosted-page deposit (FR-001 to FR-008, US1): the 004 `gateway.md` body read and checked, the
 * credential read from `merchant` at this moment, the session claimed before the provider is called,
 * the provider's call, and its answer recorded. A repeat under the same payment id answers what the
 * first was answered, refusal included, and calls no provider; `terminalProperties` is never read.
 */
final class Initiate(components: ComponentClient, merchant: MerchantClient, settings: Settings):
  private val log = LoggerFactory.getLogger(classOf[Initiate])

  def apply(text: String): Answer =
    Json.parseObject(text) match
      case None       => Answer.refusal(400, "bad_request", Some("the body is not a JSON object"))
      case Some(body) =>
        body.text("paymentId") match
          case None            => Answer.refusal(400, "bad_request", Some("paymentId is required"))
          case Some(paymentId) =>
            val existing = session(paymentId)
            if existing.exists then Initiate.answerFor(existing)
            else fresh(paymentId, body)

  private def session(paymentId: String): DepositState =
    components.forEventSourcedEntity(EntityId(paymentId)).call(DepositSession.get).invoke()

  private final case class Request(provider: Provider, amount: Long, currency: Currency, method: String, subBrandId: String,
      customerId: String, terminalId: Option[String])

  private def read(body: ObjectNode): Either[Answer, Request] =
    def bad(m: String) = Answer.refusal(400, "bad_request", Some(m))
    for
      provider   <- body.text("psp").flatMap(Provider.from).toRight(bad(s"unknown provider '${body.textOr("psp", "")}'"))
      amount     <- body.text("amount").toRight(bad("amount is required")).flatMap(a => Render.minorUnits(a).left.map(bad))
      currency   <- body.text("currency").toRight(bad("currency is required")).flatMap(c => Currency.from(c.toUpperCase).left.map(bad))
      method     <- body.text("method").toRight(bad("method is required"))
      subBrandId <- body.text("subBrandId").toRight(bad("subBrandId is required"))
    yield Request(provider, amount, currency, method.trim.toUpperCase, subBrandId, body.textOr("customerId", ""), body.text("terminalId"))

  private def fresh(paymentId: String, body: ObjectNode): Answer =
    val checked =
      for
        r <- read(body)
        _ <- Catalogue.check(r.provider, Operation.Deposit, r.method, r.currency)
               .left.map(u => Answer.refusal(400, u.error, Some(u.message)))
        _ <- Catalogue.belowMinimum(r.provider, r.amount).map(m => Answer.refusal(400, "player_error", Some(m))).toLeft(())
        credential <- merchant.credential(r.subBrandId, r.provider).left.map {
                        case Unread.None => Initiate.noCredentials(r.provider, r.subBrandId)
                        case Unread.Unavailable(why) =>
                          log.warn(s"initiate $paymentId: the credential for ${r.provider.wire} could not be read: $why")
                          Answer.refusal(502, "provider_unavailable", provider = Some(r.provider.wire))
                      }
        _ <- Some(Catalogue.lacking(r.provider, r.method, credential)).filter(_.nonEmpty)
               .map { keys =>
                 log.warn(s"initiate $paymentId: the ${r.provider.wire} credential lacks ${keys.mkString(", ")}")
                 Initiate.noCredentials(r.provider, r.subBrandId)
               }.toLeft(())
        base <- settings.baseUrl(r.provider, Operation.Deposit, credential)
                  .left.map(_ => Answer.refusal(502, "provider_not_configured", provider = Some(r.provider.wire)))
      yield (r, credential, base)
    checked match
      case Left(refused)                => refused
      case Right((r, credential, base)) => claimAndCall(paymentId, r, credential, base, Customer.from(body))

  private def claimAndCall(paymentId: String, r: Request, credential: Credential, base: String, customer: Customer): Answer =
    val provider  = GatewayRuntime.deposits(r.provider)
    val generated = provider.generate(GatewayRuntime.random)
    val claim = DepositClaim(r.provider, r.method, r.subBrandId, r.terminalId, r.customerId, r.amount, r.currency,
      settings.public(Catalogue.returnPath(r.provider, paymentId)), settings.public(Catalogue.webhookPath(r.provider, generated)), generated)
    val entity  = components.forEventSourcedEntity(EntityId(paymentId))
    val claimed = entity.call(DepositSession.claim).invoke(Claim(claim, GatewayRuntime.now()))
    // Another initiate claimed it first: answer as a repeat does.
    if claimed.claim.exists(_ != claim) then Initiate.answerFor(claimed)
    else
      Initiate.indexed(r.provider, paymentId, generated).foreach { key =>
        components.forKeyValueEntity(EntityId(ReferenceIndex.key(r.provider, key))).call(ReferenceIndex.put)
          .invoke(Put(Indexed(Indexed.Deposit, paymentId))): Unit
      }
      val charge = Charge(paymentId, r.amount, r.currency, r.method, r.terminalId, claim.returnUrl, claim.webhookUrl, generated,
        customerId = Some(r.customerId).filter(_.nonEmpty))
      val at = () => GatewayRuntime.now()
      provider.initiate(charge, credential, customer, base, GatewayRuntime.http.caller(r.provider)) match
        case Initiated.Redirected(url, ref, data, relay) =>
          val stored = data ++ relay.map(f => Initiate.RelayForm -> f.encode)
          Initiate.answerFor(entity.call(DepositSession.initiated).invoke(RecordInitiation(Initiation(ref, Destination.Redirect(url), stored, at()))))
        case Initiated.Quoted(destination, ref, data) =>
          Initiate.answerFor(entity.call(DepositSession.initiated).invoke(RecordInitiation(Initiation(ref, destination, data, at()))))
        case Initiated.Refused(error, message) =>
          log.info(s"initiate $paymentId: ${r.provider.wire} refused: $error")
          val refusal =
            if error == "missing_credentials" then Refusal(404, "no_credentials", Some(s"${r.provider.wire} for ${r.subBrandId}"))
            else Refusal(400, error, Some(message))
          Initiate.answerFor(entity.call(DepositSession.initiationFailed).invoke(FailInitiation(refusal, at())))
        case Initiated.Unavailable(reason) =>
          log.warn(s"initiate $paymentId: ${r.provider.wire} unavailable: $reason")
          val refusal = Refusal(502, "provider_unavailable", provider = Some(r.provider.wire))
          Initiate.answerFor(entity.call(DepositSession.initiationFailed).invoke(FailInitiation(refusal, at())))

object Initiate:
  /** Where a relay page's form is kept in `providerData`: the form the gateway's page POSTs for the player. */
  val RelayForm = "relayForm"

  def noCredentials(provider: Provider, subBrandId: String): Answer =
    Answer.refusal(404, "no_credentials", Some(s"${provider.wire} for $subBrandId"))

  /** The provider's own reference to the session, where it does not echo ours (research R4). */
  def indexed(provider: Provider, paymentId: String, generated: Map[String, String]): Option[String] = provider match
    case Provider.T365      => Some(io.eitheror.casino.pspgateway.providers.t365.T365.orderId(paymentId))
    case Provider.Morphosis => generated.get(io.eitheror.casino.pspgateway.providers.morphosis.Morphosis.TokenKey)
    case _                  => None

  /** `pspTransactionId`: the payment id, dashless for T365 as the original sent it. */
  def pspTransactionId(provider: Provider, paymentId: String): String =
    if provider == Provider.T365 then io.eitheror.casino.pspgateway.providers.t365.T365.orderId(paymentId) else paymentId

  /** What a session answers an initiate with, first time or repeat (FR-008). */
  def answerFor(s: DepositState): Answer =
    (s.stage, s.claim) match
      case (Stage.InitiationFailed, _) => s.refusal.fold(Answer.refusal(502, "provider_unavailable"))(Answer.of)
      case (Stage.Claimed, _)          => Answer.refusal(409, "initiation_in_progress")
      case (_, Some(c)) if s.initiation.isDefined =>
        val i  = s.initiation.get
        val id = pspTransactionId(c.provider, s.paymentId)
        i.destination match
          case Destination.Redirect(url) => Answer(200, Json.obj("redirectUrl" -> url, "pspTransactionId" -> id))
          case Destination.Crypto(address, amount, coin, until) =>
            Answer(200, Json.obj("redirectUrl" -> "", "pspTransactionId" -> id, "paymentAddress" -> address,
              "cryptoAmount" -> amount, "cryptoCurrency" -> coin, "validUntil" -> until))
      // Settled straight from claimed: a webhook arrived before the initiation was recorded.
      case _ => Answer.refusal(409, "initiation_in_progress")
