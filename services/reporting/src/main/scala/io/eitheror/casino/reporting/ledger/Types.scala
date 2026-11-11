package io.eitheror.casino.reporting.ledger

import io.eitheror.casino.contract.facts.*

/**
 * The ledger's transaction types (FR-010; research R5, R20 item 1): each wallet family shows the type name the
 * original's wallet writes for the same movement (`starter-packs/wallet/handlers-go/internal/apply/props.go`), so
 * the parity tests compare like for like (decided 2026-11-08). Spec 022's families are not a player's casino
 * transactions and have none.
 */
object Types:
  val Deposit            = "deposit"
  val Withdrawal         = "withdrawal"
  val WithdrawalReturn   = "withdrawal_return"
  val WithdrawalReversal = "withdrawal_reversal"
  val Bet                = "bet"
  val Win                = "win"
  val Rollback           = "rollback"
  val Loss               = "loss"
  val BonusCredit        = "bonus_credit"
  val BonusConversion    = "bonus_conversion"
  val BonusCancel        = "bonus_cancel"
  val BonusCapVoid       = "bonus_cap_void"
  val AdminAdjustment    = "admin_adjustment"
  val ProviderCredit     = "game_provider_credit"

  def of(fact: MoneyTransactionsFact): Option[String] = fact match
    case _: DepositConfirmed         => Some(Deposit)
    case _: BetPlaced                => Some(Bet)
    case _: WinCredited              => Some(Win)
    case _: BetRolledBack            => Some(Rollback)
    case _: RoundSettledAsLoss       => Some(Loss)
    case _: RestrictedFundsAwarded   => Some(BonusCredit)
    case _: RestrictedFundsConverted => Some(BonusConversion)
    case _: RestrictedFundsForfeited => Some(BonusCancel)
    case _: RestrictedFundsClosed    => Some(BonusCapVoid)
    case _: AdminAdjusted            => Some(AdminAdjustment)
    case _: HoldPlaced               => Some(Withdrawal)
    case _: HoldReleased             => Some(WithdrawalReturn)
    case _: WalletWithdrawalReversed => Some(WithdrawalReversal)
    case _: ProviderCredited         => Some(ProviderCredit)
    case _                           => None

  /** FR-010's filter values. Five match their type; `bonus` matches every `bonus_*` type. `all` or none matches all. */
  val filters: Set[String] = Set(Deposit, Withdrawal, Bet, Win, Rollback, "bonus")

  def matches(filter: Option[String], tpe: String): Boolean = filter match
    case None | Some("all") | Some("") => true
    case Some("bonus")                 => tpe.startsWith("bonus_")
    case Some(f)                       => tpe == f
