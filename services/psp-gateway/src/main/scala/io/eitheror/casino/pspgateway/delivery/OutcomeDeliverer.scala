package io.eitheror.casino.pspgateway.delivery

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.pspgateway.GatewayRuntime
import io.eitheror.casino.pspgateway.merchant.MerchantClient
import io.eitheror.casino.pspgateway.sessions.*
import org.slf4j.LoggerFactory

import java.time.{Duration, Instant}

/**
 * One delivery of an outcome, attempt by attempt (FR-015): `send` until the receiver answers `2xx`
 * (delivered) or a client error (refused), or the attempts or the budget run out (undelivered). Pure
 * but for `send`, `now` and `sleep`, so the schedule is tested without a receiver or a clock.
 */
object Deliver:
  def apply(send: () => Answer, now: () => Instant, sleep: Duration => Unit): (Delivery, Int) =
    val started = now()
    @annotation.tailrec
    def go(made: Int): (Delivery, Int) =
      val answer = send()
      val n      = made + 1
      Backoff.read(answer.status) match
        case Backoff.Reading.Delivered => (Delivery.Delivered(now(), answer.status.getOrElse(200)), n)
        case Backoff.Reading.Refused   => (Delivery.Refused(now(), answer.status.getOrElse(0), answer.describe), n)
        case Backoff.Reading.Retry =>
          if Backoff.another(n, Duration.between(started, now())) then
            sleep(Backoff.delayBefore(n + 1))
            go(n)
          else (Delivery.Undelivered(now(), n, answer.describe), n)
    go(0)

/** What the deliverer is given; the suites replace the receivers and the sleep. */
object DeliveryRuntime:
  @volatile var receivers: ServiceClients => Receivers =
    services => Receivers.http(services, GatewayRuntime.settings.merchantService)
  @volatile var merchant: ServiceClients => MerchantClient =
    services => MerchantClient.http(services, GatewayRuntime.settings.merchantService)
  @volatile var sleep: Duration => Unit = d => Thread.sleep(d.toMillis)

/**
 * The deposit half of the outcome deliverer (data-model.md): on `Settled` or `Redriven`, read the
 * session; if it may be delivered, sign the body when the credential read now has a `webhookSecret`
 * (research R13), deliver it to `merchant`, and record what came of it. It never throws: an outcome
 * that cannot be delivered is recorded undelivered, and the `Redrive` timer takes it from there.
 */
final class DepositDeliverer(context: ConsumerContext) extends Consumer[DepositEvent, Unit]:
  private val log = LoggerFactory.getLogger(classOf[DepositDeliverer])

  def onMessage(event: DepositEvent): Effect = event match
    case DepositEvent.Settled(_, _, _) | DepositEvent.Redriven(_, _) => deliver(messageContext.subject)
    case _                                                          => effects.ignore()

  private def deliver(paymentId: String): Effect =
    val entity = context.componentClient.forEventSourcedEntity(EntityId(paymentId))
    val state  = entity.call(DepositSession.get).invoke()
    (state.claim, state.outcome) match
      case (Some(claim), Some(outcome)) if state.deliverable =>
        val receiver = claim.receiver(paymentId)
        val body     = Receivers.body(receiver, outcome, state.initiation.flatMap(_.reference))
        val (delivery, attempts) =
          DeliveryRuntime.merchant(context.services).credential(claim.subBrandId, claim.provider) match
            case Left(why) =>
              log.warn(s"deliver $paymentId: the credential could not be read to sign the outcome: $why")
              (Delivery.Undelivered(GatewayRuntime.now(), 0, "credential unreadable"), 0)
            case Right(credential) =>
              val headers = credential.get("webhookSecret").fold(Vector.empty)(Signature.sign(body, _, GatewayRuntime.now()))
              val receivers = DeliveryRuntime.receivers(context.services)
              Deliver(() => receivers.deliver(receiver, outcome, body, headers), GatewayRuntime.now, DeliveryRuntime.sleep)
        log.info(s"deliver $paymentId: ${outcome.word} to ${receiver.describe}: ${delivery.word} after $attempts")
        entity.call(DepositSession.recordDelivery).invoke(RecordDelivery(delivery, attempts, GatewayRuntime.now())): Unit
        effects.done()
      case _ => effects.ignore()

object DepositDeliverer extends Consumer.Companion[DepositDeliverer, DepositEvent, Unit](
      componentId = ComponentId("deposit-deliverer"),
      source = ChangeSource.eventsOf(DepositSession)
    ):
  def create(ctx: ConsumerContext) = new DepositDeliverer(ctx)

/** The payout half: contracts/payouts.md's body to the brand's `payouts`, under the grant it made; unsigned. */
final class PayoutDeliverer(context: ConsumerContext) extends Consumer[PayoutEvent, Unit]:
  private val log = LoggerFactory.getLogger(classOf[PayoutDeliverer])

  def onMessage(event: PayoutEvent): Effect = event match
    case PayoutEvent.Settled(_, _, _, _) | PayoutEvent.Redriven(_, _) => deliver(messageContext.subject)
    case _                                                           => effects.ignore()

  private def deliver(requestId: String): Effect =
    val entity = context.componentClient.forEventSourcedEntity(EntityId(requestId))
    val state  = entity.call(PayoutSession.get).invoke()
    (state.receiver, state.outcome) match
      case (Some(receiver), Some(outcome)) if state.deliverable =>
        val body      = Receivers.body(receiver, outcome, state.providerReference)
        val receivers = DeliveryRuntime.receivers(context.services)
        val (delivery, attempts) =
          Deliver(() => receivers.deliver(receiver, outcome, body, Vector.empty), GatewayRuntime.now, DeliveryRuntime.sleep)
        log.info(s"deliver $requestId: ${outcome.word} to ${receiver.describe}: ${delivery.word} after $attempts")
        entity.call(PayoutSession.recordDelivery).invoke(RecordDelivery(delivery, attempts, GatewayRuntime.now())): Unit
        effects.done()
      case _ => effects.ignore()

object PayoutDeliverer extends Consumer.Companion[PayoutDeliverer, PayoutEvent, Unit](
      componentId = ComponentId("payout-deliverer"),
      source = ChangeSource.eventsOf(PayoutSession)
    ):
  def create(ctx: ConsumerContext) = new PayoutDeliverer(ctx)
