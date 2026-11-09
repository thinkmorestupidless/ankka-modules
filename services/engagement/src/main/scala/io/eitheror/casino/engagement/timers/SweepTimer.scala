package io.eitheror.casino.engagement.timers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.engagement.EngagementRuntime
import io.eitheror.casino.engagement.channel.Channel
import io.eitheror.casino.engagement.channel.ChannelCommand.Sweep

import scala.concurrent.duration.*

/**
 * `sweep-<player>` (FR-023, research R10): recurring every 30 s while the player has a connection, so the leases of an
 * instance that died expire within two minutes and take the player offline. Set by `PushPublisher` on the first open
 * and removed at zero; a sweep that finds no connection left removes it too.
 */
object Sweeps:
  def timerName(playerId: String): String = s"sweep-$playerId"

  def start(timers: TimerScheduler, playerId: String): Unit =
    val every = EngagementRuntime.settings.sweepEvery.toMillis.millis
    timers.createRecurringTimer(timerName(playerId), every, every, SweepTimer.sweep.deferred(playerId))

  def stop(timers: TimerScheduler, playerId: String): Unit = timers.delete(timerName(playerId))

/** `sweep-<player>` due: the player's channel swept at the service's time. */
final class SweepTimer(context: TimedActionContext) extends TimedAction:
  def sweep(playerId: String): Effect =
    val left = context.componentClient.forEventSourcedEntity(EntityId(playerId)).call(Channel.sweep)
      .invoke(Sweep(EngagementRuntime.now()))
    if left.connections == 0 then Sweeps.stop(EngagementRuntime.timers(), playerId)
    effects.done()

object SweepTimer extends TimedAction.Companion[SweepTimer](ComponentId("sweep-timer")):
  given Serializer[String] = com.thinkmorestupidless.ankka.core.Serializers.string

  def create(context: TimedActionContext) = new SweepTimer(context)
  val sweep = handler("sweep")(_.sweep)
