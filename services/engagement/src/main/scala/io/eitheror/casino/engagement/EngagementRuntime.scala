package io.eitheror.casino.engagement

import io.eitheror.casino.engagement.clients.{Crm, CustomerIo}
import io.eitheror.casino.engagement.config.Secrets

import java.time.Instant

/**
 * What the components and the endpoints share that is the service's: the settings, the clock, the timers, the CRM
 * and the cache of its keys. A suite wires its own, with a clock it moves.
 */
object EngagementRuntime:
  @volatile var settings: Settings = Settings()
  @volatile var now: () => Instant = () => Instant.now()
  @volatile var crm: Crm           = CustomerIo(None, None, settings.crmTimeout)
  @volatile var secrets: Secrets   = Secrets(settings.secretsTtl, () => now())

  @volatile var timers: () => com.thinkmorestupidless.ankka.sdk.TimerScheduler =
    () => throw IllegalStateException("the timers are not wired: EngagementService sets EngagementRuntime.timers at start")

  @volatile var views: () => com.thinkmorestupidless.ankka.runtime.ViewClient =
    () => throw IllegalStateException("the views are not wired: EngagementService sets EngagementRuntime.views at start")

  /** This instance's webhook refusals by code, and its open player streams, for the status route. */
  val webhookRefusals: scala.collection.concurrent.TrieMap[String, Long] = scala.collection.concurrent.TrieMap.empty
  val connections: java.util.concurrent.atomic.AtomicInteger              = java.util.concurrent.atomic.AtomicInteger(0)

  def refused(code: String): Unit = webhookRefusals.updateWith(code)(n => Some(n.getOrElse(0L) + 1L)): Unit

  def wire(s: Settings, clock: () => Instant = () => Instant.now()): Unit =
    settings = s
    now = clock
    crm = CustomerIo(s.trackUrl, s.appUrl, s.crmTimeout)
    secrets = Secrets(s.secretsTtl, () => now())
