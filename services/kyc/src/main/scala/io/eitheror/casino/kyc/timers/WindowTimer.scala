package io.eitheror.casino.kyc.timers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.kyc.{KycCodecs, KycRuntime}
import io.eitheror.casino.kyc.clients.Players
import io.eitheror.casino.kyc.policy.PolicyEntity
import io.eitheror.casino.kyc.record.{KycRecord, RequestedSuspension, WindowLapsed}

import java.time.Instant

/** What the window's timer carries: the player and the deadline it is for, so a stale fire is told apart (R10). */
final case class WindowDue(playerId: String, deadline: Instant)

object WindowDue:
  given Serializer[WindowDue] = KycCodecs.serializer[WindowDue]("window-due")

/**
 * The window lapsed (R10, FR-025): the record decides, by the policy in force now, whether to flag and
 * suspend. When it says suspend and no suspension was asked for this deadline, `players` is told; a
 * failure throws, so the timer runs again with backoff, and the record keeps `SuspensionRequested` once
 * `players` answered, so a run after that asks nothing.
 */
final class WindowTimer(context: TimedActionContext) extends TimedAction:
  def lapse(d: WindowDue): Effect =
    val record = context.componentClient.forEventSourcedEntity(EntityId(d.playerId))
    val policy = context.componentClient.forEventSourcedEntity(EntityId(KycRuntime.settings.brand)).call(PolicyEntity.get).invoke()
    val state  = record.call(KycRecord.windowLapsed).invoke(WindowLapsed(d.deadline, policy.suspendOnExpiry, KycRuntime.now())).state
    state.deadline.filter(x => x.at == d.deadline && x.suspend && !x.suspensionRequested).foreach { _ =>
      WindowTimer.players(context).suspend(d.playerId, d.deadline).fold(why => throw IllegalStateException(why), identity)
      record.call(KycRecord.suspensionRequested).invoke(RequestedSuspension(d.deadline, KycRuntime.now())): Unit
    }
    effects.done()

object WindowTimer extends TimedAction.Companion[WindowTimer](ComponentId("kyc-window-timer")):
  /** The `players` client; a suite may replace it. */
  @volatile var players: TimedActionContext => Players = ctx => Players.http(ctx.services, KycRuntime.settings.brand)

  def create(context: TimedActionContext) = new WindowTimer(context)
  val fire = handler("lapse")(_.lapse)

  def name(playerId: String): String = s"kyc-window-$playerId"
