package io.eitheror.casino.gameprovider.rounds

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.gameprovider.clients.*
import io.eitheror.casino.gameprovider.provider.st8.{Kind, Status}

import java.time.Instant
import scala.util.Try

/**
 * The money rules of FR-012 to FR-016, run by the bet workflow's `wallet` step: the wallet calls a
 * transaction makes and what the round is to record of them. A `Left` is a wallet that could not be
 * reached or did not acknowledge, which leaves the transaction unacknowledged (FR-020); every refusal the
 * provider has a word for is a `Right`, recorded and answered.
 */
final class MoneyMoves(provider: String, components: ComponentClient, wallet: WalletClient, now: () => Instant):
  import MoneyMoves.*

  def move(job: BetJob): Either[String, Moved] =
    Try(run(job)).fold(e => Left(s"${e.getClass.getSimpleName}: ${e.getMessage}"), identity)

  private def run(job: BetJob): Either[String, Moved] =
    job.callback match
      case "cancel" => cancel(job)
      case "credit" => credit(job)
      case "payout" => payout(job)
      case "buyin"  => bet(job, buyin = true)
      case _        => debit(job)

  private def round(entity: String) = components.forEventSourcedEntity(EntityId(entity))

  private def balance(job: BetJob): Either[String, Long] = wallet.balance(job.player, job.currency).map(_.total)

  private def after(moved: Option[Balances], job: BetJob): Either[String, Long] =
    moved.map(b => Right(b.total)).getOrElse(balance(job))

  private def ok(job: BetJob, balance: Long) = StoredAnswer(Status.Ok, Some(balance), Some(job.currency))

  private def tx(job: BetJob, amount: Long, movements: Vector[Movement], answer: StoredAnswer, references: Option[String] = None) =
    ProviderTransaction(job.key, recordedKind(job), amount, references, movements, answer, now())

  /** The kind the round records: the callback's own for a buyin, payout and cancel, else the provider's kind. */
  private def recordedKind(job: BetJob): String = job.callback match
    case "cancel" => "cancel"
    case "buyin"  => Kind.Buyin
    case "payout" => Kind.Payout
    case _        => job.kind

  // ── debit and buyin (FR-012, FR-013, FR-016) ──────────────────────────────────────────────────────

  private def debit(job: BetJob): Either[String, Moved] =
    if job.amount == 0 || job.kind == Kind.FreeDebit then
      // A zero or free debit places no bet; the round is tagged with the session's free rounds first (FR-013).
      job.freeRoundsGrant.foreach(grant => round(job.roundEntity).call(Round.tagFreeRounds).invoke(TagFreeRounds(job.opening, grant)): Unit)
      balance(job).map(b => Moved(RecordTransaction(job.opening, tx(job, 0, Vector.empty, ok(job, b))), countsBet = true))
    else bet(job, buyin = false)

  private def bet(job: BetJob, buyin: Boolean): Either[String, Moved] =
    wallet.placeBet(job.player, job.currency, job.key, job.key, job.gameId, job.sessionId, job.amount,
      job.kind == Kind.CorrectionDebit, job.gameCategory, provider) match
      case WalletAnswer.Moved(id, balances) =>
        after(balances, job).map { b =>
          Moved(RecordTransaction(job.opening, tx(job, job.amount, Vector(Movement(Movement.Bet, job.key, Some(job.key), job.amount, id)), ok(job, b)),
            opened = Some(OpenBet(job.key, job.key, job.amount, job.key))), countsBet = !buyin)
        }
      case WalletAnswer.Refused(_, refusal, _) if refusal.word.isDefined =>
        val b = balance(job).toOption
        Right(Moved(RecordTransaction(job.opening, tx(job, job.amount, Vector.empty, StoredAnswer(refusal.word.get, b, Some(job.currency))))))
      case WalletAnswer.Refused(code, _, message) => Left(s"$code $message")
      case WalletAnswer.Unacknowledged(error)     => Left(error)

  // ── credit (FR-014) ──────────────────────────────────────────────────────────────────────────────

  private def credit(job: BetJob): Either[String, Moved] =
    // The round's open bets: every debit answered before this credit was recorded before its answer (R18).
    val state = round(job.roundEntity).call(Round.get).invoke()
    val plan  = Settlement.credit(state.openBets, job.key, job.amount, state.freeRoundsGrant.isDefined)
    settle(plan, job).flatMap { settled =>
      after(settled.lastBalances, job).map(b =>
        Moved(RecordTransaction(job.opening, tx(job, job.amount, settled.movements, ok(job, b)), closed = settled.closed)))
    }

  private final case class Settled(movements: Vector[Movement], closed: Vector[String], lastBalances: Option[Balances])

  /** Runs a credit's wallet calls in order; one that is not acknowledged leaves the credit unacknowledged. */
  private def settle(plan: Vector[WalletCall], job: BetJob): Either[String, Settled] =
    val (player, currency, creditId) = (job.player, job.currency, job.key)
    plan.foldLeft[Either[String, Settled]](Right(Settled(Vector.empty, Vector.empty, None))) { (acc, call) =>
      acc.flatMap { done =>
        call match
          case WalletCall.Win(key, walletRound, win, bet) =>
            wallet.creditWin(player, currency, key, walletRound, win) match
              case WalletAnswer.Moved(id, b) => Right(Settled(done.movements :+ Movement(Movement.Win, key, Some(walletRound), win, id), done.closed :+ bet, b))
              case WalletAnswer.Refused(_, WalletRefusal.RoundNotFound, _) =>
                // The wallet no longer holds the bet: the win is paid as a provider credit under the credit's own id.
                wallet.providerCredit(player, currency, creditId, win, creditId, "credit for a bet the wallet no longer holds") match
                  case WalletAnswer.Moved(id, b) => Right(Settled(done.movements :+ Movement(Movement.ProviderCredit, creditId, None, win, id), done.closed :+ bet, b))
                  case other                     => Left(other.toString)
              case other => Left(other.toString)
          case WalletCall.Loss(key, walletRound, bet) =>
            wallet.settleLoss(player, currency, key, walletRound) match
              case WalletAnswer.Moved(id, b) => Right(Settled(done.movements :+ Movement(Movement.Loss, key, Some(walletRound), 0, id), done.closed :+ bet, b))
              case WalletAnswer.Refused(_, WalletRefusal.RoundNotFound, _) => Right(done.copy(closed = done.closed :+ bet))
              case other => Left(other.toString)
          case WalletCall.ProviderCredit(key, credit) =>
            wallet.providerCredit(player, currency, key, credit, key, "credit with no open bet") match
              case WalletAnswer.Moved(id, b) => Right(Settled(done.movements :+ Movement(Movement.ProviderCredit, key, None, credit, id), done.closed, b))
              case other                     => Left(other.toString)
      }
    }

  // ── cancel (FR-015) ──────────────────────────────────────────────────────────────────────────────

  private def cancel(job: BetJob): Either[String, Moved] =
    val cancelId = job.key
    val refId    = job.references.get
    val state    = round(job.roundEntity).call(Round.get).invoke()
    state.transaction(refId) match
      case None =>
        // The referenced transaction never reached its round (it is still unacknowledged): nothing to cancel yet.
        balance(job).map(b => Moved(RecordTransaction(job.opening,
          tx(job, 0, Vector.empty, StoredAnswer(Status.TransactionNotFound, Some(b), Some(job.currency)), Some(refId)))))
      case Some(referenced) =>
        def done(movements: Vector[Movement], closed: Vector[String], stake: Long, win: Long, balances: Option[Balances]) =
          after(balances, job).map(b => Moved(RecordTransaction(job.opening,
            tx(job, referenced.amount, movements, ok(job, b), Some(refId)), closed = closed, stakeReturned = stake, winReturned = win)))
        if referenced.answer.status != Status.Ok || state.cancelled(refId) then done(Vector.empty, Vector.empty, 0, 0, None)
        else if Kind.isCredit(referenced.kind) || referenced.kind == Kind.Payout then
          // A cancelled credit is debited back as a bet under the cancel id, allowed below zero.
          wallet.placeBet(job.player, job.currency, cancelId, cancelId, state.gameCode.getOrElse(""), state.sessionId.getOrElse(""),
            referenced.amount, allowNegative = true, None, provider) match
            case WalletAnswer.Moved(id, b) =>
              done(Vector(Movement(Movement.Bet, cancelId, Some(cancelId), referenced.amount, id)), Vector.empty, 0, referenced.amount, b)
            case other => Left(other.toString)
        else
          val bet = state.openBets.find(_.walletKey == refId)
          if referenced.amount == 0 then done(Vector.empty, bet.map(_.walletKey).toVector, 0, 0, None)
          else
            val refunded: Either[String, Option[(Movement, Option[Balances])]] = bet match
              case Some(b) =>
                wallet.rollbackBet(job.player, job.currency, cancelId, b.walletRoundId) match
                  case WalletAnswer.Moved(id, bs) => Right(Some(Movement(Movement.Rollback, cancelId, Some(b.walletRoundId), referenced.amount, id) -> bs))
                  case WalletAnswer.Refused(_, WalletRefusal.RoundNotFound, _) => Right(None)
                  case other => Left(other.toString)
              case None => Right(None)
            refunded.flatMap {
              case Some((movement, bs)) => done(Vector(movement), Vector(refId), referenced.amount, 0, bs)
              case None =>
                // The wallet no longer holds the bet: its amount is refunded as a provider credit under the cancel id.
                wallet.providerCredit(job.player, job.currency, cancelId, referenced.amount, refId, "cancel of a debit the wallet no longer holds") match
                  case WalletAnswer.Moved(id, bs) =>
                    done(Vector(Movement(Movement.ProviderCredit, cancelId, None, referenced.amount, id)), Vector(refId), referenced.amount, 0, bs)
                  case other => Left(other.toString)
            }

  // ── payout (FR-016) ──────────────────────────────────────────────────────────────────────────────

  private def payout(job: BetJob): Either[String, Moved] =
    wallet.providerCredit(job.player, job.currency, job.key, job.amount, job.key, "payout") match
      case WalletAnswer.Moved(id, balances) =>
        after(balances, job).map(b =>
          Moved(RecordTransaction(job.opening, tx(job, job.amount, Vector(Movement(Movement.ProviderCredit, job.key, None, job.amount, id)), ok(job, b)))))
      case WalletAnswer.Refused(code, _, message) => Left(s"$code $message")
      case WalletAnswer.Unacknowledged(error)     => Left(error)

object MoneyMoves:
  /** What the round is to record, and whether it counts as a bet placed (FR-029). */
  final case class Moved(cmd: RecordTransaction, countsBet: Boolean = false)
