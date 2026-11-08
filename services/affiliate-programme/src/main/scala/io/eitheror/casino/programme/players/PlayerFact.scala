package io.eitheror.casino.programme.players

import io.eitheror.casino.contract.facts.*

import java.time.Instant

/**
 * A player's attribution fact as the programme keeps it: the contract's kinds flattened to one shape,
 * so the entity's journal does not depend on the contract's types. `amount` is signed (GGR, bonus cost).
 */
final case class PlayerFact(
    kind: String,
    factId: String,
    occurredAt: Instant,
    brand: String,
    pseudonym: String,
    affiliate: String,
    link: String,
    campaign: Option[String] = None,
    subIds: Vector[String] = Vector.empty,
    clickId: Option[String] = None,
    country: Option[String] = None,
    currency: Option[String] = None,
    amount: Long = 0,
    reference: Option[String] = None,
    reportingDay: Option[String] = None,
    beforeBinding: Boolean = false,
    category: Option[String] = None,
    /** The period in the brand's reporting zone, which `Intake` sets: what the revenue reads count by. */
    period: Option[String] = None
)

object PlayerFact:
  val Registration        = "registration"
  val FirstDeposit        = "first-deposit"
  val Deposit             = "deposit"
  val GgrDaily            = "ggr-daily"
  val GgrAdjustment       = "ggr-adjustment"
  val GgrRound            = "ggr-round"
  val BonusCost           = "bonus-cost"
  val Chargeback          = "chargeback"
  val WithdrawalRequested = "withdrawal-requested"
  val WithdrawalCompleted = "withdrawal-completed"
  val WithdrawalFailed    = "withdrawal-failed"
  val WithdrawalReversed  = "withdrawal-reversed"
  val PlayerClosed        = "player-closed"

  val ggrKinds = Set(GgrDaily, GgrAdjustment, GgrRound)

  /** The kinds a brand's GGR source folds: `daily` the day's figures and their adjustments, `rounds` each round. */
  def folds(kind: String, source: String): Boolean =
    if source == "rounds" then kind == GgrRound else kind == GgrDaily || kind == GgrAdjustment

  /** The contract's fact as the programme keeps it; a click is not a player's and is `None`. */
  def from(fact: AttributionFact): Option[PlayerFact] =
    def base(f: PlayerAttributionFact, kind: String) =
      PlayerFact(kind, f.factId, f.occurredAt, f.brand, f.pseudonym, f.affiliate, f.link, f.campaign, f.subIds, f.clickId)
    fact match
      case _: Click         => None
      case f: Registration  => Some(base(f, Registration).copy(country = f.country))
      case f: io.eitheror.casino.contract.facts.FirstDeposit =>
        Some(base(f, FirstDeposit).copy(currency = Some(f.currency.code), amount = f.amount.value, reference = Some(f.reference)))
      case f: io.eitheror.casino.contract.facts.Deposit =>
        Some(base(f, Deposit).copy(currency = Some(f.currency.code), amount = f.amount.value, reference = Some(f.reference)))
      case f: io.eitheror.casino.contract.facts.GgrDaily =>
        Some(base(f, GgrDaily).copy(currency = Some(f.currency.code), amount = f.ggr.value, reference = Some(f.reference),
          reportingDay = Some(f.reportingDay), beforeBinding = f.beforeBinding.contains(true)))
      case f: io.eitheror.casino.contract.facts.GgrAdjustment =>
        Some(base(f, GgrAdjustment).copy(currency = Some(f.currency.code), amount = f.ggr.value, reference = Some(f.reference),
          reportingDay = Some(f.reportingDay)))
      case f: io.eitheror.casino.contract.facts.GgrRound =>
        Some(base(f, GgrRound).copy(currency = Some(f.currency.code), amount = f.ggr.value, reference = Some(f.reference)))
      case f: io.eitheror.casino.contract.facts.BonusCost =>
        Some(base(f, BonusCost).copy(currency = Some(f.currency.code), amount = f.amount.value, reference = Some(f.reference)))
      case f: io.eitheror.casino.contract.facts.Chargeback =>
        Some(base(f, Chargeback).copy(currency = Some(f.currency.code), amount = f.amount.value, reference = Some(f.reference)))
      case f: AttributedWithdrawalRequested =>
        Some(base(f, WithdrawalRequested).copy(currency = Some(f.currency.code), amount = f.amount.value, reference = Some(f.reference)))
      case f: AttributedWithdrawalCompleted =>
        Some(base(f, WithdrawalCompleted).copy(currency = Some(f.currency.code), amount = f.amount.value, reference = Some(f.reference)))
      case f: AttributedWithdrawalFailed =>
        Some(base(f, WithdrawalFailed).copy(currency = Some(f.currency.code), amount = f.amount.value, reference = Some(f.reference)))
      case f: AttributedWithdrawalReversed =>
        Some(base(f, WithdrawalReversed).copy(currency = Some(f.currency.code), amount = f.amount.value, reference = Some(f.reference)))
      case f: PlayerClosedAttributed => Some(base(f, PlayerClosed).copy(category = Some(f.category)))
