package io.eitheror.casino.attribution.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.attribution.money.{DayCloser, DayTally, OpenDays, WalletCursor}

import java.time.{LocalDate, LocalTime, ZonedDateTime}

/** Reporting days on a kit's clock, in its zone: an evening to play in, and the day's close. */
final class DayClock(kit: Kit):
  private def local: ZonedDateTime = kit.now.atZone(kit.zone)

  /**
   * Moves to 23:30 local on the next day whose evening is still to come; answers that day. The jump is
   * hours: the networks' documents are fetched again at once, as the minute's timer keeps them in real
   * time, or a registration read before the timer's run finds the key document lapsed.
   */
  def evening(): LocalDate =
    val today = local.toLocalDate
    val day   = if local.toLocalTime.isBefore(LocalTime.of(23, 0)) then today else today.plusDays(1)
    at(day, LocalTime.of(23, 30))
    kit.refreshDocuments(): Unit
    day

  def at(day: LocalDate, time: LocalTime): Unit = kit.moveClock(day.atTime(time).atZone(kit.zone).toInstant)

  /**
   * Past the day's close (`CLOSE_GRACE` after midnight local), and the closer run as its timer would, once
   * every wallet's facts are read and their legs are in the day's tallies: the consumers are asynchronous,
   * and a leg read after the close is an adjustment.
   */
  def close(day: LocalDate, wallets: WalletScript*): Unit =
    wallets.foreach(played(_, day))
    at(day.plusDays(1), LocalTime.of(0, 21))
    DayCloser.closing(kit.components, kit.clock).run(): Unit

  def played(w: WalletScript, day: LocalDate): Unit =
    kit.eventually(s"${w.player}'s wallet read") {
      val cursor = kit.components.forKeyValueEntity(EntityId(WalletCursor.id(w.player, "EUR"))).call(WalletCursor.get).invoke()
      val seen = Vector(day, day.plusDays(1)).flatMap(d =>
        kit.components.forEventSourcedEntity(EntityId(DayTally.id(w.player, "EUR", d.toString))).call(DayTally.get).invoke().seen)
      Option.when(cursor.recent.size >= w.published.size && w.legs.forall(seen.contains))(())
    }
    // The closer finds the day through the OpenDays view, which follows the tally.
    if w.legs.nonEmpty then
      kit.eventually(s"${w.player}'s day open in the view")(kit.views.forView(OpenDays)
        .ask(OpenDays.openBefore, "day" -> day.plusDays(1).toString).find(r => r.player == w.player && r.day == day.toString))
        : Unit
