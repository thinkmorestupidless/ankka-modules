package io.eitheror.casino.attribution.facts

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.{Binding, Wiring}
import io.eitheror.casino.attribution.facts.Facts.ClosedDay
import io.eitheror.casino.attribution.record.{AttributionRecord, Record}
import io.eitheror.casino.contract.facts.AttributionFact

/**
 * The record's facts (data-model.md *Fact publishers*): `Bound` is the registration, the pending money
 * and, for a later code, the days closed before it (research R10); the money events their kinds; `Closed`
 * `player-closed`. Nothing while unbound: an unbound record's events carry no binding.
 */
final class RecordFacts(brand: String, closedDays: String => Vector[ClosedDay]) extends Consumer[Record.Event, AttributionFact]:
  def onMessage(event: Record.Event): Effect =
    val before = event match
      case Record.Bound(b, _, _, _) if b.touch == Binding.LaterCode =>
        Facts.beforeBinding(brand, b, closedDays(messageContext.subject).filter(_.closedAt.isBefore(b.boundAt)))
      case _ => Vector.empty
    Facts.fromRecord(brand, event) ++ before match
      case facts if facts.isEmpty => effects.ignore()
      case facts => effects.produceAll(facts.map(f => effects.message(f).withKey(f.key).withMetadata(Publish.of(f))))

object RecordFacts extends Consumer.Companion[RecordFacts, Record.Event, AttributionFact](
      componentId = ComponentId("record-facts"),
      source = ChangeSource.eventsOf(AttributionRecord)
    ):
  def create(ctx: ConsumerContext) = new RecordFacts(Wiring.brand.get(), player => Wiring.closedDays.get()(player))
  override val outputSerializer: Option[Serializer[AttributionFact]] = Publish.outputSerializer
  override val produceTo: Option[String]                             = Publish.produceTo
  override val produces: Option[Publication]                         = Publish.produces
