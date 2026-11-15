package io.eitheror.casino.backoffice.session

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant
import scala.concurrent.duration.*

/**
 * The expiry timer (data-model.md): one per session, named `session-expiry/<id>`, due at the refresh
 * token's expiry. When it fires the session ends `expired` if that expiry has passed; a session refreshed
 * since has a later one, and the timer is set again for it.
 */
final class SessionExpiry(context: TimedActionContext) extends TimedAction:

  def expire(session: String): Effect =
    val o = context.componentClient.forEventSourcedEntity(EntityId(session)).call(Session.expire)
      .invoke(ExpireAsk(context.clock.instant()))
    if !o.ended then o.due.foreach(due => SessionExpiry.schedule(context.timers, session, due, context.clock.instant()))
    effects.done()

object SessionExpiry extends TimedAction.Companion[SessionExpiry](ComponentId("session-expiry")):
  def create(context: TimedActionContext) = new SessionExpiry(context)

  val expire = handler[String]("expire")(a => id => a.expire(id))

  def name(session: String): String = s"session-expiry/$session"

  /** Sets the session's timer for `due`; setting it again under the name replaces it. */
  def schedule(timers: TimerScheduler, session: String, due: Instant, now: Instant): Unit =
    val delay = java.time.Duration.between(now, due).toMillis.max(0L)
    timers.createSingleTimer(name(session), delay.millis, expire.deferred(session))
