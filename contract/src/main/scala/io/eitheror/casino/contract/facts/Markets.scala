package io.eitheror.casino.contract.facts

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.named
import io.eitheror.casino.contract.money.Currency

import java.time.Instant

/**
 * A fact about a market rather than a player (spec 023): keyed by the market, as ankka-predictions
 * specs 002 and 003 key `markets.events` and the exchange's topics, so a reader folds a market's
 * facts in order. A family that concerns a player names them in `player_id`, or in its legs.
 */
trait MarketFact extends Fact:
  def marketId: String
  def key: String = marketId

/** One piece of a resolution's or a void's evidence: a source, a locator and an excerpt. */
final case class EvidenceItem(source: String, locator: String, excerpt: String)

object EvidenceItem:
  given FieldType[EvidenceItem] = FieldType.nested[EvidenceItem]

/**
 * `markets.events`, produced by `markets` (spec 023 FR-006): every change to a market, keyed by
 * the market. The operator is staff, not a player; `expected_resolution` is an ISO date.
 */
sealed trait MarketsEventsFact extends MarketFact:
  def occurredAt: Instant

@named("market_drafted")
final case class MarketDrafted(marketId: String, operatorId: String, question: String, description: String, category: String,
    outcomes: Vector[String], currency: Currency, openTime: Option[Instant], close: Instant, expectedResolution: String,
    criteria: String, authority: String, occurredAt: Instant) extends MarketsEventsFact

@named("market_changed")
final case class MarketChanged(marketId: String, operatorId: String, question: String, description: String, category: String,
    outcomes: Vector[String], currency: Currency, openTime: Option[Instant], close: Instant, expectedResolution: String,
    criteria: String, authority: String, occurredAt: Instant) extends MarketsEventsFact

@named("market_approved")
final case class MarketApproved(marketId: String, operatorId: String, occurredAt: Instant) extends MarketsEventsFact

@named("market_opened")
final case class MarketOpened(marketId: String, occurredAt: Instant) extends MarketsEventsFact

@named("market_suspended")
final case class MarketSuspended(marketId: String, operatorId: String, reason: String, occurredAt: Instant) extends MarketsEventsFact

@named("market_resumed")
final case class MarketResumed(marketId: String, operatorId: String, occurredAt: Instant) extends MarketsEventsFact

/** Closed on time (no operator) or early by an operator, with their reason. */
@named("market_closed")
final case class MarketClosed(marketId: String, operatorId: Option[String], reason: Option[String], occurredAt: Instant)
    extends MarketsEventsFact

@named("market_close_moved")
final case class MarketCloseMoved(marketId: String, operatorId: String, close: Instant, expectedResolution: String, reason: String,
    occurredAt: Instant) extends MarketsEventsFact

@named("market_resolved")
final case class MarketResolved(marketId: String, operatorId: String, outcomeId: String, evidence: Vector[EvidenceItem],
    authority: String, windowEnd: Instant, occurredAt: Instant) extends MarketsEventsFact

@named("market_corrected")
final case class MarketCorrected(marketId: String, operatorId: String, outcomeId: String, evidence: Vector[EvidenceItem],
    windowEnd: Instant, occurredAt: Instant) extends MarketsEventsFact

@named("market_voided")
final case class MarketVoided(marketId: String, operatorId: String, reason: String, evidence: Vector[EvidenceItem],
    windowEnd: Instant, occurredAt: Instant) extends MarketsEventsFact

@named("market_final")
final case class MarketFinal(marketId: String, occurredAt: Instant) extends MarketsEventsFact

object MarketsEventsFact:
  given JsonValueCodec[MarketsEventsFact] = FactCodecs.make

object MarketsEvents extends Topic[MarketsEventsFact]("markets.events", Vector("markets")):
  val families = Vector(
    FamilyDef.of[MarketDrafted]("market_drafted"),
    FamilyDef.of[MarketChanged]("market_changed"),
    FamilyDef.of[MarketApproved]("market_approved"),
    FamilyDef.of[MarketOpened]("market_opened"),
    FamilyDef.of[MarketSuspended]("market_suspended"),
    FamilyDef.of[MarketResumed]("market_resumed"),
    FamilyDef.of[MarketClosed]("market_closed"),
    FamilyDef.of[MarketCloseMoved]("market_close_moved"),
    FamilyDef.of[MarketResolved]("market_resolved"),
    FamilyDef.of[MarketCorrected]("market_corrected"),
    FamilyDef.of[MarketVoided]("market_voided"),
    FamilyDef.of[MarketFinal]("market_final")
  )
