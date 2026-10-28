package io.eitheror.casino.attribution.facts

import io.eitheror.casino.attribution.money.Tally
import io.eitheror.casino.attribution.record.Record
import io.eitheror.casino.attribution.{Binding, MoneyEvent, Named}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.Instant

/**
 * What the record's and the tallies' events become on `affiliates.attribution` (contracts/facts.md):
 * each fact filled from the event alone, so a redelivered event is the same fact with the same id
 * (FR-022). Nothing is emitted for an unbound player, and no money fact for an input before the binding.
 */
object Facts:
  val ProtocolVersion = 1

  /** A day closed before a later code bound the player (research R10), for the `beforeBinding` facts. */
  final case class ClosedDay(currency: String, day: String, stake: Long, win: Long, closedAt: Instant)

  def click(brand: String, clickId: String, named: Named, at: Instant, country: Option[String], deviceType: Option[String]): Click =
    Click(ProtocolVersion, FactIds.click(clickId), at, brand, named.network, named.affiliate, named.link, named.campaign,
      named.subIds, Some(clickId), country, deviceType)

  def fromRecord(brand: String, event: Record.Event): Vector[AttributionFact] = event match
    case Record.Bound(b, country, pending, first) =>
      Registration(ProtocolVersion, FactIds.registration(b.pseudonym), b.boundAt, brand, b.network, b.affiliate, b.link,
        b.campaign, b.subIds, b.clickId, b.pseudonym, country) +:
        pending.flatMap(e => money(brand, b, e, first.contains(e.dedupeId)))
    case Record.DepositRecorded(e, first, b) => money(brand, b, e, first)
    case Record.ChargebackRecorded(e, b)     => money(brand, b, e, first = false)
    case Record.WithdrawalRecorded(e, b)     => money(brand, b, e, first = false)
    case Record.BonusCostRecorded(e, b)      => money(brand, b, e, first = false)
    case Record.Closed(category, at, Some(b)) =>
      Vector(PlayerClosedAttributed(ProtocolVersion, FactIds.playerClosed(b.pseudonym), at, brand, b.network, b.affiliate,
        b.link, b.campaign, b.subIds, b.clickId, b.pseudonym, category))
    case _ => Vector.empty

  /** A money event's fact, and a `first-deposit` beside the first deposit; nothing before the binding. */
  def money(brand: String, b: Binding, e: MoneyEvent, first: Boolean): Vector[AttributionFact] =
    if e.at.isBefore(b.boundAt) then Vector.empty
    else
      val c  = Currency.unsafe(e.currency)
      val a  = MinorUnits.unsafe(math.max(0L, e.amount))
      val id = FactIds.money(e.kind, b.pseudonym, e.reference)
      val fact: AttributionFact = e.kind match
        case MoneyEvent.Deposit =>
          Deposit(ProtocolVersion, FactIds.deposit(b.pseudonym, e.reference), e.at, brand, b.network, b.affiliate, b.link,
            b.campaign, b.subIds, b.clickId, b.pseudonym, c, a, e.reference)
        case MoneyEvent.Chargeback =>
          Chargeback(ProtocolVersion, id, e.at, brand, b.network, b.affiliate, b.link, b.campaign, b.subIds, b.clickId,
            b.pseudonym, c, a, e.reference)
        case MoneyEvent.BonusCost =>
          BonusCost(ProtocolVersion, id, e.at, brand, b.network, b.affiliate, b.link, b.campaign, b.subIds, b.clickId,
            b.pseudonym, c, SignedMinorUnits(math.max(0L, e.amount)), e.reference)
        case MoneyEvent.WithdrawalRequested =>
          AttributedWithdrawalRequested(ProtocolVersion, id, e.at, brand, b.network, b.affiliate, b.link, b.campaign,
            b.subIds, b.clickId, b.pseudonym, c, a, e.reference)
        case MoneyEvent.WithdrawalCompleted =>
          AttributedWithdrawalCompleted(ProtocolVersion, id, e.at, brand, b.network, b.affiliate, b.link, b.campaign,
            b.subIds, b.clickId, b.pseudonym, c, a, e.reference)
        case MoneyEvent.WithdrawalFailed =>
          AttributedWithdrawalFailed(ProtocolVersion, id, e.at, brand, b.network, b.affiliate, b.link, b.campaign,
            b.subIds, b.clickId, b.pseudonym, c, a, e.reference)
        case _ =>
          AttributedWithdrawalReversed(ProtocolVersion, id, e.at, brand, b.network, b.affiliate, b.link, b.campaign,
            b.subIds, b.clickId, b.pseudonym, c, a, e.reference)
      val firstDeposit = Option.when(first && e.kind == MoneyEvent.Deposit)(
        FirstDeposit(ProtocolVersion, FactIds.firstDeposit(b.pseudonym), e.at, brand, b.network, b.affiliate, b.link,
          b.campaign, b.subIds, b.clickId, b.pseudonym, c, a, e.reference))
      firstDeposit.toVector :+ fact

  /**
   * A tally's event for a bound player: a closed day with movement is `ggr-daily`; a late leg
   * `ggr-adjustment`; a settling leg `ggr-round` when the network takes the round stream (FR-016).
   */
  def fromTally(brand: String, b: Binding, event: Tally.Event, roundStream: Boolean): Vector[AttributionFact] = event match
    case e: Tally.Closed if e.moved => Vector(daily(brand, b, ClosedDay(e.currency, e.day, e.stake, e.win, e.at), before = false))
    case e: Tally.Adjusted if !e.at.isBefore(b.boundAt) =>
      Vector(GgrAdjustment(ProtocolVersion, FactIds.ggrAdjustment(b.pseudonym, e.currency, e.day, e.dedupeId), e.at, brand,
        b.network, b.affiliate, b.link, b.campaign, b.subIds, b.clickId, b.pseudonym, Currency.unsafe(e.currency), e.day,
        SignedMinorUnits(e.ggr), s"${e.currency}/${e.day}/${e.dedupeId}"))
    case e: Tally.LegRecorded if roundStream && !e.at.isBefore(b.boundAt) =>
      e.round.filter(r => r.stake != 0 || r.win != 0).toVector.map { r =>
        GgrRound(ProtocolVersion, FactIds.ggrRound(b.pseudonym, e.roundId, e.dedupeId), e.at, brand, b.network, b.affiliate,
          b.link, b.campaign, b.subIds, b.clickId, b.pseudonym, Currency.unsafe(e.currency), MinorUnits.unsafe(math.max(0L, r.stake)),
          MinorUnits.unsafe(math.max(0L, r.win)), SignedMinorUnits(r.stake - r.win),
          if r.first then e.roundId else s"${e.roundId}/${e.dedupeId}")
      }
    case _ => Vector.empty

  /** The days closed before a later code's binding, each marked `beforeBinding` (research R10). */
  def beforeBinding(brand: String, b: Binding, days: Vector[ClosedDay]): Vector[AttributionFact] =
    days.filter(d => d.stake != 0 || d.win != 0).map(daily(brand, b, _, before = true))

  private def daily(brand: String, b: Binding, d: ClosedDay, before: Boolean): GgrDaily =
    GgrDaily(ProtocolVersion, FactIds.ggrDaily(b.pseudonym, d.currency, d.day), d.closedAt, brand, b.network, b.affiliate,
      b.link, b.campaign, b.subIds, b.clickId, b.pseudonym, Currency.unsafe(d.currency), d.day,
      MinorUnits.unsafe(math.max(0L, d.stake)), MinorUnits.unsafe(math.max(0L, d.win)), SignedMinorUnits(d.stake - d.win),
      s"${d.currency}/${d.day}", Option.when(before)(true))
