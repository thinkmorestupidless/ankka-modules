package io.eitheror.casino.contract.facts

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.named
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.Instant

/**
 * `money.transactions`, produced by `wallet` (spec 001 FR-011; spec 002). Keyed by the wallet,
 * `<player>/<currency>`. Every family carries the real and restricted balance after the movement,
 * so a reader folds balances without a call. Since spec 025 every family also carries the two pools
 * after it, and each family whose movement touches real balance the deposit-origin and
 * converted-origin parts of that movement (`deposit_part_cents`, `converted_part_cents`). The fields
 * are optional in the schema because they were added (spec 001 FR-013); the wallet always writes them. `amount_cents` and `transaction_id` are the original's
 * export names (FR-014).
 */
sealed trait MoneyTransactionsFact extends Fact:
  def playerId: String
  def currency: Currency
  def transactionId: String
  def realBalanceAfter: SignedMinorUnits
  def restrictedBalanceAfter: MinorUnits
  def occurredAt: Instant
  /** The wallet's deposit and converted pools after the movement (spec 025 FR-012); they sum to
    * `realBalanceAfter`. Absent only on a fact written before spec 025. */
  def depositPoolAfter: Option[SignedMinorUnits]
  def convertedPoolAfter: Option[SignedMinorUnits]
  def key: String = s"$playerId/${currency.code}"

@named("deposit_confirmed")
final case class DepositConfirmed(playerId: String, currency: Currency, transactionId: String, amountCents: MinorUnits,
    paymentReference: String, depositOrdinal: Int, realBalanceAfter: SignedMinorUnits, restrictedBalanceAfter: MinorUnits,
    occurredAt: Instant, depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None, depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None) extends MoneyTransactionsFact

@named("bet_placed")
final case class BetPlaced(playerId: String, currency: Currency, transactionId: String, roundId: String, gameId: String,
    amountCents: MinorUnits, walletType: String, realBalanceAfter: SignedMinorUnits, restrictedBalanceAfter: MinorUnits,
    occurredAt: Instant, depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None, depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None) extends MoneyTransactionsFact

@named("win_credited")
final case class WinCredited(playerId: String, currency: Currency, transactionId: String, roundId: String, gameId: String,
    amountCents: MinorUnits, walletType: String, realBalanceAfter: SignedMinorUnits, restrictedBalanceAfter: MinorUnits,
    occurredAt: Instant, depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None, depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None) extends MoneyTransactionsFact

@named("bet_rolled_back")
final case class BetRolledBack(playerId: String, currency: Currency, transactionId: String, roundId: String,
    amountCents: MinorUnits, realBalanceAfter: SignedMinorUnits, restrictedBalanceAfter: MinorUnits, occurredAt: Instant, depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None, depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None)
    extends MoneyTransactionsFact

@named("round_settled_as_loss")
final case class RoundSettledAsLoss(playerId: String, currency: Currency, transactionId: String, roundId: String,
    realBalanceAfter: SignedMinorUnits, restrictedBalanceAfter: MinorUnits, occurredAt: Instant, depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None) extends MoneyTransactionsFact

@named("restricted_funds_awarded")
final case class RestrictedFundsAwarded(playerId: String, currency: Currency, transactionId: String, awardId: String,
    amountCents: MinorUnits, wageringRequirement: MinorUnits, realBalanceAfter: SignedMinorUnits,
    restrictedBalanceAfter: MinorUnits, occurredAt: Instant, depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None) extends MoneyTransactionsFact

@named("restricted_funds_converted")
final case class RestrictedFundsConverted(playerId: String, currency: Currency, transactionId: String, awardId: String,
    amountCents: MinorUnits, realBalanceAfter: SignedMinorUnits, restrictedBalanceAfter: MinorUnits, occurredAt: Instant, depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None, depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None)
    extends MoneyTransactionsFact

@named("restricted_funds_forfeited")
final case class RestrictedFundsForfeited(playerId: String, currency: Currency, transactionId: String, awardId: String,
    amountCents: MinorUnits, reason: String, clawbackCents: MinorUnits, realBalanceAfter: SignedMinorUnits,
    restrictedBalanceAfter: MinorUnits, occurredAt: Instant, depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None, depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None) extends MoneyTransactionsFact

@named("admin_adjusted")
final case class AdminAdjusted(playerId: String, currency: Currency, transactionId: String, amount: SignedMinorUnits,
    target: String, reason: String, actorId: String, realBalanceAfter: SignedMinorUnits, restrictedBalanceAfter: MinorUnits,
    occurredAt: Instant, depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None, depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None) extends MoneyTransactionsFact

@named("hold_placed")
final case class HoldPlaced(playerId: String, currency: Currency, transactionId: String, withdrawalRequestId: String,
    amountCents: MinorUnits, voidedCents: MinorUnits, realBalanceAfter: SignedMinorUnits, restrictedBalanceAfter: MinorUnits, occurredAt: Instant, depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None, depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None, voidedDepositCents: Option[MinorUnits] = None, voidedConvertedCents: Option[MinorUnits] = None)
    extends MoneyTransactionsFact

@named("hold_released")
final case class HoldReleased(playerId: String, currency: Currency, transactionId: String, withdrawalRequestId: String,
    amountCents: MinorUnits, realBalanceAfter: SignedMinorUnits, restrictedBalanceAfter: MinorUnits, occurredAt: Instant, depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None, depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None)
    extends MoneyTransactionsFact

/**
 * A confirmed withdrawal returned to real balance (spec 002 FR-025). Named apart from
 * `casino.withdrawals`' `WithdrawalReversed`, which is `payouts`' fact about the request; the family
 * on this topic is `withdrawal_reversed`.
 */
@named("withdrawal_reversed")
final case class WalletWithdrawalReversed(playerId: String, currency: Currency, transactionId: String,
    withdrawalRequestId: String, amountCents: MinorUnits, realBalanceAfter: SignedMinorUnits,
    restrictedBalanceAfter: MinorUnits, occurredAt: Instant, depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None, depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None) extends MoneyTransactionsFact

/** A game provider's standalone credit (spec 002 FR-027), landing on `real` or `restricted`. */
@named("provider_credited")
final case class ProviderCredited(playerId: String, currency: Currency, transactionId: String, providerRef: String,
    amountCents: MinorUnits, landedOn: String, awardId: Option[String], realBalanceAfter: SignedMinorUnits,
    restrictedBalanceAfter: MinorUnits, occurredAt: Instant, depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None, depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None) extends MoneyTransactionsFact

object MoneyTransactionsFact:
  given JsonValueCodec[MoneyTransactionsFact] = FactCodecs.make

object MoneyTransactions extends Topic[MoneyTransactionsFact]("money.transactions", Vector("wallet")):
  val families = Vector(
    FamilyDef.of[DepositConfirmed]("deposit_confirmed"),
    FamilyDef.of[BetPlaced]("bet_placed"),
    FamilyDef.of[WinCredited]("win_credited"),
    FamilyDef.of[BetRolledBack]("bet_rolled_back"),
    FamilyDef.of[RoundSettledAsLoss]("round_settled_as_loss"),
    FamilyDef.of[RestrictedFundsAwarded]("restricted_funds_awarded"),
    FamilyDef.of[RestrictedFundsConverted]("restricted_funds_converted"),
    FamilyDef.of[RestrictedFundsForfeited]("restricted_funds_forfeited"),
    FamilyDef.of[AdminAdjusted]("admin_adjusted"),
    FamilyDef.of[HoldPlaced]("hold_placed"),
    FamilyDef.of[HoldReleased]("hold_released"),
    FamilyDef.of[WalletWithdrawalReversed]("withdrawal_reversed"),
    FamilyDef.of[ProviderCredited]("provider_credited")
  )
