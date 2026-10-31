package io.eitheror.casino.wallet.entity

import io.eitheror.casino.contract.money.Digest

import java.time.Instant

/**
 * A command that moves money: it carries the caller's key and the time the endpoint received it
 * (the handler never reads a clock). Every case class here is the argument of one entity command.
 */
sealed trait MoneyCommand extends Product:
  def key: String
  def at: Instant

object MoneyCommand:
  /**
   * Fields that are context the endpoint resolved, not what the caller sent: configuration read at
   * the time (switches, terms, the award limit) and the clock. A retry after a configuration change
   * is the same command, so these never enter the digest.
   */
  private val NotPayload = Set("key", "at", "switches", "brandTerms", "maxConcurrentAwards")

  /** The digest that tells a repeat from a reuse (FR-002): the wire name and every payload field. */
  def digest(wireName: String, command: MoneyCommand): String = digestOf(wireName, command)

  /**
   * The same rule over any command's fields: an account's money commands and a flow's start, which
   * are not wallet commands, are digested exactly as a wallet's (spec 022 research R4, R7).
   */
  def digestOf(wireName: String, command: Product): String =
    Digest.of(
      ("command" -> wireName) +: command.productElementNames.zip(command.productIterator.map(_.toString)).filterNot((n, _) => NotPayload(n)).toVector
    )

/**
 * The migration's opening command (FR-001, spec 019): a wallet as the original holds it — balances,
 * records with their rule snapshots (baselines `None` when the original kept none), open rounds,
 * deposits so far — accepted once.
 *
 * `depositPool` and `convertedPool` are the original's pools for a wallet it stamped; both absent is
 * a wallet it never stamped, which opens all converted (spec 025 FR-011). A pair that does not sum
 * to `realBalance`, or only one of the two, is `POOLS_INVARIANT`.
 */
final case class OpenMigrated(key: String, realBalance: Long, depositTurnoverPending: Long, records: Vector[AwardedRecord],
    totalDeposits: Long, depositOrdinal: Int, restrictedWithoutRecords: Long, at: Instant,
    openBets: Vector[Bet] = Vector.empty, withdrawals: Vector[WithdrawalRequest] = Vector.empty,
    pendingRequestId: Option[String] = None, depositPool: Option[Long] = None, convertedPool: Option[Long] = None)
    extends MoneyCommand

final case class Deposit(key: String, amount: Long, paymentProvider: String, terminal: String, psp: String, scheme: String,
    brandTerms: TurnoverTerms, at: Instant) extends MoneyCommand

final case class PlaceBet(key: String, roundId: String, gameId: String, sessionId: String, provider: String,
    category: String, amount: Long, allowNegative: Boolean, switches: BetSwitches, at: Instant) extends MoneyCommand

/**
 * `betKey` names the bet a win settles, as the original's `bet_tx_id` does in a round with several
 * bets; without it every open bet of the round is settled.
 */
final case class CreditWin(key: String, roundId: String, amount: Long, at: Instant, betKey: Option[String] = None) extends MoneyCommand

/** `betKey` names the bet a provider cancels; without it every open bet of the round is rolled back. */
final case class RollbackBet(key: String, roundId: String, betKey: Option[String], at: Instant) extends MoneyCommand

/** `betKey` as for a win. */
final case class SettleLoss(key: String, roundId: String, at: Instant, betKey: Option[String] = None) extends MoneyCommand

final case class ProviderCredit(key: String, amount: Long, providerRef: String, reason: String, actorId: String,
    at: Instant) extends MoneyCommand

/** `key` is `rewards`' grant id, which becomes the record's grant reference (FR-015). */
final case class Award(key: String, bonusRef: String, amount: Long, wageringMultiplier: Long, maxConvertible: Long,
    priority: Long, expiresAt: Option[Instant], rules: Rules, maxConcurrentAwards: Option[Int], at: Instant)
    extends MoneyCommand

final case class ForfeitAward(key: String, grantRef: Option[String], at: Instant) extends MoneyCommand

final case class CancelAward(key: String, grantRef: Option[String], at: Instant) extends MoneyCommand

final case class ClosePendingCap(key: String, at: Instant) extends MoneyCommand

final case class SwitchPrimary(key: String, grantRef: String, at: Instant) extends MoneyCommand

/** `key` is `payouts`' request id (FR-023). */
final case class Hold(key: String, amount: Long, playerConfirmed: Boolean, at: Instant) extends MoneyCommand

/** `releasedAs`: cancelled | rejected | failed (FR-024). */
final case class Release(key: String, requestId: String, releasedAs: String, at: Instant) extends MoneyCommand

final case class Confirm(key: String, requestId: String, at: Instant) extends MoneyCommand

final case class Reverse(key: String, requestId: String, at: Instant) extends MoneyCommand

/** `target`: real | restricted; `amount` signed (FR-026). */
final case class Adjust(key: String, target: String, amount: Long, reason: String, category: String, actorId: String,
    at: Instant) extends MoneyCommand

/** Not a money command: an operator's override of the brand's turnover terms for this player (FR-004a). */
final case class SetTurnoverOverride(terms: Option[TurnoverTerms], actorId: String, at: Instant)

final case class DepositSumsAt(asOf: Instant)

final case class PreviewHold(amount: Long)

// ── spec 022: reservations, settlement, moves with a non-player account ──────

/** Set aside `amount` of real balance under an order; the key is the order id (FR-001). */
final case class Reserve(key: String, amount: Long, at: Instant) extends MoneyCommand

/** Return part or all of an order's reservation; the key is `<orderId>/<sequence>` (FR-002, research R2). */
final case class ReleaseReservation(key: String, orderId: String, amount: Long, at: Instant) extends MoneyCommand

/**
 * The buyer's side of a trade (FR-004): `amount` leaves real balance and the order's reservation,
 * and `unused` (the exchange's figure) is released from the reservation. The key is the trade id.
 */
final case class SettleDebit(key: String, orderId: String, amount: Long, unused: Long, counterparty: String, at: Instant)
    extends MoneyCommand

/** The seller's side of a trade (FR-004): `amount` added to real balance. The key is the trade id. */
final case class SettleCredit(key: String, amount: Long, counterparty: String, at: Instant) extends MoneyCommand

/**
 * Real balance moved into a non-player account (a split; a clearing move from the wallet; FR-006,
 * FR-008). `purpose` is `split` or `clearing`; `reference` is the caller's key.
 */
final case class DebitToAccount(key: String, accountKind: String, accountName: String, purpose: String, reference: String,
    amount: Long, at: Instant) extends MoneyCommand

/**
 * A split charged from an order's reservation (FR-008a, a buying leg of a trade through the pool):
 * `amount` leaves real balance and the reservation for the account, and `unused` is released from
 * the reservation, as a settlement's buyer side does. The key is the caller's, one per leg.
 */
final case class ChargeReservationToAccount(key: String, orderId: String, accountKind: String, accountName: String, purpose: String,
    reference: String, amount: Long, unused: Long, at: Instant) extends MoneyCommand

/**
 * Real balance credited from a non-player account (a merge, a resolution payment, a clearing move
 * to the wallet; FR-005, FR-006, FR-008). For a resolution payment `reference` is the position id.
 */
final case class CreditFromAccount(key: String, accountKind: String, accountName: String, purpose: String, reference: String,
    amount: Long, at: Instant) extends MoneyCommand

/** What a move with an account is for (data-model.md). */
object Purpose:
  val Split             = "split"
  val Merge             = "merge"
  val ResolutionPayment = "resolution_payment"
  val Clearing          = "clearing"
  val all: Set[String]  = Set(Split, Merge, ResolutionPayment, Clearing)
