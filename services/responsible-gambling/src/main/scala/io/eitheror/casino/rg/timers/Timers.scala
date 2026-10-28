package io.eitheror.casino.rg.timers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.rg.{RgCodecs, RgRuntime}
import io.eitheror.casino.rg.clients.Players
import io.eitheror.casino.rg.profile.*
import io.eitheror.casino.rg.profile.ProfileEvent.*
import org.slf4j.LoggerFactory

import java.time.{Duration, Instant}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.*

/** What a profile timer carries: the player and the instant it is for, so a stale fire is told apart (R3, R4). */
final case class PendingDue(playerId: String, keyId: String, appliesAt: Instant)
final case class BreakDue(playerId: String, until: Instant)
final case class ReturnDue(playerId: String, reinstatesAt: Instant)
final case class SyncDue(playerId: String)

object PendingDue:
  given Serializer[PendingDue] = RgCodecs.serializer[PendingDue]("pending-due")
object BreakDue:
  given Serializer[BreakDue] = RgCodecs.serializer[BreakDue]("break-due")
object ReturnDue:
  given Serializer[ReturnDue] = RgCodecs.serializer[ReturnDue]("return-due")
object SyncDue:
  given Serializer[SyncDue] = RgCodecs.serializer[SyncDue]("sync-due")

/** The timers' names (data-model.md *Timers*): the player and the limit's key, nothing personal. */
object TimerNames:
  def pending(playerId: String, key: LimitKey): String = s"rg-pending-$playerId-${key.timer}"
  def break(playerId: String): String                 = s"rg-break-$playerId"
  def returning(playerId: String): String             = s"rg-return-$playerId"
  def sync(playerId: String): String                  = s"rg-sync-$playerId"

private[timers] object Due:
  def in(at: Instant): FiniteDuration = Duration.between(RgRuntime.now(), at).toScala.max(FiniteDuration(0, "s"))

  def profile(client: ComponentClient, playerId: String) = client.forEventSourcedEntity(EntityId(playerId))

/** A staged change, applied when due (FR-005). */
final class PendingTimer(context: TimedActionContext) extends TimedAction:
  def apply(d: PendingDue): Effect =
    Due.profile(context.componentClient, d.playerId).call(ProfileEntity.applyPending).invoke(ApplyPending(d.keyId, d.appliesAt, RgRuntime.now())): Unit
    effects.done()

object PendingTimer extends TimedAction.Companion[PendingTimer](ComponentId("rg-pending-timer")):
  def create(context: TimedActionContext) = new PendingTimer(context)
  val fire = handler("apply")(_.apply)

/** A break's end (FR-017). */
final class BreakTimer(context: TimedActionContext) extends TimedAction:
  def end(d: BreakDue): Effect =
    Due.profile(context.componentClient, d.playerId).call(ProfileEntity.endBreak).invoke(EndBreak(d.until, RgRuntime.now())): Unit
    effects.done()

object BreakTimer extends TimedAction.Companion[BreakTimer](ComponentId("rg-break-timer")):
  def create(context: TimedActionContext) = new BreakTimer(context)
  val fire = handler("end")(_.end)

/** A return, 24 hours after it was asked for (FR-020). */
final class ReturnTimer(context: TimedActionContext) extends TimedAction:
  def reinstate(d: ReturnDue): Effect =
    Due.profile(context.componentClient, d.playerId).call(ProfileEntity.reinstate).invoke(Reinstate(d.reinstatesAt, RgRuntime.now())): Unit
    effects.done()

object ReturnTimer extends TimedAction.Companion[ReturnTimer](ComponentId("rg-return-timer")):
  def create(context: TimedActionContext) = new ReturnTimer(context)
  val fire = handler("reinstate")(_.reinstate)

/**
 * Tells `players` the status the profile holds and it has not acknowledged (R4): `PUT …/exclusion` for an
 * exclusion, `DELETE` for a reinstatement. Read at the fire, so the latest status wins; on no answer it
 * schedules itself again after the retry interval, until `players` answers.
 */
final class SyncTimer(context: TimedActionContext) extends TimedAction:
  def sync(d: SyncDue): Effect =
    val profile = Due.profile(context.componentClient, d.playerId)
    val state   = profile.call(ProfileEntity.get).invoke()
    state.playersTold.foreach { status =>
      val players = SyncTimer.players(context)
      val told = status match
        case PlayersStatus.SelfExcluded => state.exclusion.exists(e => players.exclude(d.playerId, e.since, e.until))
        case _                          => players.reinstate(d.playerId)
      if told then profile.call(ProfileEntity.playersAck).invoke(PlayersAck(status, RgRuntime.now())): Unit
      else
        SyncTimer.log.warn("players did not take the {} status of a player; trying again", status)
        SyncTimer.schedule(RgRuntime.timers(), d.playerId, RgRuntime.settings.syncRetry.toScala)
    }
    effects.done()

object SyncTimer extends TimedAction.Companion[SyncTimer](ComponentId("rg-sync-timer")):
  private val log = LoggerFactory.getLogger("io.eitheror.casino.rg.timers")

  /** The `players` client; a suite may replace it. */
  @volatile var players: TimedActionContext => Players = ctx => Players.http(ctx.services, RgRuntime.settings.brand)

  def create(context: TimedActionContext) = new SyncTimer(context)
  val fire = handler("sync")(_.sync)

  def schedule(scheduler: TimerScheduler, playerId: String, delay: FiniteDuration): Unit =
    scheduler.createSingleTimer(TimerNames.sync(playerId), delay, fire.deferred(SyncDue(playerId)))

/**
 * Keeps a timer for every pending change, break and return the profile holds, and starts the sync to
 * `players` on every exclusion and reinstatement (data-model.md *Invariants*): a consumer of the profile's
 * events, so a timer is set whatever path made the change, and set again by name on a redelivery.
 */
final class ProfileTimers extends Consumer[ProfileEvent, Nothing]:
  def onMessage(event: ProfileEvent): Effect =
    val player = messageContext.subject
    val timers = RgRuntime.timers()
    event match
      case e: PendingStaged =>
        timers.createSingleTimer(TimerNames.pending(player, e.key), Due.in(e.appliesAt),
          PendingTimer.fire.deferred(PendingDue(player, e.key.id, e.appliesAt)))
      case e: PendingCancelled => timers.delete(TimerNames.pending(player, e.key))
      case e: BreakSet =>
        timers.createSingleTimer(TimerNames.break(player), Due.in(e.until), BreakTimer.fire.deferred(BreakDue(player, e.until)))
      case _: BreakLifted => timers.delete(TimerNames.break(player))
      case e: ReturnRequested =>
        timers.createSingleTimer(TimerNames.returning(player), Due.in(e.reinstatesAt), ReturnTimer.fire.deferred(ReturnDue(player, e.reinstatesAt)))
      case _: Excluded => SyncTimer.schedule(timers, player, FiniteDuration(0, "s"))
      case _: Reinstated =>
        timers.delete(TimerNames.break(player))
        SyncTimer.schedule(timers, player, FiniteDuration(0, "s"))
      case _ => ()
    effects.done()

object ProfileTimers extends Consumer.Companion[ProfileTimers, ProfileEvent, Nothing](
      ComponentId("rg-profile-timers"), ChangeSource.eventsOf(ProfileEntity)):
  def create(ctx: ConsumerContext) = new ProfileTimers
