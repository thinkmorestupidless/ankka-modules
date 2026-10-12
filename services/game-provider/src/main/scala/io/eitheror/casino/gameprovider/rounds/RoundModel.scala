package io.eitheror.casino.gameprovider.rounds

import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

/** The answer a callback was given; its bet workflow keeps it so a repeat is answered the same (FR-011). Balance in minor units, signed. */
final case class StoredAnswer(status: String, balance: Option[Long] = None, currency: Option[String] = None)

/** One movement this service made in the wallet for a provider transaction. */
final case class Movement(action: String, walletKey: String, walletRoundId: Option[String], amount: Long, walletTransactionId: String)

object Movement:
  val Bet            = "bet"
  val Win            = "win"
  val Loss           = "loss"
  val Rollback       = "rollback"
  val ProviderCredit = "provider_credit"

/** A bet this service placed and the wallet holds open, keyed in the wallet by the debit's transaction id. */
final case class OpenBet(walletKey: String, walletRoundId: String, amount: Long, transactionId: String)

/**
 * One transaction the provider sent for the round (data-model.md): its id (the cancel id for a cancel),
 * kind, amount, the transaction a cancel references, the wallet movements it made, and its answer.
 */
final case class ProviderTransaction(
    transactionId: String,
    kind: String,
    amount: Long,
    references: Option[String],
    movements: Vector[Movement],
    answer: StoredAnswer,
    receivedAt: Instant
):
  def walletTransactionIds: Vector[String] = movements.map(_.walletTransactionId)

/** A repeat whose body differed from the first: answered the first way, and recorded (FR-011). */
final case class ConflictingRepeat(transactionId: String, difference: String, at: Instant)

/** How an unacknowledged callback was cleared (FR-020). */
final case class Cleared(how: String, reason: Option[String], operator: Option[String], at: Instant)

object Cleared:
  val ProviderRetry   = "provider_retry"
  val OperatorReplay  = "operator_replay"
  val OperatorResolve = "operator_resolve"

/**
 * A callback the wallet did not acknowledge (FR-020): what it was, why, and the body it came with so an
 * operator can replay it under the same transaction id.
 */
final case class UnacknowledgedEntry(
    transactionId: String,
    callback: String,
    kind: String,
    amount: Long,
    error: String,
    body: String,
    recordedAt: Instant,
    cleared: Option[Cleared] = None
):
  def open: Boolean = cleared.isEmpty

/** The round's identity and context, taken from the first callback that names it. */
final case class RoundOpening(provider: String, roundId: String, playerId: String, currency: String, gameCode: Option[String],
    sessionId: Option[String], at: Instant)

final case class RoundState(
    provider: String = "",
    roundId: String = "",
    playerId: String = "",
    currency: String = "",
    gameCode: Option[String] = None,
    sessionId: Option[String] = None,
    transactions: Vector[ProviderTransaction] = Vector.empty,
    conflictingRepeats: Vector[ConflictingRepeat] = Vector.empty,
    openBets: Vector[OpenBet] = Vector.empty,
    totalStake: Long = 0,
    win: Long = 0,
    freeRoundsGrant: Option[String] = None,
    unacknowledged: Vector[UnacknowledgedEntry] = Vector.empty,
    status: String = RoundState.New,
    openedAt: Option[Instant] = None,
    settledAt: Option[Instant] = None
):
  def isNew: Boolean = status == RoundState.New

  def transaction(id: String): Option[ProviderTransaction] = transactions.find(_.transactionId == id)

  def openUnacknowledged(id: String): Option[UnacknowledgedEntry] = unacknowledged.find(e => e.transactionId == id && e.open)

  /** A transaction a later cancel already referenced, so cancelling it again moves nothing. */
  def cancelled(id: String): Boolean = transactions.exists(t => t.references.contains(id) && t.answer.status == "ok")

  def providerTransactionIds: Vector[String] = transactions.filter(_.answer.status == "ok").map(_.transactionId)

  def walletTransactionIds: Vector[String] = transactions.flatMap(_.walletTransactionIds)

object RoundState:
  val New       = "new"
  val Open      = "open"
  val Settled   = "settled"
  val Cancelled = "cancelled"

/** What a settlement or cancellation publishes, carried on the event so the consumer needs no state (FR-019). */
final case class SettlementSummary(
    provider: String,
    roundId: String,
    playerId: String,
    currency: String,
    gameCode: Option[String],
    sessionId: Option[String],
    stake: Long,
    win: Long,
    outcome: String,
    providerTransactionIds: Vector[String],
    walletTransactionIds: Vector[String],
    freeRoundsGrant: Option[String],
    at: Instant
)

sealed trait RoundEvent

object RoundEvent:
  final case class RoundOpened(opening: RoundOpening) extends RoundEvent
  /** A debit, free debit, correction debit or buyin, with the bet it opened when it opened one. */
  final case class DebitRecorded(tx: ProviderTransaction, opened: Option[OpenBet]) extends RoundEvent
  /** A credit, with the open bets it settled (their wallet keys). */
  final case class CreditRecorded(tx: ProviderTransaction, closed: Vector[String]) extends RoundEvent
  /** A cancel of the transaction it references: a rolled back bet closes, a cancelled credit lowers the win. */
  final case class CancelRecorded(tx: ProviderTransaction, closed: Vector[String], stakeReturned: Long, winReturned: Long) extends RoundEvent
  final case class BuyinRecorded(tx: ProviderTransaction, opened: Option[OpenBet]) extends RoundEvent
  final case class PayoutRecorded(tx: ProviderTransaction) extends RoundEvent
  /** A refusal answered for a transaction that moved nothing (not enough money, a limit, a lock). */
  final case class RefusalRecorded(tx: ProviderTransaction) extends RoundEvent
  final case class FreeRoundsTagged(grant: String) extends RoundEvent
  final case class RepeatConflicted(repeat: ConflictingRepeat) extends RoundEvent
  final case class CallbackUnacknowledged(entry: UnacknowledgedEntry) extends RoundEvent
  final case class UnacknowledgedCleared(transactionId: String, cleared: Cleared) extends RoundEvent
  final case class RoundSettled(summary: SettlementSummary) extends RoundEvent
  final case class RoundCancelled(summary: SettlementSummary) extends RoundEvent

// ── Commands and replies ─────────────────────────────────────────────────────────────────────────

/**
 * What the endpoint did for one provider transaction, for the round to record. `opened` is the bet a
 * debit or buyin placed; `closed` the open bets a credit settled or a cancel rolled back. The round
 * decides from these whether it is now settled or cancelled.
 */
final case class RecordTransaction(
    opening: RoundOpening,
    tx: ProviderTransaction,
    opened: Option[OpenBet] = None,
    closed: Vector[String] = Vector.empty,
    stakeReturned: Long = 0,
    winReturned: Long = 0,
    clearedBy: Option[Cleared] = None
)

final case class TagFreeRounds(opening: RoundOpening, grant: String)

final case class RecordUnacknowledged(opening: RoundOpening, entry: UnacknowledgedEntry)

final case class ClearUnacknowledged(transactionId: String, cleared: Cleared)

/** The record's reply: the answer kept under the key, which is the first one when the key was answered before. */
final case class Recorded(answer: StoredAnswer, first: Boolean)

object RoundCodecs:
  given Serializer[RoundState]           = Codecs.serializer[RoundState]("round-state")
  given Serializer[RoundEvent]           = Codecs.serializer[RoundEvent]("round-event")
  given Serializer[RecordTransaction]    = Codecs.serializer[RecordTransaction]("record-transaction")
  given Serializer[TagFreeRounds]        = Codecs.serializer[TagFreeRounds]("tag-free-rounds")
  given Serializer[RecordUnacknowledged] = Codecs.serializer[RecordUnacknowledged]("record-unacknowledged")
  given Serializer[ClearUnacknowledged]  = Codecs.serializer[ClearUnacknowledged]("clear-unacknowledged")
  given Serializer[ConflictingRepeat]    = Codecs.serializer[ConflictingRepeat]("conflicting-repeat")
  given Serializer[Recorded]             = Codecs.serializer[Recorded]("recorded")
