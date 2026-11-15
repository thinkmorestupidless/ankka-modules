package io.eitheror.casino.backoffice

import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.TimerScheduler
import io.eitheror.casino.backoffice.session.RealmClient

import java.time.Instant

/**
 * What the components share that the runtime hands none of them: the realm (a workflow's step and the
 * erasure handler call it), the clock, and the view client and timers (the endpoints and the erasure
 * handler use them). The service sets each at start; a suite wires its own.
 */
object StateRuntime:
  @volatile var realm: RealmClient = RealmClient.unconfigured
  @volatile var now: () => Instant = () => Instant.now()

  @volatile var views: () => ViewClient =
    () => throw IllegalStateException("the views are not wired: BackofficeStateService sets StateRuntime.views at start")

  @volatile var timers: () => TimerScheduler =
    () => throw IllegalStateException("the timers are not wired: BackofficeStateService sets StateRuntime.timers at start")
