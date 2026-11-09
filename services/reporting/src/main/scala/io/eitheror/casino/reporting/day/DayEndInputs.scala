package io.eitheror.casino.reporting.day

import com.thinkmorestupidless.ankka.runtime.ViewClient
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.Settings
import io.eitheror.casino.reporting.days.Days
import io.eitheror.casino.reporting.predictions.{TradeFeeState, TradeFees}
import io.eitheror.casino.reporting.reads.{RoundRow, Rounds, Wallets, WithdrawalRow, WithdrawalsView}

import java.time.{Instant, LocalDate, ZoneId}

/**
 * What a day's close reads from views over every round, wallet and withdrawal (research R7; `CloseInputs`), so the
 * day-end holds what no event of the day carries (FR-018): the games and the differences from `Rounds`, the liability
 * from `Wallets`' open records, the pending holds from the `Withdrawal`s requested before the day's end and not ended
 * by it, the corrections to earlier days that arrived during it (FR-019), and with the predictions module the fee
 * account's credits beside the trades' fees (FR-031). The folds are pure; `gather` reads.
 */
object DayEndInputs:
  /** A round the game provider's record lacks has no game: it is listed under this provider and game. */
  val Unknown = "unknown"

  private def cancelled(o: String) = o == f.RoundSettled.Outcome.Cancelled

  /**
   * Per currency and `<provider>/<game>`, the provider's stake and win (nothing for a cancelled round, as
   * `RoundChecks.agree` reads it) and the wallet's stake less rollbacks and win, over the day's rounds.
   */
  def games(rows: Vector[RoundRow]): Map[String, Map[String, GameFigures]] =
    rows.filter(r => r.provider.isDefined || r.wallet.isDefined).groupBy(_.currency).map { (currency, rounds) =>
      currency -> rounds.groupBy(r => r.provider.fold(s"$Unknown/$Unknown")(p => s"${p.provider}/${p.game}")).map { (key, rs) =>
        val first = rs.flatMap(_.provider).headOption
        key -> rs.foldLeft(GameFigures(first.fold(Unknown)(_.provider), first.fold(Unknown)(_.game),
          rs.flatMap(_.provider).flatMap(_.gameName).headOption)) { (g, r) =>
          val (ps, pw) = r.provider.fold((0L, 0L))(p => if cancelled(p.outcome) then (0L, 0L) else (p.stake, p.win))
          val (ws, ww) = r.wallet.fold((0L, 0L))(w => (w.stake - w.rollback, w.win))
          g.copy(providerStake = g.providerStake + ps, providerWin = g.providerWin + pw, walletStake = g.walletStake + ws,
            walletWin = g.walletWin + ww)
        }
      }
    }

  /** Every round of the day whose two sides differ or that one side lacks, in a fixed order (FR-018). */
  def differences(rows: Vector[RoundRow]): Vector[Difference] =
    rows.filter(r => r.status.nonEmpty && r.status != io.eitheror.casino.reporting.intake.RoundChecks.Matched).map { r =>
      Difference(r.player, r.currency, r.roundId, r.status, r.provider.fold(0L)(_.stake), r.provider.fold(0L)(_.win),
        r.wallet.fold(0L)(_.stake), r.wallet.fold(0L)(_.win), r.wallet.fold(0L)(_.rollback), r.provider.fold("")(_.provider))
    }.sortBy(d => (d.currency, d.roundId, d.player))

  /** The pending holds at `end` per currency. */
  def pendingHolds(rows: Vector[WithdrawalRow]): Map[String, Long] =
    rows.groupMapReduce(_.currency)(_.amount)(_ + _)

  /**
   * The corrections to the days of `rows` that arrived from `from` (inclusive) to `to` (exclusive); a test-only one moves
   * no figure the day-end shows, which counts every player.
   */
  def opening(rows: Vector[DayRow], from: Instant, to: Instant): Vector[OpeningCorrection] =
    rows.flatMap(r => r.corrections.filter(c => !c.testOnly && !c.arrivedAt.isBefore(from) && c.arrivedAt.isBefore(to))
      .map(c => OpeningCorrection(r.day, c.arrivedAt, c.delta))).sortBy(o => (o.arrivedAt, o.day))

  /**
   * The fee account's credits per currency and market over the day's trades, and every trade whose fee and credits
   * differ or that one side lacks (FR-031), in a fixed order. A credit with no `trade_made` has no market: `""`.
   */
  def fees(rows: Vector[TradeFeeState]): (Map[String, Map[String, Long]], Vector[FeeDifference]) =
    val credits = rows.filter(_.credited).groupBy(_.currency).map((c, rs) => c -> rs.groupMapReduce(_.market)(_.credits)(_ + _))
    val differ = rows.filter(_.differs).map { r =>
      val status = if !r.credited then "fee_only" else if !r.traded then "credit_only" else "differs"
      FeeDifference(r.trade, r.market, r.currency, r.fee, r.credits, status)
    }.sortBy(d => (d.currency, d.market, d.trade))
    (credits, differ)

  /**
   * The inputs of `day`'s close, read now. The liability is the open records' balance as the views hold them at the
   * close, which a close run late (a service that was down) reads later than the day's end.
   */
  def gather(views: ViewClient, settings: Settings, day: String): CloseInputs =
    val g      = settings.generation
    val zone: ZoneId = settings.reportingZone
    val rounds = Rounds.ofDay(views, g, day)
    val end    = Days.endOf(day, zone)
    val held   = DaysView.currencies(views, g)
    val (credits, feeDifferences) =
      if settings.predictions then fees(TradeFees.ofDay(views, g, day)) else (Map.empty[String, Map[String, Long]], Vector.empty)
    CloseInputs(
      games = games(rounds),
      differences = differences(rounds),
      liability = held.map(c => c -> Wallets.open(views, g, c).flatMap(_.records.values).map(_.balance).sum).toMap,
      pendingHolds = pendingHolds(WithdrawalsView.pending(views, g, end)),
      openingCorrections = opening(DaysView.corrected(views, g, LocalDate.parse(day)), Days.startOf(day, zone), end),
      feeCredits = credits,
      feeDifferences = feeDifferences
    )
