package io.eitheror.casino.attribution.carriers

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.Wiring

/**
 * Each fact appended to a network's feed, to every active webhook of the network (research R15): one
 * `Delivery` per subscription and fact, whose `start` is a no-op when it exists, so a redelivered event
 * starts nothing twice.
 */
final class WebhookFanout(client: ComponentClient, active: String => Vector[String]) extends Consumer[FeedEvent, Unit]:
  def onMessage(event: FeedEvent): Effect = event match
    case FeedEvent.Appended(_, fact) =>
      val network = messageContext.subject
      active(network) match
        case subs if subs.isEmpty => effects.ignore()
        case subs =>
          subs.foreach(sub => client.forWorkflow(Delivery.id(sub, fact.factId)).call(Delivery.start).invoke(DeliveryStart(sub, network, fact)): Unit)
          effects.done()

object WebhookFanout extends Consumer.Companion[WebhookFanout, FeedEvent, Unit](
      componentId = ComponentId("webhook-fanout"),
      source = ChangeSource.eventsOf(NetworkFeed)
    ):
  def create(ctx: ConsumerContext) = new WebhookFanout(ctx.componentClient, network => Wiring.activeSubscriptions.get()(network))
