package io.eitheror.casino.gameprovider.rounds

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializers}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.RoundSettled.Outcome
import io.eitheror.casino.gameprovider.provider.st8.Kind
import io.eitheror.casino.gameprovider.rounds.RoundEvent.*

import RoundCodecs.given
import Serializers.given

/**
 * One provider round (data-model.md, research R6), id `<provider>/<round id>`. It records every
 * transaction the provider sent with the answer given, from the transaction's bet workflow before that
 * answer is given (research R18; the workflow, not the round, answers a repeat, FR-011); the bets open in
 * the wallet, so a credit settles all of them (FR-014); and every unacknowledged callback until it is
 * cleared (FR-020). It never holds a balance of its own: the
 * balances it keeps are the answers the wallet gave (constitution II).
 */
final class Round(context: EventSourcedEntityContext) extends EventSourcedEntity[RoundState, RoundEvent]:

  def emptyState: RoundState = RoundState()

  def applyEvent(event: RoundEvent): RoundState = Round.fold(currentState, event)

  /**
   * Records what the bet workflow did for one transaction. A transaction recorded before is not recorded
   * twice (the workflow's step may run again after a crash): the first answer is replied. A credit, a payout or a cancel that leaves no bet open also settles or
   * cancels the round, publishing its summary (FR-019).
   */
  def record(cmd: RecordTransaction): Effect[Recorded] =
    val s   = currentState
    val key = cmd.tx.transactionId
    s.transaction(key) match
      case Some(first) => effects.reply(Recorded(first.answer, first = false))
      case None =>
        val opening = if s.isNew then Vector(RoundOpened(cmd.opening)) else Vector.empty
        val main    = Round.eventFor(cmd)
        val cleared = cmd.clearedBy.filter(_ => s.openUnacknowledged(key).isDefined).map(UnacknowledgedCleared(key, _)).toVector
        val events  = opening ++ (main +: cleared)
        val after   = events.foldLeft(s)(Round.fold)
        val closing = Round.closing(cmd, s, after).toVector
        effects.persistAll(events ++ closing).thenReply(_ => Recorded(cmd.tx.answer, first = true))

  /** Tags the round with the free-rounds grant a free debit belongs to, before the debit is answered (FR-013). */
  def tagFreeRounds(cmd: TagFreeRounds): Effect[String] =
    val s       = currentState
    val opening = if s.isNew then Vector(RoundOpened(cmd.opening)) else Vector.empty
    if s.freeRoundsGrant.contains(cmd.grant) then effects.reply(cmd.grant)
    else effects.persistAll(opening :+ FreeRoundsTagged(cmd.grant)).thenReply(_ => cmd.grant)

  def recordConflict(repeat: ConflictingRepeat): Effect[Boolean] =
    if currentState.isNew then effects.reply(false) else effects.persist(RepeatConflicted(repeat)).thenReply(_ => true)

  /** Records a callback the wallet did not acknowledge; a key already answered is not reopened. */
  def recordUnacknowledged(cmd: RecordUnacknowledged): Effect[Boolean] =
    val s = currentState
    if s.transaction(cmd.entry.transactionId).isDefined then effects.reply(false)
    else
      val opening = if s.isNew then Vector(RoundOpened(cmd.opening)) else Vector.empty
      effects.persistAll(opening :+ CallbackUnacknowledged(cmd.entry)).thenReply(_ => true)

  /** Clears an open entry by an operator's resolve; a replay or a retry clears through `record`. */
  def clear(cmd: ClearUnacknowledged): Effect[Boolean] =
    if currentState.openUnacknowledged(cmd.transactionId).isEmpty then effects.reply(false)
    else effects.persist(UnacknowledgedCleared(cmd.transactionId, cmd.cleared)).thenReply(_ => true)

  def get: ReadOnlyEffect[RoundState] = effects.reply(currentState)

object Round extends EventSourcedEntity.Companion[Round, RoundState, RoundEvent](
      componentId = ComponentId("round"),
      stateSerializer = RoundCodecs.given_Serializer_RoundState,
      eventSerializer = RoundCodecs.given_Serializer_RoundEvent
    ):
  def create(context: EventSourcedEntityContext) = new Round(context)

  val record               = command("record")(_.record)
  val tagFreeRounds        = command("tag-free-rounds")(_.tagFreeRounds)
  val recordConflict       = command("record-conflict")(_.recordConflict)
  val recordUnacknowledged = command("record-unacknowledged")(_.recordUnacknowledged)
  val clear                = command("clear")(_.clear)
  val get                  = query("get")(_.get)

  def entityId(provider: String, roundId: String): String = s"$provider/$roundId"

  private val DebitKinds  = Set(Kind.Debit, Kind.FreeDebit, Kind.CorrectionDebit)
  private val CreditKinds = Set(Kind.Credit, Kind.FreeCredit)

  def eventFor(cmd: RecordTransaction): RoundEvent =
    val tx = cmd.tx
    if tx.answer.status != "ok" then RefusalRecorded(tx)
    else if tx.kind == "cancel" then CancelRecorded(tx, cmd.closed, cmd.stakeReturned, cmd.winReturned)
    else if CreditKinds(tx.kind) then CreditRecorded(tx, cmd.closed)
    else if tx.kind == Kind.Payout then PayoutRecorded(tx)
    else if tx.kind == Kind.Buyin then BuyinRecorded(tx, cmd.opened)
    else DebitRecorded(tx, cmd.opened)

  /** Whether the transaction settles or cancels the round (FR-014, FR-015, FR-019), from the state after it. */
  def closing(cmd: RecordTransaction, before: RoundState, after: RoundState): Option[RoundEvent] =
    val tx = cmd.tx
    def summary(outcome: String) = SettlementSummary(after.provider, after.roundId, after.playerId, after.currency, after.gameCode,
      after.sessionId, after.totalStake, after.win, outcome, after.providerTransactionIds, after.walletTransactionIds,
      after.freeRoundsGrant, tx.receivedAt)
    if tx.answer.status != "ok" then None
    else if CreditKinds(tx.kind) then
      val actions = tx.movements.map(_.action).toSet
      val outcome =
        if after.freeRoundsGrant.isDefined then (if tx.amount > 0 then Outcome.Win else Outcome.Loss)
        else if actions(Movement.Win) then Outcome.Win
        else if actions(Movement.ProviderCredit) then Outcome.Credit
        else Outcome.Loss
      Some(RoundSettled(summary(outcome)))
    else if tx.kind == Kind.Payout then Some(RoundSettled(summary(Outcome.Credit)))
    else if tx.kind == "cancel" then
      val referencedDebit = tx.references.flatMap(before.transaction).exists(t => DebitKinds(t.kind))
      Option.when(referencedDebit && after.openBets.isEmpty && after.status != RoundState.Cancelled)(RoundCancelled(summary(Outcome.Cancelled)))
    else None

  /** Applies one event: the round's whole memory, so it is the same in the entity, a replay and a test. */
  def fold(s: RoundState, event: RoundEvent): RoundState =
    def recorded(tx: ProviderTransaction) = s.copy(transactions = s.transactions :+ tx)
    event match
      case RoundOpened(o) =>
        s.copy(provider = o.provider, roundId = o.roundId, playerId = o.playerId, currency = o.currency, gameCode = o.gameCode,
          sessionId = o.sessionId, status = RoundState.Open, openedAt = Some(o.at))
      case DebitRecorded(tx, opened) =>
        val r = recorded(tx); r.copy(openBets = r.openBets ++ opened, totalStake = r.totalStake + tx.amount)
      case BuyinRecorded(tx, opened) =>
        val r = recorded(tx); r.copy(openBets = r.openBets ++ opened, totalStake = r.totalStake + tx.amount)
      case CreditRecorded(tx, closed) =>
        val r = recorded(tx); r.copy(openBets = r.openBets.filterNot(b => closed.contains(b.walletKey)), win = r.win + tx.amount)
      case CancelRecorded(tx, closed, stakeReturned, winReturned) =>
        val r = recorded(tx)
        r.copy(openBets = r.openBets.filterNot(b => closed.contains(b.walletKey)), totalStake = r.totalStake - stakeReturned,
          win = r.win - winReturned)
      case PayoutRecorded(tx)  => val r = recorded(tx); r.copy(win = r.win + tx.amount)
      case RefusalRecorded(tx) => recorded(tx)
      case FreeRoundsTagged(grant) => s.copy(freeRoundsGrant = Some(grant))
      case RepeatConflicted(repeat) => s.copy(conflictingRepeats = s.conflictingRepeats :+ repeat)
      case CallbackUnacknowledged(entry) =>
        s.copy(unacknowledged = s.unacknowledged.filterNot(e => e.transactionId == entry.transactionId && e.open) :+ entry)
      case UnacknowledgedCleared(id, cleared) =>
        s.copy(unacknowledged = s.unacknowledged.map(e => if e.transactionId == id && e.open then e.copy(cleared = Some(cleared)) else e))
      case RoundSettled(summary)   => s.copy(status = RoundState.Settled, settledAt = Some(summary.at))
      case RoundCancelled(summary) => s.copy(status = RoundState.Cancelled, settledAt = Some(summary.at))
