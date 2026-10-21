package io.eitheror.casino.merchant.operators

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.merchant.deposits.DepositRuntime

import scala.concurrent.duration.*

/**
 * The realm's enabled flags mirrored onto the operators (FR-028), on a recurring timer (ankka 032, in
 * 0.11.0), replacing the original's in-process `OperatorStatusSyncer`, which a restart lost. A realm
 * that cannot be read fails the run, and the timer runs it again.
 */
final class OperatorSync(context: TimedActionContext) extends TimedAction:
  def sync: Effect =
    val settings = DepositRuntime.settings
    OperatorsRuntime.keycloak(settings, context.secrets).listUsers() match
      case Left(why) => throw IllegalStateException(s"the operators' realm could not be read: $why")
      case Right(users) =>
        val at = DepositRuntime.now()
        users.foreach { u =>
          val op = context.componentClient.forEventSourcedEntity(EntityId(u.id))
          if op.call(Operator.get).invoke().exists then op.call(Operator.mirrorEnabled).invoke(MirrorEnabled(u.enabled, at)): Unit
        }
        effects.done()

object OperatorSync extends TimedAction.Companion[OperatorSync](ComponentId("operator-sync")):
  def create(context: TimedActionContext) = new OperatorSync(context)
  val sync = handler("sync")(_.sync)

  val TimerName = "operator-sync"

  /** Scheduled at start; scheduling again with the same period keeps its next due (`createRecurringTimer`). */
  def schedule(scheduler: TimerScheduler, every: FiniteDuration): Unit =
    scheduler.createRecurringTimer(TimerName, every, every, sync.deferred)
