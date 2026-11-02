package io.eitheror.casino.attribution.money

import io.eitheror.casino.attribution.MoneyEvent
import io.eitheror.casino.contract.facts.*

/**
 * What a wallet fact means for affiliate GGR and bonus cost (contracts/facts.md; research R9, R10). A
 * fact counts its deposit part (`depositPartCents`, spec 025): the money of its real movement that a
 * player deposited. Converted bonus money is never counted as deposited money (Sheldon, 2026-10-28), so
 * a stake from the converted pool, its win and its rollback count nothing, restricted money never counts,
 * and a fact without parts counts 0.
 */
object RealMoney:

  /** One leg of a round on a reporting day: `stake` and `win` positive, `rollback` reducing the stake. */
  final case class Leg(kind: String, roundId: String, amount: Long)

  object Leg:
    val Stake    = "stake"
    val Win      = "win"
    val Rollback = "rollback"
    val Loss     = "loss"
    val settling: Set[String] = Set(Win, Rollback, Loss)

  /**
   * The leg a fact counts: a bet's stake, a win's payout and a rollback's return are their deposit parts,
   * a loss settles its round with nothing. A provider credit joins the converted pool (spec 025), so it
   * counts nothing; every other family counts nothing.
   */
  def counted(fact: MoneyTransactionsFact): Option[Leg] = fact match
    case f: BetPlaced          => Some(Leg(Leg.Stake, f.roundId, deposit(f.depositPartCents)))
    case f: WinCredited        => Some(Leg(Leg.Win, f.roundId, deposit(f.depositPartCents)))
    case f: BetRolledBack      => Some(Leg(Leg.Rollback, f.roundId, deposit(f.depositPartCents)))
    case f: RoundSettledAsLoss => Some(Leg(Leg.Loss, f.roundId, 0L))
    case _                     => None

  /** A fact's deposit part; one without parts (published before spec 025) counts 0, never its real delta. */
  private def deposit(part: Option[io.eitheror.casino.contract.money.MinorUnits]): Long = part.fold(0L)(_.value)

  /**
   * A bonus's cost to the brand from the wallet, recognised at the award and never negative (spec 011 FR-028): a
   * restricted award, and a provider credit landing on restricted money. A free-rounds grant's cost is its
   * `free_spins_settled` instead (spec 011, "Free-rounds cost from free_spins_settled"), so its winnings awards,
   * told by their key, cost nothing here; its provider credit names only the reward, which the attribution record
   * recognises from `casino.rewards` and drops (`Record.money`).
   */
  def bonusCost(fact: MoneyTransactionsFact): Option[(Long, String)] = fact match
    case f: RestrictedFundsAwarded if !freeRoundsWinnings(f.awardId) => Some(f.amountCents.value -> f.awardId)
    case f: ProviderCredited if f.landedOn == "restricted"           => Some(f.amountCents.value -> f.providerRef)
    case _                                                           => None

  /**
   * Whether an award is a free-rounds grant's winnings as `rewards` keys them (spec 011 research R5): `<reward>/winnings`
   * at finalisation, `<reward>/late/<round>` for a round after it. A funds reward's award is keyed by the reward id.
   */
  def freeRoundsWinnings(awardId: String): Boolean =
    val slash = awardId.indexOf('/')
    slash > 0 && { val rest = awardId.substring(slash + 1); rest == "winnings" || rest.startsWith("late/") }

  /**
   * A free-rounds grant's cost: what one `free_spins_settled` paid (spec 011, "Free-rounds cost from
   * free_spins_settled"), at the finalisation and again for each late round. The reference is
   * `<reward>/settled/<occurredAt in ms>`, so each settlement is a `bonus-cost` of its own; the dedupe id is the
   * fact's `ce-id`, or, when the runtime does not surface it (ankka 0.13), `free_spins_settled/<reward>/<ms>/<win>`.
   * Rounds that won nothing cost nothing.
   */
  def freeRoundsCost(fact: FreeSpinsSettled, ceId: Option[String]): Option[MoneyEvent] =
    Option.when(fact.winCents.value > 0) {
      val ms = fact.occurredAt.toEpochMilli
      MoneyEvent(MoneyEvent.BonusCost, fact.currency.code, fact.winCents.value, s"${fact.bonusId}/settled/$ms", fact.occurredAt,
        ceId.filter(_.nonEmpty).getOrElse(s"free_spins_settled/${fact.bonusId}/$ms/${fact.winCents.value}"))
    }

  /** A fact's family, as the topic names it. */
  def family(fact: MoneyTransactionsFact): String = fact match
    case _: DepositConfirmed         => "deposit_confirmed"
    case _: BetPlaced                => "bet_placed"
    case _: WinCredited              => "win_credited"
    case _: BetRolledBack            => "bet_rolled_back"
    case _: RoundSettledAsLoss       => "round_settled_as_loss"
    case _: RestrictedFundsAwarded   => "restricted_funds_awarded"
    case _: RestrictedFundsConverted => "restricted_funds_converted"
    case _: RestrictedFundsForfeited => "restricted_funds_forfeited"
    case _: RestrictedFundsClosed    => "restricted_funds_closed"
    case _: AdminAdjusted            => "admin_adjusted"
    case _: HoldPlaced               => "hold_placed"
    case _: HoldReleased             => "hold_released"
    case _: WalletWithdrawalReversed => "withdrawal_reversed"
    case _: ProviderCredited         => "provider_credited"
    case _: ReservationPlaced        => "reservation_placed"
    case _: ReservationReleased      => "reservation_released"
    case _: SettlementDebit          => "settlement_debit"
    case _: SettlementCredit         => "settlement_credit"
    case _: ResolutionPayment        => "resolution_payment"
    case _: AccountDebit             => "account_debit"
    case _: AccountCredit            => "account_credit"

  /**
   * A message's dedupe id: its `ce-id` as the wallet set it, or, when the runtime does not surface it,
   * `<family>/<transactionId>/<awardId or empty>` (data-model.md).
   */
  def dedupeId(fact: MoneyTransactionsFact, ceId: Option[String]): String =
    ceId.filter(_.nonEmpty).getOrElse {
      val award = fact match
        case f: RestrictedFundsAwarded   => f.awardId
        case f: RestrictedFundsConverted => f.awardId
        case f: RestrictedFundsForfeited => f.awardId
        case f: RestrictedFundsClosed    => f.awardId
        case f: ProviderCredited         => f.awardId.getOrElse("")
        case _                           => ""
      s"${family(fact)}/${fact.transactionId}/$award"
    }
