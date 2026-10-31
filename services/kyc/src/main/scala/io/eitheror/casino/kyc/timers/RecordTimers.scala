package io.eitheror.casino.kyc.timers

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.kyc.KycRuntime
import io.eitheror.casino.kyc.record.{KycEvent, KycRecord}
import io.eitheror.casino.kyc.record.KycEvent.*

import java.time.{Duration, Instant}
import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters.*

/**
 * Keeps the window's timer, `kyc-window-<player>`, at the deadline the record holds (R10): set on
 * `DeadlineSet` and on a migrated record's `Opened`, replaced by name on a redelivery, cancelled on
 * `Closed`. A consumer of the record's events, so the timer is set whatever path set the deadline. A
 * deadline already past is due at once.
 */
final class RecordTimers extends Consumer[KycEvent, Nothing]:
  def onMessage(event: KycEvent): Effect =
    val player = messageContext.subject
    val timers = KycRuntime.timers()
    def set(deadline: Instant): Unit =
      timers.createSingleTimer(WindowTimer.name(player), RecordTimers.in(deadline), WindowTimer.fire.deferred(WindowDue(player, deadline)))
    event match
      case e: DeadlineSet                                 => set(e.deadline)
      case e: Opened if !e.record.closed                  => e.record.deadline.filterNot(_.handled).foreach(d => set(d.at))
      case _: Closed                                      => timers.delete(WindowTimer.name(player))
      case _                                              => ()
    effects.done()

object RecordTimers extends Consumer.Companion[RecordTimers, KycEvent, Nothing](
      ComponentId("kyc-record-timers"), ChangeSource.eventsOf(KycRecord)):
  def create(ctx: ConsumerContext) = new RecordTimers

  def in(at: Instant): FiniteDuration = Duration.between(KycRuntime.now(), at).toScala.max(FiniteDuration(0, "s"))
