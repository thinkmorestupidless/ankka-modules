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
  def digest(wireName: String, command: MoneyCommand): String =
    Digest.of(
      ("command" -> wireName) +: command.productElementNames.zip(command.productIterator.map(_.toString)).filterNot((n, _) => NotPayload(n)).toVector
    )

/**
 * The migration's opening command (FR-001, spec 019): a wallet as the original holds it — balances,
 * records with their rule snapshots (baselines `None` when the original kept none), open rounds,
 * deposits so far — accepted once.
 */
final case class OpenMigrated(key: String, realBalance: Long, depositTurnoverPending: Long, records: Vector[AwardedRecord],
    totalDeposits: Long, depositOrdinal: Int, restrictedWithoutRecords: Long, at: Instant,
    openBets: Vector[Bet] = Vector.empty, withdrawals: Vector[WithdrawalRequest] = Vector.empty,
    pendingRequestId: Option[String] = None) extends MoneyCommand

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
