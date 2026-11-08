package io.eitheror.casino.reporting.day

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.{ReportingCodecs, ReportingRuntime, Settings}
import io.eitheror.casino.reporting.days.Days
import io.eitheror.casino.reporting.intake.Generation

import java.time.{Clock, Instant}
import scala.concurrent.duration.*

/** One day, open until its `DayFixed` (data-model.md): what the close asks for. */
final case class OpenDayRow(generation: Int, day: String, open: Boolean = true, closedAt: Option[Instant] = None)

object OpenDayRow:
  given Serializer[OpenDayRow] = ReportingCodecs.row[OpenDayRow]("open-day-row")

final class OpenDaysView extends View[DayEvent, OpenDayRow]:
  def onChange(event: DayEvent): Effect =
    val subject = updateContext.subject
    Generation.of(subject).fold(effects.ignore()) { generation =>
      event match
        case f: DayEvent.DayFixed => effects.updateRow(OpenDayRow(generation, Generation.rest(subject), open = false, Some(f.closedAt)))
        case _ if rowState.isEmpty => effects.updateRow(OpenDayRow(generation, Generation.rest(subject)))
        case _                     => effects.ignore()
    }

/** The days `close-days` closes: one row per `BrandDay`, open until it is fixed. */
object OpenDays extends View.Companion[OpenDaysView, DayEvent, OpenDayRow](
      componentId = ComponentId("open-days"),
      source = ChangeSource.eventsOf(BrandDay),
      rowSerializer = OpenDayRow.given_Serializer_OpenDayRow
    ):
  /** A generation's open days before `day` (an ISO date, so text order is date order). */
  val openBefore = query("open-before")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND (payload::jsonb->>'open')::boolean IS NOT FALSE AND payload::jsonb->>'day' < :day ORDER BY payload::jsonb->>'day'")

  def create(ctx: ViewComponentContext) = new OpenDaysView

/**
 * One run of the closer (research R7), attribution's `DayClosing`: closes every open day whose end in the zone plus
 * `CLOSE_GRACE` has passed, so a day closes at the instant `ggr-daily` does (FR-006). A day's close is once, so a run
 * repeated after a crash is harmless.
 */
final class DayClosing(components: ComponentClient, openBefore: String => Vector[OpenDayRow], settings: Settings,
    inputs: String => CloseInputs, now: () => Instant):
  def run(): Vector[String] =
    val at   = now()
    val zone = settings.reportingZone
    val due  = openBefore(Days.dayOf(at, zone)).filter(r => r.open && Days.closeDue(r.day, zone, settings.closeGrace, at))
    due.foreach { r =>
      components.forEventSourcedEntity(EntityId(BrandDay.id(settings.generation, r.day))).call(BrandDay.close)
        .invoke(Close(at, zone.getId, inputs(r.day))): Unit
    }
    due.map(_.day)

/** The recurring timer `close-days`, every five minutes: always done, so a failed run is retried by the next. */
final class CloseDays(context: TimedActionContext) extends TimedAction:
  def run: Effect =
    CloseDays.closing(context.componentClient, context.clock).run(): Unit
    effects.done()

object CloseDays extends TimedAction.Companion[CloseDays](ComponentId("close-days")):
  val TimerName = "close-days"
  val Every: FiniteDuration = 5.minutes

  def closing(components: ComponentClient, clock: Clock): DayClosing =
    DayClosing(components, ReportingRuntime.openBefore.get(), ReportingRuntime.settings.get(), ReportingRuntime.closeInputs.get(),
      () => clock.instant())

  /** At every start: setting the timer again under its name replaces it. */
  def schedule(timers: TimerScheduler): Unit =
    timers.createRecurringTimer(TimerName, Every, Every, closeDays.deferred)

  def create(context: TimedActionContext) = new CloseDays(context)
  val closeDays = handler("close-days")(_.run)
