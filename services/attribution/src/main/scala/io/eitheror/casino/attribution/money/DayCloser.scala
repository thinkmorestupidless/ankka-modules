package io.eitheror.casino.attribution.money

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.{Settings, Wiring}
import io.eitheror.casino.attribution.settings.{EndTransition, SettingsEntity}

import java.time.{Clock, Instant, LocalDate}

/** What one run of the closer did, for its suite and its log. */
final case class Closing(closed: Vector[String], transitionEnded: Option[(String, String)])

/**
 * The day closer's run (research R10, R16): closes every open tally whose day ended `CLOSE_GRACE` ago,
 * ends the transition from legacy tags when it is due, and drops the legacy counts older than 90 days.
 * A tally's `close` and the settings' commands are idempotent, so a run repeated after a crash is harmless.
 */
final class DayClosing(components: ComponentClient, openBefore: String => Vector[DayRow], settings: Settings, now: () => Instant):
  def run(): Closing =
    val at    = now()
    val zone  = settings.reportingZone
    val today = Days.dayOf(at, zone)
    val due   = openBefore(today).filter(r => Days.closeDue(r.day, zone, settings.closeGrace, at))
    due.foreach(r => components.forEventSourcedEntity(EntityId(DayTally.id(r.player, r.currency, r.day))).call(DayTally.close).invoke(at): Unit)

    val brand = components.forKeyValueEntity(EntityId(SettingsEntity.Id))
    val s     = brand.call(SettingsEntity.get).invoke()
    val ended = for
      first      <- s.firstDay if s.transitionEnd.isEmpty
      (end, by)  <- Days.endOfTransition(LocalDate.parse(first), s.lastLegacyDay.map(LocalDate.parse), LocalDate.parse(today))
    yield
      brand.call(SettingsEntity.endTransition).invoke(EndTransition(end.toString, by)): Unit
      end.toString -> by
    if s.legacyDaily.keys.exists(_ < LocalDate.parse(today).minusDays(90).toString) then
      brand.call(SettingsEntity.pruneLegacy).invoke(today): Unit
    Closing(due.map(r => DayTally.id(r.player, r.currency, r.day)), ended)

/** The recurring timer `close-days`, every 5 minutes: always done, so a failed run is retried by the next. */
final class DayCloser(context: TimedActionContext) extends TimedAction:
  def run: Effect =
    DayCloser.closing(context.componentClient, context.clock).run(): Unit
    effects.done()

object DayCloser extends TimedAction.Companion[DayCloser](ComponentId("day-closer")):
  val TimerName = "close-days"

  def closing(components: ComponentClient, clock: Clock): DayClosing =
    DayClosing(components, day => Wiring.openBefore.get()(day), Wiring.settings.get(), () => clock.instant())

  def create(context: TimedActionContext) = new DayCloser(context)
  val closeDays = handler("close-days")(_.run)
