package io.eitheror.casino.pspgateway.payouts

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.{GatewayRuntime, Json, Settings}
import io.eitheror.casino.pspgateway.Json.*
import io.eitheror.casino.pspgateway.deposits.Answer
import io.eitheror.casino.pspgateway.merchant.{MerchantClient, Unread}
import io.eitheror.casino.pspgateway.money.Render
import io.eitheror.casino.pspgateway.providers.{Catalogue, Operation, PayoutAnswer, Withdrawal}
import io.eitheror.casino.pspgateway.sessions.*
import org.slf4j.LoggerFactory

/**
 * A brand's payout (FR-004, US3, contracts/payouts.md): the body read, the brand held to the caller's
 * project, the terminal's provider from `merchant`'s brand listing, the credential read now, the session
 * claimed before the provider is called, and the answer recorded; a repeat answers the recorded answer
 * and calls no provider. A refusal before the call is answered and not recorded, so a corrected
 * configuration serves the same request id.
 */
final class Initiate(components: ComponentClient, merchant: MerchantClient, settings: Settings):
  private val log = LoggerFactory.getLogger(classOf[Initiate])

  /** `caller` is the brand the caller is (its project), or none for this machine, which may name any. */
  def apply(text: String, caller: Option[String]): Answer =
    Json.parseObject(text) match
      case None       => Answer.refusal(400, "bad_request", Some("the body is not a JSON object"))
      case Some(body) =>
        Initiate.read(body) match
          case Left(refused) => refused
          case Right(req) if caller.exists(_ != req.brand) =>
            Answer.refusal(403, "forbidden", Some(s"a payout for ${req.brand} may only be made by its payouts"))
          case Right(req) =>
            val entity = components.forEventSourcedEntity(EntityId(req.requestId))
            val state  = entity.call(PayoutSession.get).invoke()
            state.claim match
              case Some(c) if c.brand != req.brand => Answer.refusal(409, "conflict", Some("the request id is another brand's"))
              case Some(_)                         => Initiate.answerFor(state)
              case None                            => fresh(req, body)

  private def fresh(req: Initiate.Request, body: ObjectNode): Answer =
    def rejected(reason: String) = Initiate.body(req.requestId, "rejected", None, Some(reason))
    val terminal = merchant.brandTerminals(req.brand).map(_.find(_.id == req.terminalId))
    terminal match
      case Left(why) =>
        log.warn(s"payout ${req.requestId}: the brand's terminals could not be read: ${Initiate.describe(why)}")
        Answer.refusal(503, "unavailable", Some("merchant could not be read"))
      case Right(None) => rejected("unknown_terminal")
      case Right(Some(t)) =>
        Provider.from(t.gateway).filter(p => GatewayRuntime.payouts.contains(p)) match
          case None => rejected("unsupported_method")
          case Some(provider) =>
            Catalogue.check(provider, Operation.Payout, req.method, req.currency) match
              case Left(u) => rejected(u.error)
              case Right(()) =>
                merchant.credential(req.subBrandId, provider) match
                  case Left(Unread.None) => rejected("missing_credentials")
                  case Left(Unread.Unavailable(why)) =>
                    log.warn(s"payout ${req.requestId}: the credential for ${provider.wire} could not be read: $why")
                    Answer.refusal(503, "unavailable", Some("the credential could not be read"))
                  case Right(credential) =>
                    settings.baseUrl(provider, Operation.Payout, credential) match
                      case Left(_)     => Answer.refusal(502, "provider_not_configured", provider = Some(provider.wire))
                      case Right(base) => claimAndCall(req, body, provider, credential, base)

  private def claimAndCall(req: Initiate.Request, body: ObjectNode, provider: Provider,
      credential: io.eitheror.casino.pspgateway.providers.Credential, base: String): Answer =
    val port      = GatewayRuntime.payouts(provider)
    val generated = port.generate(GatewayRuntime.random)
    val claim = PayoutClaim(req.brand, req.subBrandId, provider, req.terminalId, req.method, req.amount, req.currency,
      settings.public(Catalogue.payoutWebhookPath(provider, generated)), generated)
    val entity = components.forEventSourcedEntity(EntityId(req.requestId))
    val claimed =
      try entity.call(PayoutSession.claim).invoke(ClaimPayout(claim, GatewayRuntime.now()))
      catch case e: CommandError => return Answer.refusal(409, "conflict", Option(e.message))
    if claimed.claim.exists(_ != claim) then Initiate.answerFor(claimed)
    else
      Initiate.indexed(provider, generated).foreach { key =>
        components.forKeyValueEntity(EntityId(ReferenceIndex.key(provider, key))).call(ReferenceIndex.put)
          .invoke(Put(Indexed(Indexed.Payout, req.requestId))): Unit
      }
      val withdrawal = Withdrawal(req.requestId, req.brand, req.amount, req.currency, req.method, req.terminalId,
        claim.webhookUrl, Initiate.destination(body), generated, customerId = body.text("customerId").orElse(body.text("playerId")))
      val answer = port.payout(withdrawal, credential, base, GatewayRuntime.http.caller(provider))
      log.info(s"payout ${req.requestId}: ${provider.wire} answered ${Initiate.status(answer)}")
      Initiate.answerFor(entity.call(PayoutSession.answered).invoke(RecordAnswer(answer, GatewayRuntime.now())))

object Initiate:
  final case class Request(requestId: String, brand: String, subBrandId: String, terminalId: String, amount: Long,
      currency: Currency, method: String)

  /** The method's details (contracts/payouts.md): passed to the provider and never stored (FR-022). */
  val DestinationFields: Vector[String] = Vector("iban", "firstName", "lastName", "email", "mobile", "account",
    "institution", "transit", "coin", "address", "memo", "country", "province", "ip")

  def destination(body: ObjectNode): Map[String, String] =
    DestinationFields.flatMap(f => body.text(f).map(f -> _)).toMap

  def read(body: ObjectNode): Either[Answer, Request] =
    def bad(m: String) = Answer.refusal(400, "bad_request", Some(m))
    def need(f: String) = body.text(f).toRight(bad(s"$f is required"))
    for
      requestId  <- need("requestId")
      brand      <- need("brand")
      subBrandId <- need("subBrandId")
      terminalId <- need("terminalId")
      amount     <- body.text("amount").toRight(bad("amount is required")).flatMap(a => Render.minorUnits(a).left.map(bad))
      currency   <- need("currency").flatMap(c => Currency.from(c.toUpperCase).left.map(bad))
      method     <- need("method")
    yield Request(requestId, brand, subBrandId, terminalId, amount, currency, method.trim.toUpperCase)

  /** The provider's own reference to the payout where it does not echo the request id: Morphosis's token. */
  def indexed(provider: Provider, generated: Map[String, String]): Option[String] = provider match
    case Provider.Morphosis => generated.get(io.eitheror.casino.pspgateway.providers.morphosis.Morphosis.TokenKey)
    case _                  => None

  def status(a: PayoutAnswer): String = a match
    case PayoutAnswer.Initiated(_)     => "initiated"
    case PayoutAnswer.Completed(_)     => "completed"
    case PayoutAnswer.AlreadyInitiated => "already_initiated"
    case PayoutAnswer.Rejected(_)      => "rejected"
    case PayoutAnswer.InDoubt(_)       => "in_doubt"

  def body(requestId: String, status: String, ref: Option[String], reason: Option[String]): Answer =
    Answer(200, Json.obj("requestId" -> requestId, "status" -> status, "providerReference" -> ref, "reason" -> reason))

  /** The recorded answer (FR-008's rule for payouts); a claim not yet answered is in progress. */
  def answerFor(s: PayoutState): Answer = s.answer match
    case None => Answer.refusal(409, "initiation_in_progress")
    case Some(a) =>
      val reason = a match
        case PayoutAnswer.Rejected(r) => Some(r)
        case PayoutAnswer.InDoubt(r)  => Some(r)
        case _                        => None
      body(s.requestId, status(a), s.providerReference, reason)

  def describe(u: Unread): String = u match
    case Unread.None             => "none held"
    case Unread.Unavailable(why) => why
