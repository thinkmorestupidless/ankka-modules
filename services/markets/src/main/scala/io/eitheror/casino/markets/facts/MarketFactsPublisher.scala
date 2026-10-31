package io.eitheror.casino.markets.facts

import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.predictions.markets.{Definition, Evidence, MarketEvent}
import com.thinkmorestupidless.predictions.markets.components.MarketEntityCompanion
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.Currency

/** Every change to a market on `markets.events`, keyed by the market (spec 023 FR-006). */
final class MarketFactsPublisher extends Consumer[MarketEvent, MarketsEventsFact]:
  def onMessage(event: MarketEvent): Effect =
    val fact = MarketFactsPublisher.fact(messageContext.subject, event)
    effects.produceAll(Seq(effects.message(fact).withKey(fact.key)
      .withMetadata(Metadata.empty.set("ce-id", s"market/${messageContext.subject}/${messageContext.sequenceNumber}"))))

object MarketFactsPublisher
    extends Consumer.Companion[MarketFactsPublisher, MarketEvent, MarketsEventsFact](ComponentId("market-facts"), MarketEntityCompanion.events):
  override val outputSerializer: Option[Serializer[MarketsEventsFact]] = Some(MarketsEvents.serializer)
  override val produceTo: Option[String]                               = Some(MarketsEvents.name)
  override val produces: Option[Publication] = Some(Publication(MarketsEvents.name, Some(MarketsEvents.contract), None))
  def create(ctx: ConsumerContext) = new MarketFactsPublisher

  private def evidence(e: Vector[Evidence]) = e.map(x => EvidenceItem(x.source, x.locator, x.excerpt))

  // The library has validated the currency as three capital letters; the brand's list is checked at the route.
  private def currency(d: Definition) = Currency.unsafe(d.currency)

  /** The fact for an event of the market `m`. */
  def fact(m: String, event: MarketEvent): MarketsEventsFact = event match
    case MarketEvent.Drafted(_, op, d, at) =>
      MarketDrafted(m, op.value, d.question, d.description, d.category, d.outcomes.map(_.value), currency(d), d.openTime, d.close,
        d.expectedResolution.toString, d.criteria, d.authority, at)
    case MarketEvent.Changed(op, d, at) =>
      MarketChanged(m, op.value, d.question, d.description, d.category, d.outcomes.map(_.value), currency(d), d.openTime, d.close,
        d.expectedResolution.toString, d.criteria, d.authority, at)
    case MarketEvent.Approved(op, at)          => MarketApproved(m, op.value, at)
    case MarketEvent.Opened(at)                => MarketOpened(m, at)
    case MarketEvent.Suspended(op, reason, at) => MarketSuspended(m, op.value, reason, at)
    case MarketEvent.Resumed(op, at)           => MarketResumed(m, op.value, at)
    case MarketEvent.Closed(op, reason, at)    => MarketClosed(m, op.map(_.value), reason, at)
    case MarketEvent.CloseMoved(op, close, expected, reason, at) =>
      MarketCloseMoved(m, op.value, close, expected.toString, reason, at)
    case MarketEvent.Resolved(op, outcome, ev, authority, windowEnd, at) =>
      MarketResolved(m, op.value, outcome.value, evidence(ev), authority, windowEnd, at)
    case MarketEvent.Corrected(op, outcome, ev, windowEnd, at) => MarketCorrected(m, op.value, outcome.value, evidence(ev), windowEnd, at)
    case MarketEvent.Voided(op, reason, ev, windowEnd, at)     => MarketVoided(m, op.value, reason, evidence(ev), windowEnd, at)
    case MarketEvent.Final(at)                                 => MarketFinal(m, at)
