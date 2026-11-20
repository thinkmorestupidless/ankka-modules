package io.eitheror.casino.pspgateway.deposits

import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.contract.money.{Amounts, Currency}
import io.eitheror.casino.pspgateway.{GatewayRuntime, Json, Settings}
import io.eitheror.casino.pspgateway.Json.*
import io.eitheror.casino.pspgateway.merchant.{MerchantClient, Unread}
import io.eitheror.casino.pspgateway.providers.{Call, Catalogue, Charge, Credential, Customer, Initiation, Operation}
import io.eitheror.casino.pspgateway.sessions.Provider
import org.slf4j.LoggerFactory

import java.util.UUID

/**
 * A provider's own test mode, as its adapter has one (spec 030 T2): the method and currency a test
 * charge is made in, and what the credential is made to say so the charge cannot leave the test mode.
 */
final case class TestMode(name: String, method: String, currency: Currency, inTestMode: Credential => Credential)

object TestMode:
  private def sandboxed(c: Credential): Credential = Credential(c.values + ("environment" -> "sandbox"))

  /**
   * Gigadat's `sandbox` flag and Digitarium's sandbox host, each chosen by the credential's
   * `environment`, set to `sandbox` whatever the credential says: the request itself keeps the charge
   * out of production. No other adapter has a test mode a request can force, so the other seven answer
   * `test_mode_unavailable`:
   *  - Unity Finance has one address for test and live (the original's spec 109 research, *Endpoints*);
   *    test mode is a setting of each checkout in its portal, which the gateway cannot see. `CARD_TEST`
   *    is only the generic `card` payway (`adapter.go:255`) on the checkout `checkoutId.CARD_TEST`
   *    names; on a live checkout its page takes a real card, and the charge would be one nothing records.
   *  - BounceMoney has no sandbox, and Cozeni's and the rest's are an installation's base URL, not a request's.
   */
  def of(provider: Provider): Option[TestMode] = provider match
    case Provider.Gigadat      => Some(TestMode("sandbox", "INTERAC", Currency.unsafe("CAD"), sandboxed))
    case Provider.Digitarium   => Some(TestMode("sandbox", "BASIC_CARD", Currency.unsafe("USD"), sandboxed))
    case _                     => None

  /** Why `provider` has no test mode, for the refusal. */
  def unavailable(provider: Provider): String = provider match
    case Provider.UnityFinance =>
      "unityfinance's test mode is a setting of the checkout in its portal, which no request can force; a test charge could take live money"
    case p => s"${p.wire} offers no test mode"

  /** The smallest test charge: one whole unit of the currency, or the catalogue's minimum where that is more. */
  def amount(provider: Provider, currency: Currency): Long =
    val unit = math.pow(10, Amounts.exponent(currency)).toLong
    Catalogue(provider).minimum.fold(unit)((min, _) => math.max(unit, min))

/**
 * The test payment (spec 030 T2, merchant's H4): one charge of the provider's smallest amount in the
 * provider's test mode, with the credential `merchant` names, read from `merchant` at this moment, to
 * see what the provider answers. Nothing is recorded: no deposit session is claimed (this class is
 * given no component client), no reference is indexed and no outcome is delivered, so a webhook the
 * provider sends for it finds no session, is answered as an unknown one, and moves nothing.
 */
final class TestPayment(merchant: MerchantClient, settings: Settings, call: Provider => Call = GatewayRuntime.http.caller):
  private val log = LoggerFactory.getLogger(classOf[TestPayment])

  def apply(text: String): Answer =
    Json.parseObject(text) match
      case None       => Answer.refusal(400, "bad_request", Some("the body is not a JSON object"))
      case Some(body) => read(body).fold(identity, (provider, subBrandId, name, currency) => made(provider, subBrandId, name, currency))

  private def read(body: ObjectNode): Either[Answer, (Provider, String, String, Option[Currency])] =
    def bad(m: String) = Answer.refusal(400, "bad_request", Some(m))
    for
      provider   <- body.text("psp").flatMap(Provider.from).toRight(bad(s"unknown provider '${body.textOr("psp", "")}'"))
      subBrandId <- body.text("subBrandId").toRight(bad("subBrandId is required"))
      name       <- body.text("credentialName").toRight(bad("credentialName is required"))
      currency   <- body.text("currency").fold(Right(None))(c => Currency.from(c.trim.toUpperCase).map(Some(_)).left.map(bad))
    yield (provider, subBrandId, name, currency)

  private def made(provider: Provider, subBrandId: String, name: String, asked: Option[Currency]): Answer =
    TestMode.of(provider) match
      case None =>
        Answer.refusal(501, "test_mode_unavailable", Some(TestMode.unavailable(provider)), Some(provider.wire))
      case Some(mode) =>
        val currency = asked.getOrElse(mode.currency)
        val amount   = TestMode.amount(provider, currency)
        val checked =
          for
            _ <- Catalogue.check(provider, Operation.Deposit, mode.method, currency).left.map(u => Answer.refusal(400, u.error, Some(u.message)))
            read <- merchant.namedCredential(subBrandId, provider, name).left.map {
                      case Unread.None => Answer.refusal(404, "no_credentials", Some(s"${provider.wire} $name for $subBrandId"), Some(provider.wire))
                      case Unread.Unavailable(why) =>
                        log.warn(s"test payment: the ${provider.wire} credential could not be read: $why")
                        Answer.refusal(502, "provider_unavailable", provider = Some(provider.wire))
                    }
            credential = mode.inTestMode(read)
            _ <- Some(Catalogue.lacking(provider, mode.method, credential)).filter(_.nonEmpty)
                   .map(keys => Answer.refusal(404, "no_credentials", Some(s"the credential lacks ${keys.mkString(", ")}"), Some(provider.wire)))
                   .toLeft(())
            base <- settings.baseUrl(provider, Operation.Deposit, credential)
                      .left.map(_ => Answer.refusal(502, "provider_not_configured", provider = Some(provider.wire)))
          yield (credential, base)
        checked.fold(identity, (credential, base) => charge(provider, mode, currency, amount, credential, base))

  private def charge(provider: Provider, mode: TestMode, currency: Currency, amount: Long, credential: Credential, base: String): Answer =
    val reference = UUID.randomUUID().toString
    val generated = GatewayRuntime.deposits(provider).generate(GatewayRuntime.random)
    val charge = Charge(reference, amount, currency, mode.method, None, settings.public(Catalogue.returnPath(provider, reference)),
      settings.public(Catalogue.webhookPath(provider, generated)), generated)
    def answered(outcome: String, fields: (String, Any)*): Answer =
      Answer(200, Json.obj((Vector[(String, Any)]("outcome" -> outcome, "provider" -> provider.wire, "testMode" -> mode.name,
        "method" -> mode.method, "currency" -> currency.code, "amount" -> amount, "reference" -> reference) ++ fields)*))
    val outcome = GatewayRuntime.deposits(provider).initiate(charge, credential, Customer(), base, call(provider))
    log.info(s"test payment $reference: ${provider.wire} ${outcome.getClass.getSimpleName.toLowerCase}")
    outcome match
      case Initiation.Redirected(url, ref, _, _)  => answered("accepted", "redirectUrl" -> url, "providerReference" -> ref)
      case Initiation.Quoted(destination, ref, _) => answered("accepted", "paymentAddress" -> destination.address, "providerReference" -> ref)
      case Initiation.Refused(error, message)     => answered("refused", "error" -> error, "message" -> message)
      case Initiation.Unavailable(reason)         => answered("unavailable", "reason" -> reason)
