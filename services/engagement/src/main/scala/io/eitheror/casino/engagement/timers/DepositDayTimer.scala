package io.eitheror.casino.engagement.timers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.engagement.EngagementRuntime
import io.eitheror.casino.engagement.consumers.Sends
import io.eitheror.casino.engagement.profile.{DepositDay, Profile}
import io.eitheror.casino.engagement.reads.ProfilesView

import java.time.{Instant, LocalDate}
import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * FR-009's rollover: at 03:05 UTC every player whose daily deposit count is above zero and stamped with an earlier
 * deposit day goes to 0, and is identified under `deposit-day/<day>/<player>`. A profile already reset answers false
 * and is sent nothing, so a second firing, or a view still showing the old count, identifies no one again.
 */
object DepositDayReset:
  private val log = org.slf4j.LoggerFactory.getLogger("io.eitheror.casino.engagement.timers.DepositDayReset")

  /** The timer's name: `deposit-day-<slug>`. */
  def timerName(slug: String): String = s"deposit-day-$slug"

  def key(day: LocalDate, playerId: String): String = s"deposit-day/$day/$playerId"

  /** Resets and identifies every player above zero before `day`, a page at a time; how many were identified. */
  def run(client: ComponentClient, day: LocalDate, at: Instant): Int =
    val view = EngagementRuntime.views().forView(ProfilesView)
    @tailrec def page(after: String, done: Int): Int =
      val rows = ProfilesView.dailyCountAbove(view, day, after)
      val reset = rows.count { r =>
        val was = client.forKeyValueEntity(EntityId(r.playerId)).call(Profile.resetDepositDay).invoke(day)
        if was then Sends.start(client, Vector(Sends.identify(r.playerId, s"deposit-day/$day", at, key(day, r.playerId))))
        was
      }
      if rows.size < ProfilesView.PageSize then done + reset else page(rows.last.playerId, done + reset)
    page("", 0)

  /** Sets `deposit-day-<slug>` recurring daily, first due at the next 03:05 UTC. Set at every start: the same timer. */
  def schedule(timers: TimerScheduler): Unit =
    val slug  = EngagementRuntime.settings.brand
    val now   = EngagementRuntime.now()
    val first = java.time.Duration.between(now, DepositDay.nextReset(now)).toMillis.millis
    try timers.createRecurringTimer(timerName(slug), first, 24.hours, DepositDayTimer.reset.deferred(slug))
    catch case NonFatal(e) => log.error(s"the deposit day's timer could not be set: ${e.getMessage}")

/** `deposit-day-<slug>` due: the deposit day that has just begun, reset. */
final class DepositDayTimer(context: TimedActionContext) extends TimedAction:
  def reset(slug: String): Effect =
    val now = EngagementRuntime.now()
    val n   = DepositDayReset.run(context.componentClient, DepositDay.of(now), now)
    org.slf4j.LoggerFactory.getLogger("io.eitheror.casino.engagement.timers.DepositDayTimer")
      .info(s"deposit day ${DepositDay.of(now)} for $slug: $n players identified with a count of 0")
    effects.done()

object DepositDayTimer extends TimedAction.Companion[DepositDayTimer](ComponentId("deposit-day-timer")):
  given Serializer[String] = com.thinkmorestupidless.ankka.core.Serializers.string

  def create(context: TimedActionContext) = new DepositDayTimer(context)
  val reset = handler("reset")(_.reset)
