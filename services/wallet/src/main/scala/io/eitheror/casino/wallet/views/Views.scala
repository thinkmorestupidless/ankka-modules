package io.eitheror.casino.wallet.views

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.contract.personal.Personal.given
import io.eitheror.casino.wallet.Ids
import io.eitheror.casino.wallet.entity.{Purpose, WalletEntity, WalletEvent}
import io.eitheror.casino.wallet.entity.WalletEvent.*
import io.eitheror.casino.wallet.funding.Parts

import java.time.{Instant, ZoneOffset}

/**
 * One transaction as the transactions read lists it (FR-029, User Story 6 scenario 3): the
 * original's row, with its `type` values. `reason` is the operator's free text, personal (FR-033).
 */
final case class TransactionRow(
    transactionId: String,
    walletId: String,
    playerId: String,
    currency: String,
    `type`: String,
    walletType: String,
    amount: Long,
    balanceBefore: Long,
    balanceAfter: Long,
    status: String,
    paymentReference: Option[String] = None,
    roundKey: Option[String] = None,
    awardRef: Option[String] = None,
    reason: Option[Personal[String]] = None,
    terminal: Option[String] = None,
    paymentProvider: Option[String] = None,
    actorId: Option[String] = None,
    createdAt: String,
    createdAtMillis: Long,
    /** For a bet: its key, so a win or rollback can find its row. */
    betKey: Option[String] = None,
    /**
     * Spec 025: the movement's parts (0 where it moves no real balance) and both pools after it, as
     * the original's transaction carries `funded_*`, `won_*`, `from_*` and `to_*`.
     */
    depositPart: Long = 0L,
    convertedPart: Long = 0L,
    depositPoolAfter: Long = 0L,
    convertedPoolAfter: Long = 0L,
    /** Spec 022: the order, trade, account or position a ledger move is about. */
    reference: Option[String] = None
)

/** The original's transaction types. */
object TransactionType:
  val Deposit            = "deposit"
  val Bet                = "bet"
  val Win                = "win"
  val Rollback           = "rollback"
  val Loss               = "loss"
  val BonusCredit        = "bonus_credit"
  val BonusCancel        = "bonus_cancel"
  val BonusCapVoid       = "bonus_cap_void"
  val Withdrawal         = "withdrawal"
  val WithdrawalReturn   = "withdrawal_return"
  val WithdrawalReversal = "withdrawal_reversal"
  val AdminAdjust        = "admin_adjust"
  val GameProviderCredit = "game_provider_credit"
  // spec 022
  val Reservation        = "reservation"
  val ReservationRelease = "reservation_release"
  val SettlementDebit    = "settlement_debit"
  val SettlementCredit   = "settlement_credit"
  val ResolutionPayment  = "resolution_payment"
  val AccountDebit       = "account_debit"
  val AccountCredit      = "account_credit"

  /** The types spec 022 adds, which the transactions read accepts in its `type` filter. */
  val Ledger: Set[String] =
    Set(Reservation, ReservationRelease, SettlementDebit, SettlementCredit, ResolutionPayment, AccountDebit, AccountCredit)

/** The rows a wallet event writes, as pure values (tested without a runtime). */
object TransactionRows:

  def of(walletId: String, event: WalletEvent): Vector[TransactionRow] =
    val playerId = walletId.takeWhile(_ != '/')
    val currency = walletId.drop(walletId.indexOf('/') + 1)
    def row(txId: String, kind: String, walletType: String, amount: Long, before: Long, after: Long, status: String, at: Instant) =
      TransactionRow(txId, walletId, playerId, currency, kind, walletType, amount, before, after, status,
        createdAt = at.toString, createdAtMillis = at.toEpochMilli)

    // Spec 025: the pools after the event, and each row's parts.
    def pooled(rows: Vector[TransactionRow], depositAfter: Long, convertedAfter: Long, parts: Parts*): Vector[TransactionRow] =
      rows.zipWithIndex.map { (r, i) =>
        val p = parts.lift(i).getOrElse(Parts.zero)
        r.copy(depositPart = p.deposit, convertedPart = p.converted, depositPoolAfter = depositAfter, convertedPoolAfter = convertedAfter)
      }

    event match
      case e: DepositConfirmed  => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter, e.parts)
      case e: BetPlaced         => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter, e.bet.parts.getOrElse(Parts.zero))
      case e: WinCredited       => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter, e.parts)
      case e: BetRolledBack     => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter, e.parts)
      case e: RoundSettledAsLoss => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter)
      case e: RestrictedFundsAwarded   => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter)
      case e: RestrictedFundsForfeited => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter)
      case e: RestrictedFundsCancelled => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter, e.parts)
      case e: HoldPlaced =>
        // The withdrawal row is the paid amount's; the void row, when there is one, the void's.
        val rows = plain(walletId, e, row)
        if e.voided > 0 then pooled(rows, e.depositAfter, e.convertedAfter, e.paidParts, e.voidedParts)
        else pooled(rows, e.depositAfter, e.convertedAfter, e.paidParts)
      case e: HoldReleased       => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter, e.parts)
      case e: WithdrawalReversed => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter, e.parts)
      case e: AdminAdjusted      => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter, e.parts)
      case e: ProviderCredited   => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter, e.parts)
      case e: ReservationPlaced   => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter)
      case e: ReservationReleased => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter)
      case e: SettlementDebited   => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter, e.parts)
      case e: SettlementCredited  => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter, e.parts)
      case e: MovedToAccount      => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter, e.parts)
      case e: MovedFromAccount    => pooled(plain(walletId, e, row), e.depositAfter, e.convertedAfter, e.parts)
      case _                     => Vector.empty

  private type Row = (String, String, String, Long, Long, Long, String, Instant) => TransactionRow

  /** The rows as the original writes them, before the pools. */
  private def plain(walletId: String, event: WalletEvent, row: Row): Vector[TransactionRow] =
    event match
      case e: DepositConfirmed =>
        Vector(row(e.transactionId, TransactionType.Deposit, "real", e.amount, e.realAfter - e.amount, e.realAfter, "completed", e.at)
          .copy(paymentReference = Some(e.key), terminal = Some(e.terminal), paymentProvider = Some(e.paymentProvider)))
      case e: BetPlaced =>
        Vector(row(e.bet.transactionId, TransactionType.Bet, e.bet.walletType, e.bet.amount, e.balanceBefore, e.balanceAfter, "open", e.at)
          .copy(roundKey = Some(e.bet.roundKey), betKey = Some(e.bet.key)))
      case e: WinCredited =>
        Vector(row(e.transactionId, TransactionType.Win, e.walletType, e.amount, e.realAfter - e.realShare, e.realAfter, "settled", e.at)
          .copy(roundKey = Some(e.roundKey)))
      case e: BetRolledBack =>
        Vector(row(e.transactionId, TransactionType.Rollback, "real", e.amount, e.realAfter - e.realRestored, e.realAfter, "completed", e.at)
          .copy(roundKey = Some(e.roundKey)))
      case e: RoundSettledAsLoss =>
        Vector(row(e.transactionId, TransactionType.Loss, "", 0L, e.realAfter, e.realAfter, "settled", e.at).copy(roundKey = Some(e.roundKey)))
      case e: RestrictedFundsAwarded =>
        Vector(row(e.transactionId, TransactionType.BonusCredit, "bonus", e.awarded.record.balance, e.restrictedAfter - e.awarded.record.balance,
          e.restrictedAfter, "completed", e.at).copy(awardRef = Some(e.awarded.grantRef)))
      case e: RestrictedFundsForfeited =>
        // The original writes a forfeit as a voided bonus_credit (the fixtures of forfeit-bonus).
        e.forfeits.map(f => row(s"${e.transactionId}/${f.recordId}", TransactionType.BonusCredit, "bonus", f.amount,
          e.restrictedAfter + f.amount, e.restrictedAfter, "voided", e.at)
          .copy(awardRef = e.recordUpdates.find(_.id == f.recordId).map(_.grantRef)))
      case e: RestrictedFundsCancelled =>
        Vector(row(e.transactionId, TransactionType.BonusCancel, "bonus", e.balanceBefore + e.clawback, e.balanceBefore, e.restrictedAfter,
          "completed", e.at).copy(awardRef = e.forfeits.headOption.flatMap(f => e.recordUpdates.find(_.id == f.recordId).map(_.grantRef))))
      case e: HoldPlaced =>
        val hold = row(e.transactionId, TransactionType.Withdrawal, "real", e.amount, e.realAfter + e.amount, e.realAfter, "pending", e.at)
        val void = Option.when(e.voided > 0)(row(s"${e.transactionId}/void", TransactionType.BonusCapVoid, "real", e.voided, e.realAfter,
          e.realAfter, "completed", e.at))
        // A hold's forfeit is written as a bonus_cancel, as the original's create-withdrawal-request does.
        val forfeits = e.forfeits.map(f => row(s"${e.transactionId}/${f.recordId}", TransactionType.BonusCancel, "bonus", f.amount,
          f.amount, 0L, "completed", e.at))
        hold +: (void.toVector ++ forfeits)
      case e: HoldReleased =>
        Vector(row(e.transactionId, TransactionType.WithdrawalReturn, "real", e.amount, e.realAfter - e.amount, e.realAfter, e.releasedAs, e.at))
      case e: WithdrawalReversed =>
        Vector(row(e.transactionId, TransactionType.WithdrawalReversal, "real", e.amount, e.realAfter - e.amount, e.realAfter, "completed", e.at))
      case e: AdminAdjusted =>
        val (before, after) = if e.target == "restricted" then (e.restrictedAfter - e.amount, e.restrictedAfter) else (e.realAfter - e.amount, e.realAfter)
        Vector(row(e.transactionId, TransactionType.AdminAdjust, if e.target == "restricted" then "bonus" else "real", e.amount, before, after,
          "completed", e.at).copy(reason = Some(e.reason), actorId = Some(e.actorId)))
      case e: ProviderCredited =>
        val (before, after) = if e.landedOn == "restricted" then (e.restrictedAfter - e.amount, e.restrictedAfter) else (e.realAfter - e.amount, e.realAfter)
        Vector(row(e.transactionId, TransactionType.GameProviderCredit, if e.landedOn == "restricted" then "bonus" else "real", e.amount,
          before, after, "completed", e.at).copy(actorId = Some(e.actorId)))
      case e: ReservationPlaced =>
        Vector(row(e.transactionId, TransactionType.Reservation, "real", e.amount, e.realAfter, e.realAfter, "completed", e.at)
          .copy(reference = Some(e.orderId)))
      case e: ReservationReleased =>
        Vector(row(e.transactionId, TransactionType.ReservationRelease, "real", e.amount, e.realAfter, e.realAfter, "completed", e.at)
          .copy(reference = Some(e.orderId)))
      case e: SettlementDebited =>
        Vector(row(e.transactionId, TransactionType.SettlementDebit, "real", e.amount, e.realAfter + e.amount, e.realAfter, "completed", e.at)
          .copy(reference = Some(e.tradeId)))
      case e: SettlementCredited =>
        Vector(row(e.transactionId, TransactionType.SettlementCredit, "real", e.amount, e.realAfter - e.amount, e.realAfter, "completed", e.at)
          .copy(reference = Some(e.tradeId)))
      case e: MovedToAccount =>
        Vector(row(e.transactionId, TransactionType.AccountDebit, "real", e.amount, e.realAfter + e.amount, e.realAfter, "completed", e.at)
          .copy(reference = Some(s"${e.accountKind}/${e.accountName}/${e.reference}")))
      case e: MovedFromAccount =>
        val kind = if e.purpose == Purpose.ResolutionPayment then TransactionType.ResolutionPayment else TransactionType.AccountCredit
        Vector(row(e.transactionId, kind, "real", e.amount, e.realAfter - e.amount, e.realAfter, "completed", e.at)
          .copy(reference = Some(s"${e.accountKind}/${e.accountName}/${e.reference}")))
      case _ => Vector.empty

  /** The bets a settlement closes, and the status their rows take. */
  def settled(event: WalletEvent): Option[(Vector[String], String)] = event match
    case e: WinCredited        => Some((e.settledBets, "settled"))
    case e: BetRolledBack      => Some((e.voidedBets, "voided"))
    case e: RoundSettledAsLoss => Some((e.lostBets, "loss"))
    case _                     => None

/** Every transaction of every wallet, one row each, queried by wallet, type, wallet type and date (FR-029). */
final class TransactionsView extends KeyedView[TransactionRow]:
  def onWallet(event: WalletEvent, change: Change): Effect =
    val walletId = change.subject
    val rows     = TransactionRows.of(walletId, event).map(r => s"${r.walletId}/${r.transactionId}" -> r)
    val statuses = TransactionRows.settled(event).toVector.flatMap { (betKeys, status) =>
      betKeys.flatMap { k =>
        val key = s"$walletId/${Ids.transactionId(walletId, k)}"
        change.rows.get(key).map(r => key -> r.copy(status = status))
      }
    }
    if rows.isEmpty && statuses.isEmpty then effects.ignore() else effects.updateRows(rows ++ statuses)

object TransactionsView extends KeyedView.Companion[TransactionsView, TransactionRow](
      ComponentId("transactions"),
      Codecs.serializer[TransactionRow]("wallet.transaction-row")
    ):
  val wallets = source(ChangeSource.eventsOf(WalletEntity))(_.onWallet)
  def create(ctx: ViewComponentContext) = new TransactionsView

/** One row per wallet, type and UTC day: the total and the count, summed over a range by the reader. */
final case class SumRow(walletId: String, `type`: String, day: String, total: Long, count: Long)

final class SumsView extends KeyedView[SumRow]:
  def onWallet(event: WalletEvent, change: Change): Effect =
    val rows = TransactionRows.of(change.subject, event)
    if rows.isEmpty then effects.ignore()
    else
      // Several rows of one event may fall on the same key; fold them before reading what is stored.
      val byKey = rows.groupBy(r => (r.`type`, Instant.ofEpochMilli(r.createdAtMillis).atZone(ZoneOffset.UTC).toLocalDate.toString))
      effects.updateRows(byKey.toVector.map { case ((kind, day), rs) =>
        val key    = s"${change.subject}/$kind/$day"
        val stored = change.rows.get(key).getOrElse(SumRow(change.subject, kind, day, 0L, 0L))
        key -> stored.copy(total = stored.total + rs.map(_.amount).sum, count = stored.count + rs.size)
      })

object SumsView extends KeyedView.Companion[SumsView, SumRow](
      ComponentId("transaction-sums"),
      Codecs.serializer[SumRow]("wallet.sum-row")
    ):
  val wallets = source(ChangeSource.eventsOf(WalletEntity))(_.onWallet)
  def create(ctx: ViewComponentContext) = new SumsView

/** Lifetime totals per wallet (FR-029): deposited, wagered and won. */
final case class LifetimeTotals(walletId: String, deposited: Long, wagered: Long, won: Long)

final class LifetimeTotalsView extends View[WalletEvent, LifetimeTotals]:
  def onChange(event: WalletEvent): Effect =
    val current = rowState.getOrElse(LifetimeTotals(updateContext.subject, 0L, 0L, 0L))
    event match
      case e: DepositConfirmed => effects.updateRow(current.copy(deposited = current.deposited + e.amount))
      case e: BetPlaced        => effects.updateRow(current.copy(wagered = current.wagered + e.bet.amount))
      case e: BetRolledBack    => effects.updateRow(current.copy(wagered = current.wagered - e.amount))
      case e: WinCredited      => effects.updateRow(current.copy(won = current.won + e.amount))
      case _                   => effects.ignore()

object LifetimeTotalsView
    extends View.Companion[LifetimeTotalsView, WalletEvent, LifetimeTotals](
      componentId = ComponentId("lifetime-totals"),
      source = ChangeSource.eventsOf(WalletEntity),
      rowSerializer = Codecs.serializer[LifetimeTotals]("wallet.lifetime-totals")
    ):
  def create(ctx: ViewComponentContext) = new LifetimeTotalsView
