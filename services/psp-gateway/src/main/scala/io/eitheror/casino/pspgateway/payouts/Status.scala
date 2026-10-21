package io.eitheror.casino.pspgateway.payouts

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.pspgateway.{GatewayRuntime, Json, Settings}
import io.eitheror.casino.pspgateway.deposits.Answer
import io.eitheror.casino.pspgateway.merchant.MerchantClient
import io.eitheror.casino.pspgateway.providers.{Catalogue, Operation, PayoutStatus, Withdrawal}
import io.eitheror.casino.pspgateway.sessions.*
import org.slf4j.LoggerFactory

/**
 * A payout's status (FR-009, contracts/payouts.md): a settled session answers its outcome; Unity Finance
 * is asked, and a final state it reports settles the session (so `payouts` is told by the deliverer);
 * the rest answer `pending` until a webhook. Never `no_record` for a credential that cannot be read.
 */
final class Status(components: ComponentClient, merchant: MerchantClient, settings: Settings):
  private val log = LoggerFactory.getLogger(classOf[Status])

  def apply(requestId: String, caller: Option[String]): Answer =
    val entity = components.forEventSourcedEntity(EntityId(requestId))
    val s      = entity.call(PayoutSession.get).invoke()
    s.claim match
      case None                                     => Answer.refusal(404, "not_found")
      case Some(c) if caller.exists(_ != c.brand)   => Answer.refusal(404, "not_found")
      case Some(_) if s.outcome.isDefined           => Status.of(s)
      case Some(c) if Catalogue(c.provider).hasStatus => ask(s, c)
      case Some(_)                                  => Status.of(s)

  private def unavailable(why: String) =
    log.warn(s"payout status: $why")
    Answer.refusal(502, "status_unavailable")

  private def ask(s: PayoutState, c: PayoutClaim): Answer =
    val port = GatewayRuntime.payouts(c.provider)
    merchant.credential(c.subBrandId, c.provider) match
      case Left(why) => unavailable(s"${s.requestId}: the credential could not be read (${Initiate.describe(why)})")
      case Right(credential) =>
        settings.baseUrl(c.provider, Operation.Payout, credential) match
          case Left(_) => unavailable(s"${s.requestId}: ${c.provider.wire} has no base URL")
          case Right(base) =>
            val w = Withdrawal(s.requestId, c.brand, c.amount, c.currency, c.method, c.terminalId, c.webhookUrl, Map.empty,
              c.generated, s.providerReference)
            port.status(w, credential, base, GatewayRuntime.http.caller(c.provider)) match
              case None | Some(PayoutStatus.Unavailable(_)) => unavailable(s"${s.requestId}: ${c.provider.wire} could not be read")
              case Some(PayoutStatus.NoRecord)               => Status.answer(s.requestId, "no_record", s.providerReference, None)
              case Some(PayoutStatus.Pending(_))             => Status.answer(s.requestId, "pending", s.providerReference, None)
              case Some(PayoutStatus.Final(outcome, providerStatus)) =>
                val settled = components.forEventSourcedEntity(EntityId(s.requestId)).call(PayoutSession.settle)
                  .invoke(SettlePayout(outcome, s.providerReference, providerStatus, GatewayRuntime.now()))
                Status.of(settled.state)

object Status:
  def answer(requestId: String, status: String, ref: Option[String], reason: Option[String]): Answer =
    Answer(200, Json.obj("requestId" -> requestId, "status" -> status, "providerReference" -> ref, "reason" -> reason))

  def of(s: PayoutState): Answer = s.outcome match
    case None => answer(s.requestId, "pending", s.providerReference, None)
    case Some(o) =>
      val reason = o match
        case Outcome.PayoutFailed(_, r) => Some(r)
        case Outcome.Failed(_, r, _)    => Some(r)
        case _                          => None
      answer(s.requestId, o.word, s.providerReference, reason)
