package io.eitheror.casino.players.sessions

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.Gauges
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.{Neighbours, PlayersRuntime}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * The recurring read of the realm's live sessions (R5, FR-014), named `realm-reconcile`: every open
 * player session whose realm session the realm no longer holds ends `expired`, and the gauge
 * `players.active_sessions` is set to the realm's count. A tick the realm does not answer is logged
 * and changes nothing; the timer runs again at the next.
 */
final class RealmReconcile(context: TimedActionContext) extends TimedAction:
  private val log = LoggerFactory.getLogger("players.realm-reconcile")

  def reconcile(): Effect =
    Neighbours.realm(context.secrets).liveSessions() match
      case Left(why) => log.warn("realm-reconcile: the realm did not answer: {}", why.code)
      case Right(live) =>
        val held = live.map(_.sessionId).toSet
        Gauges.global.set(RealmReconcile.Gauge, Map("brand" -> PlayersRuntime.settings.brand), live.size.toDouble,
          "player sessions the brand's realm holds")
        Try(SessionsView.open(PlayersRuntime.views())).fold(
          e => log.warn("realm-reconcile: the open sessions could not be read: {}", e.getClass.getSimpleName),
          open =>
            val gone = open.filter(r => r.parent.nonEmpty && !held(r.parent))
            gone.foreach(r => SessionEndWorkflow.end(context.componentClient, r.sessionId, EndReason.Expired, None): Unit)
            if gone.nonEmpty then log.info("realm-reconcile: {} sessions ended expired", gone.size)
        )
    effects.done()

object RealmReconcile extends TimedAction.Companion[RealmReconcile](ComponentId("realm-reconcile")):
  val Name  = "realm-reconcile"
  val Gauge = "players.active_sessions"

  def create(context: TimedActionContext) = new RealmReconcile(context)

  val reconcile = handler("reconcile")(_.reconcile())

  /** Scheduled at every start; scheduling again under the name keeps its next due. */
  def schedule(timers: TimerScheduler): Unit =
    val every = FiniteDuration(PlayersRuntime.settings.realmReconcileInterval.toMillis, "millis")
    timers.createRecurringTimer(Name, every, every, reconcile.deferred)
