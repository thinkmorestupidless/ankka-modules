package io.eitheror.casino.attribution.money

import io.eitheror.casino.contract.facts.*

/**
 * What a wallet fact means for affiliate GGR and bonus cost (contracts/facts.md; research R9, R10). The
 * real movement of a fact is its `realBalanceAfter` minus the wallet's previous one (`WalletCursor`), so a
 * split bet counts only its real part and restricted money never counts.
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
   * The leg a fact counts, given its real delta: a bet's stake is the real money it took (`−delta`), a win
   * or a credit landing on real money what it gave, a rollback what it gave back, a loss nothing. Every
   * other family counts nothing.
   */
  def counted(fact: MoneyTransactionsFact, delta: Long): Option[Leg] = fact match
    case f: BetPlaced                                  => Some(Leg(Leg.Stake, f.roundId, -delta))
    case f: WinCredited                                => Some(Leg(Leg.Win, f.roundId, delta))
    case f: ProviderCredited if f.landedOn == "real"   => Some(Leg(Leg.Win, f.providerRef, delta))
    case f: BetRolledBack                              => Some(Leg(Leg.Rollback, f.roundId, delta))
    case f: RoundSettledAsLoss                         => Some(Leg(Leg.Loss, f.roundId, 0L))
    case _                                             => None

  /** A bonus's cost to the brand, recognised at the award and never negative (spec 011 FR-028). */
  def bonusCost(fact: MoneyTransactionsFact): Option[(Long, String)] = fact match
    case f: RestrictedFundsAwarded                          => Some(f.amountCents.value -> f.awardId)
    case f: ProviderCredited if f.landedOn == "restricted"  => Some(f.amountCents.value -> f.providerRef)
    case _                                                  => None

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
    case _: AdminAdjusted            => "admin_adjusted"
    case _: HoldPlaced               => "hold_placed"
    case _: HoldReleased             => "hold_released"
    case _: WalletWithdrawalReversed => "withdrawal_reversed"
    case _: ProviderCredited         => "provider_credited"

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
        case f: ProviderCredited         => f.awardId.getOrElse("")
        case _                           => ""
      s"${family(fact)}/${fact.transactionId}/$award"
    }
