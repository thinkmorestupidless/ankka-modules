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
 *
 * Spec 022 adds `account_adjusted`, keyed by a non-player account (`AccountFact`); the topic's type is
 * the parent of both, `MoneyTransactionsMessage`.
 */
sealed trait MoneyTransactionsMessage extends Fact

/** A message about a player's wallet, keyed `<player>/<currency>`: every family but `account_adjusted`. */
sealed trait MoneyTransactionsFact extends MoneyTransactionsMessage:
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
    occurredAt: Instant, depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None, depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None,
    psp: Option[String] = None, scheme: Option[String] = None) extends MoneyTransactionsFact

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

// ── spec 022: reservations, settlement, resolution payments, non-player accounts ──
// The seven wallet-keyed families name what happened to the wallet and carry its reserved balance
// after beside real and restricted. A move's account side is no message of its own: the wallet's
// message names the account, and an account's balance is the fold over the messages that name it
// (specs/022-wallet-primitives/contracts/facts.md, SC-004). Each carries both pools after it and,
// where it moves real balance, its parts, as spec 025 FR-012 asks of every family.

/** Money set aside from real balance under an order (spec 022 FR-001). Real is unchanged. */
@named("reservation_placed")
final case class ReservationPlaced(playerId: String, currency: Currency, transactionId: String, orderId: String,
    amountCents: MinorUnits, realBalanceAfter: SignedMinorUnits, restrictedBalanceAfter: MinorUnits,
    reservedBalanceAfter: MinorUnits, occurredAt: Instant,
    depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None) extends MoneyTransactionsFact

/** Part or all of a reservation returned to the available amount (FR-002). */
@named("reservation_released")
final case class ReservationReleased(playerId: String, currency: Currency, transactionId: String, orderId: String,
    amountCents: MinorUnits, realBalanceAfter: SignedMinorUnits, restrictedBalanceAfter: MinorUnits,
    reservedBalanceAfter: MinorUnits, occurredAt: Instant,
    depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None) extends MoneyTransactionsFact

/** The buyer's side of a trade (FR-004): real down by the cost, the reservation by the cost and what was released. */
@named("settlement_debit")
final case class SettlementDebit(playerId: String, currency: Currency, transactionId: String, tradeId: String,
    orderId: String, amountCents: MinorUnits, releasedCents: MinorUnits, counterpartyPlayerId: String,
    realBalanceAfter: SignedMinorUnits, restrictedBalanceAfter: MinorUnits, reservedBalanceAfter: MinorUnits,
    occurredAt: Instant,
    depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None,
    depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None) extends MoneyTransactionsFact

/** The seller's side of a trade (FR-004): real up by the cost. */
@named("settlement_credit")
final case class SettlementCredit(playerId: String, currency: Currency, transactionId: String, tradeId: String,
    amountCents: MinorUnits, counterpartyPlayerId: String, realBalanceAfter: SignedMinorUnits,
    restrictedBalanceAfter: MinorUnits, reservedBalanceAfter: MinorUnits, occurredAt: Instant,
    depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None,
    depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None) extends MoneyTransactionsFact

/** A position paid at resolution from the market's pool account (FR-005). */
@named("resolution_payment")
final case class ResolutionPayment(playerId: String, currency: Currency, transactionId: String, marketId: String,
    positionId: String, amountCents: MinorUnits, realBalanceAfter: SignedMinorUnits, restrictedBalanceAfter: MinorUnits,
    reservedBalanceAfter: MinorUnits, occurredAt: Instant,
    depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None,
    depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None) extends MoneyTransactionsFact

/**
 * The wallet debited into a non-player account: a split, or a clearing move from the wallet (FR-006, FR-008).
 * A split charged from a reservation (FR-008a, a leg of a trade through the pool) names the order and
 * what it released, the reservation falling by the amount and the release.
 */
@named("account_debit")
final case class AccountDebit(playerId: String, currency: Currency, transactionId: String, accountKind: String,
    accountName: String, purpose: String, reference: String, amountCents: MinorUnits, realBalanceAfter: SignedMinorUnits,
    restrictedBalanceAfter: MinorUnits, reservedBalanceAfter: MinorUnits, occurredAt: Instant,
    depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None,
    depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None,
    orderId: Option[String] = None, releasedCents: Option[MinorUnits] = None) extends MoneyTransactionsFact

/** The wallet credited from a non-player account: a merge, or a clearing move to the wallet (FR-006, FR-008). */
@named("account_credit")
final case class AccountCredit(playerId: String, currency: Currency, transactionId: String, accountKind: String,
    accountName: String, purpose: String, reference: String, amountCents: MinorUnits, realBalanceAfter: SignedMinorUnits,
    restrictedBalanceAfter: MinorUnits, reservedBalanceAfter: MinorUnits, occurredAt: Instant,
    depositPoolAfter: Option[SignedMinorUnits] = None, convertedPoolAfter: Option[SignedMinorUnits] = None,
    depositPartCents: Option[MinorUnits] = None, convertedPartCents: Option[MinorUnits] = None) extends MoneyTransactionsFact

/**
 * A message about a non-player account (spec 022 FR-006), keyed `<kind>/<name>/<currency>`. It is
 * about no player, so it carries no `player_id`.
 */
sealed trait AccountFact extends MoneyTransactionsMessage:
  def accountKind: String
  def accountName: String
  def currency: Currency
  def transactionId: String
  def balanceAfter: SignedMinorUnits
  def occurredAt: Instant
  def key: String = s"$accountKind/$accountName/${currency.code}"

/** An operator's adjustment of a non-player account (FR-009). `reason` is the category, never the operator's text. */
@named("account_adjusted")
final case class AccountAdjusted(accountKind: String, accountName: String, currency: Currency, transactionId: String,
    amount: SignedMinorUnits, reason: String, actorId: String, balanceAfter: SignedMinorUnits, creditLine: MinorUnits,
    occurredAt: Instant) extends AccountFact

object MoneyTransactionsMessage:
  given JsonValueCodec[MoneyTransactionsMessage] = FactCodecs.make

object MoneyTransactionsFact:
  given JsonValueCodec[MoneyTransactionsFact] = FactCodecs.make

object MoneyTransactions extends Topic[MoneyTransactionsMessage]("money.transactions", Vector("wallet")):
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
    FamilyDef.of[ProviderCredited]("provider_credited"),
    FamilyDef.of[ReservationPlaced]("reservation_placed"),
    FamilyDef.of[ReservationReleased]("reservation_released"),
    FamilyDef.of[SettlementDebit]("settlement_debit"),
    FamilyDef.of[SettlementCredit]("settlement_credit"),
    FamilyDef.of[ResolutionPayment]("resolution_payment"),
    FamilyDef.of[AccountDebit]("account_debit"),
    FamilyDef.of[AccountCredit]("account_credit"),
    FamilyDef.of[AccountAdjusted]("account_adjusted")
  )
