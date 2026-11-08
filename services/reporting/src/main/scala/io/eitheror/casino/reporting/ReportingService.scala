package io.eitheror.casino.reporting

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import io.eitheror.casino.reporting.intake.*

import java.time.Clock

/**
 * Every component of the service (plan.md's structure), registered by the service and by its test kit alike. The
 * consumers are built for the settings' generation (research R8); `clock` dates a correction's arrival.
 */
object ReportingService:
  def descriptors(settings: Settings, clock: Clock = Clock.systemUTC()): Seq[ComponentDescriptor] = Seq(
    WalletBook.descriptor,
    MoneyFacts(settings).descriptor,
    Payment.descriptor,
    PaymentsFacts(settings).descriptor,
    Withdrawal.descriptor,
    WithdrawalsFacts(settings).descriptor,
    Grant.descriptor,
    PlayerLog.descriptor,
    PlayersFacts(settings).descriptor,
    Session.descriptor,
    SessionsFacts(settings).descriptor,
    RoundCheck.descriptor,
    RoundLegs(settings).descriptor,
    Coverage.descriptor,
    day.BrandDay.descriptor,
    day.OpenDays.descriptor,
    day.CloseDays.descriptor
  ) ++ day.ToDaysConsumers.all(settings, clock) ++ casino(settings)

  /** What the timed actions are constructed without (see `ReportingRuntime`), before the service starts. */
  def wire(settings: Settings): Unit = ReportingRuntime.settings.set(settings)

  /** The reads that need the running service's view client, and the recurring timers; at every start. */
  def started(settings: Settings, views: com.thinkmorestupidless.ankka.runtime.ViewClient,
      timers: com.thinkmorestupidless.ankka.sdk.TimerScheduler): Unit =
    ReportingRuntime.openBefore.set(d =>
      views.forView(day.OpenDays).ask(day.OpenDays.openBefore, 10_000, "generation" -> settings.generation.toString, "day" -> d))
    day.CloseDays.schedule(timers)

  /** `casino.rewards` and `casino.rounds` exist to be read only when the brand runs the casino module (R12). */
  private def casino(settings: Settings): Seq[ComponentDescriptor] =
    if !settings.casino then Nil
    else Seq(RewardsFacts(settings).descriptor, RoundsFacts(settings).descriptor)
