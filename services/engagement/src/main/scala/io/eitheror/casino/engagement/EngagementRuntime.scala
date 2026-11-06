package io.eitheror.casino.engagement

import java.time.Instant

/**
 * What the components and the endpoints share that is the service's: the settings, the clock and the timers.
 * The CRM and the neighbours join it as their clients are built. A suite wires its own, with a clock it moves.
 */
object EngagementRuntime:
  @volatile var settings: Settings = Settings()
  @volatile var now: () => Instant = () => Instant.now()

  @volatile var timers: () => com.thinkmorestupidless.ankka.sdk.TimerScheduler =
    () => throw IllegalStateException("the timers are not wired: EngagementService sets EngagementRuntime.timers at start")

  def wire(s: Settings, clock: () => Instant = () => Instant.now()): Unit =
    settings = s
    now = clock
