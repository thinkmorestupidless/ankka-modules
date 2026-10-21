package io.eitheror.casino.pspgateway.deposits

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.pspgateway.GatewayRuntime
import io.eitheror.casino.pspgateway.http.Raw
import io.eitheror.casino.pspgateway.merchant.{MerchantClient, Unread}
import io.eitheror.casino.pspgateway.providers.*
import io.eitheror.casino.pspgateway.sessions.*
import org.slf4j.LoggerFactory

/**
 * A provider's deposit webhook (research R8): the session resolved by the reference or through
 * `ReferenceIndex`, the credential read for it, the provider's verification and cross-check over the
 * raw body, the outcome settled once, and the provider answered as the original answered it. Every
 * refusal is logged with its reason; no body is logged (FR-022).
 */
final class Webhooks(components: ComponentClient, merchant: MerchantClient):
  private val log = LoggerFactory.getLogger(classOf[Webhooks])

  def deposit(provider: Provider, raw: Raw): Reply =
    val port = GatewayRuntime.deposits(provider)
    port.locate(raw) match
      case Left(reply) =>
        log.info(s"webhook ${provider.wire}: no reference could be read")
        reply
      case Right(located) =>
        resolve(provider, located) match
          case None =>
            log.info(s"webhook ${provider.wire}: unknown reference")
            port.unknown(raw)
          case Some(Webhooks.Target.Payout(requestId)) =>
            // Gigadat's one route carries its payouts' notices too (`type` ETO or ACH).
            GatewayRuntime.payouts.get(provider).fold(port.unknown(raw))(settlePayout(provider, _, requestId, raw))
          case Some(Webhooks.Target.Deposit(paymentId)) => settle(provider, port, paymentId, raw)

  private def settle(provider: Provider, port: Deposits, paymentId: String, raw: Raw): Reply =
    val entity = components.forEventSourcedEntity(EntityId(paymentId))
    val state  = entity.call(DepositSession.get).invoke()
    state.claim.filter(_.provider == provider) match
      case None =>
        log.info(s"webhook ${provider.wire} $paymentId: no session of this provider")
        port.unknown(raw)
      case Some(_) if state.stage == Stage.InitiationFailed =>
        log.info(s"webhook ${provider.wire} $paymentId: the initiation failed; it settles nothing (FR-024)")
        port.unknown(raw)
      case Some(claim) =>
        merchant.credential(claim.subBrandId, provider) match
          case Left(why) =>
            log.warn(s"webhook ${provider.wire} $paymentId: the credential could not be read (${Webhooks.describe(why)})")
            Webhooks.credentialUnreadable(provider).getOrElse(port.unknown(raw))
          case Right(credential) =>
            val handled = port.webhook(raw, Webhooks.charge(state, claim), credential, GatewayRuntime.now())
            handled.verdict match
              case Verdict.Settle(outcome, status) =>
                val s = entity.call(DepositSession.settle).invoke(Settle(outcome, status, GatewayRuntime.now()))
                if s.settled then log.info(s"webhook ${provider.wire} $paymentId: settled ${outcome.word} ($status)")
                else log.info(s"webhook ${provider.wire} $paymentId: already settled; acknowledged")
              case Verdict.Open(status)   => log.info(s"webhook ${provider.wire} $paymentId: open ($status)")
              case Verdict.Ignore(reason) => log.info(s"webhook ${provider.wire} $paymentId: ignored: $reason")
              case Verdict.Reject(reason) => log.warn(s"webhook ${provider.wire} $paymentId: refused: $reason")
            handled.reply

  /** A provider's withdrawal webhook (Unity Finance, Cozeni, Morphosis): the payout half of R8. */
  def payout(provider: Provider, raw: Raw): Reply =
    val port = GatewayRuntime.payouts(provider)
    port.locatePayout(raw) match
      case Left(reply) =>
        log.info(s"payout webhook ${provider.wire}: no reference could be read")
        reply
      case Right(located) =>
        resolve(provider, located) match
          case Some(Webhooks.Target.Payout(requestId)) => settlePayout(provider, port, requestId, raw)
          case _ =>
            log.info(s"payout webhook ${provider.wire}: unknown reference")
            port.unknownPayout(raw)

  private def settlePayout(provider: Provider, port: Payouts, requestId: String, raw: Raw): Reply =
    val entity = components.forEventSourcedEntity(EntityId(requestId))
    val state  = entity.call(PayoutSession.get).invoke()
    state.claim.filter(_.provider == provider) match
      case None =>
        log.info(s"payout webhook ${provider.wire} $requestId: no payout of this provider")
        port.unknownPayout(raw)
      case Some(claim) =>
        merchant.credential(claim.subBrandId, provider) match
          case Left(why) =>
            log.warn(s"payout webhook ${provider.wire} $requestId: the credential could not be read (${Webhooks.describe(why)})")
            port.unknownPayout(raw)
          case Right(credential) =>
            val handled = port.payoutWebhook(raw, Webhooks.withdrawal(state, claim), credential, GatewayRuntime.now())
            handled.verdict match
              case Verdict.Settle(outcome, status) =>
                val s = entity.call(PayoutSession.settle).invoke(SettlePayout(outcome, state.providerReference, status, GatewayRuntime.now()))
                if s.settled then log.info(s"payout webhook ${provider.wire} $requestId: settled ${outcome.word} ($status)")
                else log.info(s"payout webhook ${provider.wire} $requestId: not admitted after ${state.outcome.map(_.word).getOrElse("none")}")
              case Verdict.Open(status)   => log.info(s"payout webhook ${provider.wire} $requestId: open ($status)")
              case Verdict.Ignore(reason) => log.info(s"payout webhook ${provider.wire} $requestId: ignored: $reason")
              case Verdict.Reject(reason) => log.warn(s"payout webhook ${provider.wire} $requestId: refused: $reason")
            handled.reply

  private def resolve(provider: Provider, located: Located): Option[Webhooks.Target] = located match
    case Located.Deposit(ref) => Some(Webhooks.Target.Deposit(ref))
    case Located.Payout(ref)  => Some(Webhooks.Target.Payout(ref))
    case Located.Indexed(key) =>
      components.forKeyValueEntity(EntityId(ReferenceIndex.key(provider, key))).call(ReferenceIndex.get).invoke().map {
        case Indexed(Indexed.Payout, k) => Webhooks.Target.Payout(k)
        case Indexed(_, k)              => Webhooks.Target.Deposit(k)
      }

object Webhooks:
  enum Target:
    case Deposit(paymentId: String)
    case Payout(requestId: String)

  def describe(u: Unread): String = u match
    case Unread.None             => "none held"
    case Unread.Unavailable(why) => why

  /** Where the provider redelivers on a non-2xx, a credential that cannot be read is `502` (R8 step 2). */
  def credentialUnreadable(provider: Provider): Option[Reply] = provider match
    case Provider.BounceMoney => Some(bouncemoney.BounceMoney.credentialUnreadable)
    case Provider.Digitarium  => Some(digitarium.Digitarium.credentialUnreadable)
    case _                    => None

  /** A payout as the provider's package reads it; its destination was never kept. */
  def withdrawal(s: PayoutState, c: PayoutClaim): Withdrawal =
    Withdrawal(s.requestId, c.brand, c.amount, c.currency, c.method, c.terminalId, c.webhookUrl, Map.empty, c.generated,
      s.providerReference)

  /** The session as the provider's package reads it. */
  def charge(s: DepositState, c: DepositClaim): Charge =
    Charge(s.paymentId, c.amount, c.currency, c.method, c.terminalId, c.returnUrl, c.webhookUrl, c.generated,
      s.initiation.flatMap(_.reference), s.initiation.map(_.providerData).getOrElse(Map.empty),
      Some(c.customerId).filter(_.nonEmpty))
