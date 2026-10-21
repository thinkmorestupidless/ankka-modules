package io.eitheror.casino.pspgateway.delivery

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.pspgateway.GatewayRuntime
import io.eitheror.casino.pspgateway.sessions.*
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

/**
 * The automatic re-drive (FR-016): every `REDRIVE_INTERVAL`, the undelivered outcomes, oldest first and
 * at most 500 a run, are re-driven by the timer, and the deliverer delivers each again. A refused
 * outcome is left for an operator. A run that fails is left to the timer runtime's retry.
 */
final class Redrive(context: TimedActionContext) extends TimedAction:
  private val log = LoggerFactory.getLogger(classOf[Redrive])

  def run: Effect =
    val views = Redrive.views.getOrElse(throw IllegalStateException("the re-drive runs before the service has its view client"))
    val due   = SessionsView.undelivered(views, Redrive.Batch)
    val at    = GatewayRuntime.now()
    due.foreach { row =>
      val entity = context.componentClient.forEventSourcedEntity(EntityId(row.reference))
      val r =
        if row.kind == SessionRow.Payout then entity.call(PayoutSession.redrive).invoke(Redrive.by(at))
        else entity.call(DepositSession.redrive).invoke(Redrive.by(at))
      if !r.redriven then log.info(s"redrive ${row.reference}: no longer undelivered (${r.delivery.word})")
    }
    if due.nonEmpty then log.info(s"redrive: ${due.size} undelivered outcomes re-driven")
    effects.done()

object Redrive extends TimedAction.Companion[Redrive](ComponentId("redrive")):
  def create(context: TimedActionContext) = new Redrive(context)
  val run = handler("run")(_.run)

  val TimerName = "redrive-undelivered"
  val Batch     = 500

  /** The service's view client, which a timed action's context does not carry; set once it has started. */
  @volatile var views: Option[ViewClient] = None

  def by(at: java.time.Instant): io.eitheror.casino.pspgateway.sessions.Redrive =
    io.eitheror.casino.pspgateway.sessions.Redrive(DepositSession.Timer, at)

  /** Scheduled at start; scheduling again with the same period keeps its next due (`createRecurringTimer`). */
  def schedule(scheduler: TimerScheduler, every: FiniteDuration): Unit =
    scheduler.createRecurringTimer(TimerName, every, every, run.deferred)
