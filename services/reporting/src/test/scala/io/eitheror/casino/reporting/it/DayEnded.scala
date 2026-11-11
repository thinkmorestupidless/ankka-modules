package io.eitheror.casino.reporting.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.reporting.day.{CloseDays, DaysView}
import io.eitheror.casino.reporting.days.Days
import io.eitheror.casino.reporting.reads.{Rounds, Wallets, WithdrawalsView}

import java.time.LocalDate
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * A reporting day of facts for the day-end (US6; FR-018, FR-019), in a currency no other suite uses (`CHF`), so the
 * liability and the pending holds the close reads over every wallet are this helper's alone to compute: a deposit
 * through one provider and an attempt through another, a completed withdrawal, two pending at the close (one completed
 * the next day), four rounds of one game (matched, differing, the provider's only, the wallet's only), a grant, a
 * chargeback and an open restricted funds record. Every figure expected is written from the facts here.
 */
final class DayEnded(kit: Kit, val day: LocalDate, val currency: String = DayEnded.Currency):
  val tag: String     = UUID.randomUUID().toString.take(8)
  val player: String  = s"de-$tag"
  val game: String    = s"de-game-$tag"
  val provider        = "st8"
  def round(n: Int)   = s"de-$tag-r$n"
  val set: FactSet    = FactSet(kit.zone)
  private def at(time: String) = set.at(day, time)

  def usual(): this.type =
    val c = currency
    set.register(player, set.at(day.minusDays(1), "09:00"), currency = c)
    set.payment(player, c, "psp-a", 100_00, Some(at("09:00")), "captured", Some(at("09:01")))
    set.payment(player, c, "psp-b", 50_00, Some(at("09:02")), "failed", Some(at("09:03")))
    set.deposit(player, c, 100_00, at("09:01"))
    // r1 matched, r2 the wallet staked 6 where the provider says 5, r3 the provider's only, r4 the wallet's only.
    set.bet(player, c, round(1), 10_00, 10_00, at("10:00"))
    set.win(player, c, round(1), 4_00, 4_00, at("10:01"))
    set.settled(player, c, round(1), provider, game, "Book of Gold", s"gs-$tag", 10_00, 4_00, at("10:01"))
    set.bet(player, c, round(2), 6_00, 6_00, at("10:10"))
    set.settled(player, c, round(2), provider, game, "Book of Gold", s"gs-$tag", 5_00, 0, at("10:11"))
    set.settled(player, c, round(3), provider, game, "Book of Gold", s"gs-$tag", 3_00, 1_00, at("10:20"))
    set.bet(player, c, round(4), 2_00, 2_00, at("10:30"))
    set.win(player, c, round(4), 5_00, 5_00, at("10:31"))
    set.bonus(player, c, 2_00, at("12:00"))
    set.chargeback(player, c, 1_50, at("13:00"))
    set.withdrawal(player, c, 30_00, at("14:00"), at("15:00"), "bank")
    set.withdrawalOf(player, c, 7_00, at("16:00"), None, "bank")
    set.withdrawalOf(player, c, 4_00, at("17:00"), Some(set.at(day.plusDays(1), "09:00")), "bank")
    set.award(player, c, s"award-$tag", 20_00, 200_00, at("18:00"))
    this

  def publish(): this.type =
    set.publish(kit)
    this

  private def g = kit.settings.generation

  /** Every fact folded into the views the close reads, so none of them arrives after it as a correction. */
  def settle(): this.type =
    val want = set.figures(day, currency)
    kit.eventually(s"$day folded", 60.seconds)(DaysView.row(kit.views, g, day).flatMap(_.currencies.get(currency)).map(_.total)
      .filter(s => s.money.stakes == want.stakes && s.money.wins == want.wins && s.money.bonusCost == want.bonus &&
        s.money.chargebacks == want.chargebacks && s.money.deposits == want.deposits && s.money.withdrawals == want.withdrawals &&
        s.payments.get("psp-b").exists(_.attempts == 1) && s.gateways.get("bank").exists(_.requests >= 2)))
    kit.eventually(s"$day's rounds", 60.seconds)(Option.when(
      Rounds.ofDay(kit.views, g, day.toString).filter(_.roundId.startsWith(s"de-$tag")).map(r => r.roundId -> r.status).toMap ==
        Map(round(1) -> "matched", round(2) -> "differs", round(3) -> "provider_only", round(4) -> "wallet_only"))(()))
    kit.eventually("the pending withdrawals", 60.seconds)(Option.when(
      WithdrawalsView.pending(kit.views, g, end).count(_.player == player) == 2)(()))
    kit.eventually("the open record", 60.seconds)(Option.when(Wallets.open(kit.views, g, currency).exists(_.player == player))(()))
    this

  def end: java.time.Instant = Days.endOf(day.toString, kit.zone)

  /** What the close is due: the clock moved past the next run of `close-days` and every day consumer's idle bound. */
  def close(): JsonNode = DayEnded.closeDue(kit, day)

  /**
   * Facts for this closed day arriving during a today of its own, which a deposit of its own makes a day: answers today
   * and when they arrived.
   */
  def arriveLate(add: FactSet => Unit): (LocalDate, java.time.Instant) =
    val today = kit.freshToday()
    val t     = FactSet(kit.zone)
    t.deposit(player, currency, 1_00, t.at(today, "09:00"))
    t.publish(kit)
    val late = FactSet(kit.zone)
    add(late)
    late.publish(kit)
    (today, kit.now)

  /** The day-end once a correction `matching` is listed on it, and the day's row in the view holds it too. */
  def corrected(matching: JsonNode => Boolean): JsonNode =
    val j = kit.eventually("the correction", 60.seconds)(Some(DayEnded.read(kit, day).json)
      .filter(j => DayEnded.list(j.path("corrections")).exists(matching)))
    kit.eventually("the corrected row in the view", 60.seconds)(DaysView.row(kit.views, g, day).filter(_.corrections.nonEmpty))
    j

  // ── what the day-end must hold ─────────────────────────────────────────────────────────────────

  def money(v: Long): String = java.math.BigDecimal.valueOf(v, 2).toPlainString

  def deposits: Vector[(String, Long, String)]    = Vector(("psp-a", 1L, money(100_00)), ("psp-b", 0L, money(0)))
  def withdrawals: Vector[(String, Long, String)] = Vector(("bank", 1L, money(30_00)))

  /** The game's provider GGR over r1–r3 and the wallet's over r1 and r2; r4 under the unknown game, the wallet's alone. */
  def games: Vector[(String, String, String, String, String)] = Vector(
    (provider, game, "Book of Gold", money((10_00 - 4_00) + 5_00 + (3_00 - 1_00)), money((10_00 - 4_00) + 6_00)),
    ("unknown", "unknown", "unknown", money(0), money(2_00 - 5_00)))

  def differences: Vector[Map[String, String]] = Vector(
    Map("roundId" -> round(2), "provider" -> provider, "providerStake" -> money(5_00), "providerWin" -> money(0),
      "walletStake" -> money(6_00), "walletWin" -> money(0), "status" -> "differs"),
    Map("roundId" -> round(3), "provider" -> provider, "providerStake" -> money(3_00), "providerWin" -> money(1_00),
      "walletStake" -> money(0), "walletWin" -> money(0), "status" -> "provider_only"),
    Map("roundId" -> round(4), "provider" -> "", "providerStake" -> money(0), "providerWin" -> money(0),
      "walletStake" -> money(2_00), "walletWin" -> money(5_00), "status" -> "wallet_only"))

  def totals: Map[String, String] =
    val e = set.figures(day, currency)
    Map("walletGgr" -> money(e.ggr), "depositPoolGgr" -> money(e.pool), "bonusCost" -> money(e.bonus),
      "chargebacks" -> money(e.chargebacks), "liability" -> money(kit.everything.openRecords(currency, before = Some(end)).map(_.balance).sum),
      "pendingHolds" -> money(kit.everything.pendingAt(end, currency)))

object DayEnded:
  val Currency = "CHF"

  def finance(kit: Kit): Seq[(String, String)] = kit.asStaff(Seq("brand_finance"), "fin-1")

  def read(kit: Kit, day: LocalDate): Reply = kit.get(s"/day-end/$day", finance(kit))

  /**
   * `day`, already past its end and the grace, closed: the clock moved past `CLOSE_IDLE` and a run of `close-days`, so a
   * consumer that has folded nothing past the day since the facts were published no longer holds it (research R7).
   */
  def closeDue(kit: Kit, day: LocalDate): JsonNode =
    kit.moveClock(idleRun(kit))
    closedOnceIdle(kit, day)

  /**
   * The day-end once the day has closed, the clock moved past `CLOSE_IDLE` again each time half a minute passes without
   * it. A consumer still replaying after a rebuild (another suite's, every fact on the kit's topics) tells its progress
   * at the moved clock, so it is active, not idle, until the replay ends (research R7); the close waits for that, as a
   * brand's does, for at most `Replay`.
   */
  def closedOnceIdle(kit: Kit, day: LocalDate): JsonNode =
    val until = System.nanoTime() + Replay.toNanos
    def done = read(kit, day).status == 200
    while !done && System.nanoTime() < until do
      if scala.util.Try(kit.eventually(s"$day closed", 30.seconds)(Option.when(done)(()))).isFailure then kit.moveClock(idleRun(kit))
    closed(kit, day)

  /** How long a close waits for a replay of the kit's topics to end. */
  val Replay: FiniteDuration = 20.minutes

  /** Past every day consumer's idle bound and the next run of `close-days`. */
  def idleRun(kit: Kit): FiniteDuration = kit.settings.closeIdle.toMillis.millis + 1.minute

  /** The day-end once the day has closed. */
  def closed(kit: Kit, day: LocalDate): JsonNode =
    kit.eventually(s"$day closed", 60.seconds)(Some(read(kit, day)).filter(_.status == 200)).json

  /**
   * `day` (today) left open until its end and the grace have passed, and then closed: answers its day-end. The clock is
   * moved to ten minutes after its end, where a run of `close-days` leaves it open, then `CLOSE_IDLE` and a minute on, past
   * the grace, the consumers' idle bound and a run of `close-days` (every five minutes) after it.
   */
  def closeAtGrace(kit: Kit, day: LocalDate): JsonNode =
    val end = Days.endOf(day.toString, kit.zone)
    kit.moveClock(java.time.Duration.between(kit.now, end.plusSeconds(10 * 60)).toMillis.millis)
    Thread.sleep(1_000)
    assertOpen(kit, day)
    kit.moveClock(idleRun(kit))
    // A run of `close-days` still catching up when the clock moved read it before the move and set its next run from
    // after it, so the day waits for that next run: the clock is moved on to it (still past the grace) if it has to.
    scala.util.Try(kit.eventually(s"$day closed", 20.seconds)(Option.when(read(kit, day).status == 200)(())))
      .getOrElse(kit.moveClock(CloseDays.Every))
    closedOnceIdle(kit, day)

  def assertOpen(kit: Kit, day: LocalDate): Unit =
    val r = read(kit, day)
    if r.status != 409 then throw AssertionError(s"$day is not open: ${r.status} ${r.text.take(200)}")

  /** A day-end without its corrections: what is fixed. */
  def fixed(j: JsonNode): JsonNode =
    j.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]().without[JsonNode]("corrections")

  def fields(n: JsonNode): Map[String, String] = n.properties.asScala.map(e => e.getKey -> e.getValue.asText).toMap

  def list(n: JsonNode): Vector[JsonNode] = n.elements.asScala.toVector
