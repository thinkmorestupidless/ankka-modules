package io.eitheror.casino.attribution.facts

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.Wiring
import io.eitheror.casino.attribution.money.{DayTally, Tally}
import io.eitheror.casino.attribution.networks.Network
import io.eitheror.casino.attribution.record.AttributionRecord
import io.eitheror.casino.contract.facts.AttributionFact

/**
 * The tallies' facts: a closed day with movement is `ggr-daily`, a late leg `ggr-adjustment`, and a
 * settling leg `ggr-round` when the player's network takes the round stream (FR-016). The binding is read
 * from the player's record; nothing for an unbound player. An event that can make no fact reads nothing.
 */
final class TallyFacts(brand: String, client: ComponentClient) extends Consumer[Tally.Event, AttributionFact]:
  def onMessage(event: Tally.Event): Effect =
    val mayEmit = event match
      case c: Tally.Closed      => c.moved
      case _: Tally.Adjusted    => true
      case l: Tally.LegRecorded => l.round.exists(r => r.stake != 0 || r.win != 0)
    if !mayEmit then effects.ignore()
    else
      client.forEventSourcedEntity(EntityId(event.player)).call(AttributionRecord.get).invoke().binding match
        case None => effects.ignore()
        case Some(b) =>
          val stream = event match
            case _: Tally.LegRecorded => client.forKeyValueEntity(EntityId(b.network)).call(Network.get).invoke().roundStream
            case _                    => false
          Facts.fromTally(brand, b, event, stream) match
            case facts if facts.isEmpty => effects.ignore()
            case facts => effects.produceAll(facts.map(f => effects.message(f).withKey(f.key).withMetadata(Publish.of(f))))

object TallyFacts extends Consumer.Companion[TallyFacts, Tally.Event, AttributionFact](
      componentId = ComponentId("tally-facts"),
      source = ChangeSource.eventsOf(DayTally)
    ):
  def create(ctx: ConsumerContext) = new TallyFacts(Wiring.brand.get(), ctx.componentClient)
  override val outputSerializer: Option[Serializer[AttributionFact]] = Publish.outputSerializer
  override val produceTo: Option[String]                             = Publish.produceTo
  override val produces: Option[Publication]                         = Publish.produces
