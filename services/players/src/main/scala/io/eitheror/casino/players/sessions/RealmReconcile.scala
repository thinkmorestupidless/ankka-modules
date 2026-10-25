package io.eitheror.casino.players.sessions

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.{Neighbours, PlayersRuntime}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

/**
 * The recurring read of the realm's live sessions (R5, FR-014), named `realm-reconcile`. Until US2's
 * task fills it, it reads the sessions and logs how many; it then ends each open player session the
 * realm no longer holds.
 */
final class RealmReconcile(context: TimedActionContext) extends TimedAction:
  private val log = LoggerFactory.getLogger("players.realm-reconcile")

  def reconcile(): Effect =
    Neighbours.realm(context.secrets).liveSessions() match
      case Right(live) => log.info("realm-reconcile: {} live sessions", live.size)
      case Left(why)   => log.warn("realm-reconcile: the realm did not answer: {}", why.code)
    effects.done()

object RealmReconcile extends TimedAction.Companion[RealmReconcile](ComponentId("realm-reconcile")):
  val Name = "realm-reconcile"

  def create(context: TimedActionContext) = new RealmReconcile(context)

  val reconcile = handler("reconcile")(_.reconcile())

  /** Scheduled at every start; scheduling again under the name keeps its next due. */
  def schedule(timers: TimerScheduler): Unit =
    val every = FiniteDuration(PlayersRuntime.settings.realmReconcileInterval.toMillis, "millis")
    timers.createRecurringTimer(Name, every, every, reconcile.deferred)
