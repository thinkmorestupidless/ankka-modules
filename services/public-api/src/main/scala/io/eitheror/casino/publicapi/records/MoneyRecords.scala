package io.eitheror.casino.publicapi.records

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.publicapi.PublicApiCodecs
import io.eitheror.casino.publicapi.PublicApiCodecs.given_Serializer_Boolean
import io.eitheror.casino.publicapi.wire.Enums

import java.time.Instant

/** A bet (data-model.md `BetRecord`, by `bet_placed`'s `transactionId`). Its status follows the round key's later facts. */
final case class BetState(
    id: String = "",
    playerId: String = "",
    roundKey: String = "",
    roundId: Option[String] = None,
    sessionId: Option[String] = None,
    gameId: Option[String] = None,
    amountCents: Long = 0L,
    currency: String = "",
    fundedFrom: String = "",
    status: String = "",
    placedAt: Option[Instant] = None,
    applied: Vector[String] = Vector.empty
)

/**
 * A transaction (data-model.md `TransactionRecord`): one per `money.transactions` fact of a family the contract has a
 * type for, plus the secondary rows of contracts/fields.md §6. `typeWord` is the original's word, mapped when served.
 */
final case class TransactionState(
    id: String = "",
    playerId: String = "",
    typeWord: String = "",
    amountCents: Long = 0L,
    currency: String = "",
    walletType: Option[String] = None,
    status: String = "",
    createdAt: Option[Instant] = None,
    roundId: Option[String] = None,
    gameId: Option[String] = None,
    paymentRef: Option[String] = None,
    withdrawalRequestId: Option[String] = None,
    applied: Vector[String] = Vector.empty
)

/** The bets of one wallet round key (`<playerId>/<roundKey>`), so a win, rollback or loss finds them. */
final case class RoundKeyState(betIds: Vector[String] = Vector.empty, gameId: Option[String] = None)

/** The WITHDRAWAL rows of one payout, and whether `withdrawal_completed` has arrived for it. */
final case class PayoutState(txIds: Vector[String] = Vector.empty, completed: Boolean = false)

/** What one `money.transactions` fact makes (contracts/fields.md §2): rows, a bet, a status for the round key's bets. */
final case class MoneyChange(
    rows: Vector[TransactionState] = Vector.empty,
    bet: Option[BetState] = None,
    roundKey: Option[String] = None,
    betsBecome: Option[String] = None,
    payout: Option[String] = None,
    award: Option[AwardMove] = None,
    progress: Vector[f.RecordProgress] = Vector.empty
)

/** A restricted-funds award's requirement, from `restricted_funds_awarded`. */
final case class AwardMove(awardId: String, requirementCents: Long)

object MoneyFold:
  private def gameRound(id: Option[String]): Option[String] = id.filter(_.nonEmpty).map(Records.roundId)
  private def nonEmpty(s: String): Option[String]           = Option(s).filter(_.nonEmpty)
  def roundKey(playerId: String, key: String): String       = s"$playerId/$key"

  private def row(x: f.MoneyTransactionsFact, family: String, amount: Long, walletType: Option[String], status: String,
      reason: Option[String] = None): TransactionState =
    TransactionState(x.transactionId, x.playerId, Enums.typeWordOf(family, reason).getOrElse(""), amount, x.currency.code, walletType,
      status, Some(x.occurredAt))

  private def secondary(x: f.MoneyTransactionsFact, family: String, suffix: String): String =
    Records.uuid3(s"${x.transactionId}/$family/$suffix")

  def apply(message: f.MoneyTransactionsMessage): MoneyChange = message match
    case x: f.DepositConfirmed =>
      MoneyChange(rows = Vector(row(x, "deposit_confirmed", x.amountCents.value, Some("MAIN"), "completed").copy(paymentRef = nonEmpty(x.paymentReference))))
    case x: f.BetPlaced =>
      val key = roundKey(x.playerId, x.roundId)
      val bet = BetState(x.transactionId, x.playerId, key, gameRound(x.gameRoundId), x.sessionId.filter(_.nonEmpty), nonEmpty(x.gameId),
        x.amountCents.value, x.currency.code, x.walletType, "open", Some(x.occurredAt))
      MoneyChange(rows = Vector(row(x, "bet_placed", x.amountCents.value, Enums.walletType(x.walletType), "open")
          .copy(roundId = bet.roundId, gameId = bet.gameId)), bet = Some(bet), roundKey = Some(key), progress = x.records.getOrElse(Vector.empty))
    case x: f.WinCredited =>
      MoneyChange(rows = Vector(row(x, "win_credited", x.amountCents.value, Enums.walletType(x.walletType), "settled")
          .copy(roundId = gameRound(x.gameRoundId), gameId = nonEmpty(x.gameId))), roundKey = Some(roundKey(x.playerId, x.roundId)),
        betsBecome = Some("settled"), progress = x.records.getOrElse(Vector.empty))
    case x: f.BetRolledBack =>
      MoneyChange(rows = Vector(row(x, "bet_rolled_back", x.amountCents.value, Some("MAIN"), "completed").copy(roundId = gameRound(x.gameRoundId))),
        roundKey = Some(roundKey(x.playerId, x.roundId)), betsBecome = Some("voided"), progress = x.records.getOrElse(Vector.empty))
    case x: f.RoundSettledAsLoss =>
      MoneyChange(rows = Vector(row(x, "round_settled_as_loss", 0L, Some("MAIN"), "settled").copy(roundId = gameRound(x.gameRoundId))),
        roundKey = Some(roundKey(x.playerId, x.roundId)), betsBecome = Some("loss"))
    case x: f.RestrictedFundsAwarded =>
      MoneyChange(rows = Vector(row(x, "restricted_funds_awarded", x.amountCents.value, Some("BONUS"), "completed")),
        award = Some(AwardMove(x.awardId, x.wageringRequirement.value)), progress = x.records.getOrElse(Vector.empty))
    case x: f.RestrictedFundsConverted =>
      MoneyChange(rows = Vector(row(x, "restricted_funds_converted", x.amountCents.value, Some("MAIN"), "settled")
          .copy(id = secondary(x, "restricted_funds_converted", x.awardId))), progress = x.records.getOrElse(Vector.empty))
    case x: f.RestrictedFundsForfeited =>
      val cancel = x.reason == "cancel" || x.reason == "hold"
      MoneyChange(rows = Vector(row(x, "restricted_funds_forfeited", x.amountCents.value, Some("BONUS"), if cancel then "completed" else "voided",
          Some(x.reason)).copy(id = secondary(x, "restricted_funds_forfeited", x.awardId))), progress = x.records.getOrElse(Vector.empty))
    case x: f.RestrictedFundsClosed => MoneyChange(progress = x.records.getOrElse(Vector.empty))
    case x: f.AdminAdjusted =>
      MoneyChange(rows = Vector(row(x, "admin_adjusted", x.amount.value, Enums.side(x.target), "completed")),
        progress = x.records.getOrElse(Vector.empty))
    case x: f.HoldPlaced =>
      val hold = row(x, "hold_placed", x.amountCents.value, Some("MAIN"), "pending").copy(withdrawalRequestId = nonEmpty(x.withdrawalRequestId))
      val void = Option.when(x.voidedCents.value > 0)(
        row(x, "hold_placed", x.voidedCents.value, Some("MAIN"), "completed").copy(id = secondary(x, "hold_placed", "void"), typeWord = "bonus_cap_void"))
      MoneyChange(rows = hold +: void.toVector, payout = hold.withdrawalRequestId, progress = x.records.getOrElse(Vector.empty))
    case x: f.HoldReleased =>
      MoneyChange(rows = Vector(row(x, "hold_released", x.amountCents.value, Some("MAIN"), "released").copy(withdrawalRequestId = nonEmpty(x.withdrawalRequestId))))
    case x: f.WalletWithdrawalReversed =>
      MoneyChange(rows = Vector(row(x, "withdrawal_reversed", x.amountCents.value, Some("MAIN"), "completed")
          .copy(withdrawalRequestId = nonEmpty(x.withdrawalRequestId))))
    case x: f.ProviderCredited =>
      MoneyChange(rows = Vector(row(x, "provider_credited", x.amountCents.value, Enums.side(x.landedOn), "completed")),
        progress = x.records.getOrElse(Vector.empty))
    case _ => MoneyChange() // spec 022's families, account_adjusted: not the casino's player transactions (fields.md §6)

final case class TxPut(factId: String, row: TransactionState)
final case class TxStatus(factId: String, status: String)
final case class BetPut(factId: String, bet: BetState)
final case class BetStatus(factId: String, status: String)
final case class AddBet(betId: String, gameId: Option[String])
final case class AddHold(txId: String)
final case class Complete(payoutId: String)

final class TransactionRecord(@scala.annotation.unused context: KeyValueEntityContext) extends RecordEntity[TransactionState]:
  def emptyState: TransactionState                                   = TransactionState()
  protected def applied(s: TransactionState): Vector[String]        = s.applied
  protected def remembered(s: TransactionState, ids: Vector[String]) = s.copy(applied = ids)

  /** The row a fact made; a later status (a bet settled, a payout completed) is kept. */
  def put(c: TxPut): Effect[Boolean] =
    once(c.factId)(s => if s.id.isEmpty then c.row else c.row.copy(status = s.status, gameId = c.row.gameId.orElse(s.gameId)))
  def status(c: TxStatus): Effect[Boolean] = once(c.factId)(s => s.copy(status = c.status))
  def fill(c: TxPut): Effect[Boolean]      = once(c.factId)(s => s.copy(gameId = s.gameId.orElse(c.row.gameId), roundId = s.roundId.orElse(c.row.roundId)))
  def get: ReadOnlyEffect[TransactionState] = effects.reply(currentState)

object TransactionRecord extends KeyValueEntity.Companion[TransactionRecord, TransactionState](
      componentId = ComponentId("transaction-record"),
      stateSerializer = PublicApiCodecs.serializer[TransactionState]("transaction-record")
    ):
  given Serializer[TxPut]            = PublicApiCodecs.serializer[TxPut]("tx-put")
  given Serializer[TxStatus]         = PublicApiCodecs.serializer[TxStatus]("tx-status")
  given Serializer[TransactionState] = PublicApiCodecs.serializer[TransactionState]("transaction-reply")

  def create(context: KeyValueEntityContext) = new TransactionRecord(context)

  val put    = command("put")(_.put)
  val status = command("status")(_.status)
  val fill   = command("fill")(_.fill)
  val get    = query("get")(_.get)

final class BetRecord(@scala.annotation.unused context: KeyValueEntityContext) extends RecordEntity[BetState]:
  def emptyState: BetState                                   = BetState()
  protected def applied(s: BetState): Vector[String]        = s.applied
  protected def remembered(s: BetState, ids: Vector[String]) = s.copy(applied = ids)

  def put(c: BetPut): Effect[Boolean]       = once(c.factId)(s => if s.id.isEmpty then c.bet else c.bet.copy(status = s.status))
  def status(c: BetStatus): Effect[Boolean] = once(c.factId)(s => s.copy(status = c.status))
  def get: ReadOnlyEffect[BetState]         = effects.reply(currentState)

object BetRecord extends KeyValueEntity.Companion[BetRecord, BetState](
      componentId = ComponentId("bet-record"),
      stateSerializer = PublicApiCodecs.serializer[BetState]("bet-record")
    ):
  given Serializer[BetPut]    = PublicApiCodecs.serializer[BetPut]("bet-put")
  given Serializer[BetStatus] = PublicApiCodecs.serializer[BetStatus]("bet-status")
  given Serializer[BetState]  = PublicApiCodecs.serializer[BetState]("bet-reply")

  def create(context: KeyValueEntityContext) = new BetRecord(context)

  val put    = command("put")(_.put)
  val status = command("status")(_.status)
  val get    = query("get")(_.get)

final class BetsByRoundKey(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[RoundKeyState]:
  def emptyState: RoundKeyState = RoundKeyState()
  def add(c: AddBet): Effect[RoundKeyState] =
    val next = currentState.copy(betIds = (currentState.betIds :+ c.betId).distinct, gameId = currentState.gameId.orElse(c.gameId))
    if next == currentState then effects.reply(currentState) else effects.updateState(next).thenReply(identity)
  def get: ReadOnlyEffect[RoundKeyState] = effects.reply(currentState)

object BetsByRoundKey extends KeyValueEntity.Companion[BetsByRoundKey, RoundKeyState](
      componentId = ComponentId("bets-by-round-key"),
      stateSerializer = PublicApiCodecs.serializer[RoundKeyState]("bets-by-round-key")
    ):
  given Serializer[AddBet]        = PublicApiCodecs.serializer[AddBet]("add-bet")
  given Serializer[RoundKeyState] = PublicApiCodecs.serializer[RoundKeyState]("round-key-reply")

  def create(context: KeyValueEntityContext) = new BetsByRoundKey(context)

  val add = command("add")(_.add)
  val get = query("get")(_.get)

final class WithdrawalsByRequest(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[PayoutState]:
  def emptyState: PayoutState = PayoutState()
  def add(c: AddHold): Effect[PayoutState] =
    val next = currentState.copy(txIds = (currentState.txIds :+ c.txId).distinct)
    if next == currentState then effects.reply(currentState) else effects.updateState(next).thenReply(identity)
  def complete(@scala.annotation.unused c: Complete): Effect[PayoutState] =
    if currentState.completed then effects.reply(currentState) else effects.updateState(currentState.copy(completed = true)).thenReply(identity)

object WithdrawalsByRequest extends KeyValueEntity.Companion[WithdrawalsByRequest, PayoutState](
      componentId = ComponentId("withdrawals-by-request"),
      stateSerializer = PublicApiCodecs.serializer[PayoutState]("withdrawals-by-request")
    ):
  given Serializer[AddHold]     = PublicApiCodecs.serializer[AddHold]("add-hold")
  given Serializer[Complete]    = PublicApiCodecs.serializer[Complete]("complete")
  given Serializer[PayoutState] = PublicApiCodecs.serializer[PayoutState]("payout-reply")

  def create(context: KeyValueEntityContext) = new WithdrawalsByRequest(context)

  val add      = command("add")(_.add)
  val complete = command("complete")(_.complete)
