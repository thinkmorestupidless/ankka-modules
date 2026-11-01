package io.eitheror.casino.exchange

import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, ServiceClients}

import java.time.Instant

/**
 * What the ports and endpoints are constructed without: the settings, the clock, and the running
 * service's clients. The ports are built before the service starts, so they reach the clients through
 * `clients`, which `ExchangeService.started` sets; the checks read a view through it, which a timed action's context does not reach (research R3, found in implementation: the running
 * service's `services` serve a call made outside a component's context).
 */
object ExchangeRuntime:
  @volatile private var current: Option[Settings]                           = None
  @volatile private var running: Option[() => (ServiceClients, ComponentClient, ViewClient)] = None
  @volatile var now: () => Instant                                          = () => Instant.now()

  def settings: Settings = current.getOrElse(throw IllegalStateException("exchange is not wired: ExchangeService.wire first"))

  def wire(s: Settings, clock: () => Instant = () => Instant.now()): Unit =
    current = Some(s)
    now = clock

  def started(clients: () => (ServiceClients, ComponentClient, ViewClient)): Unit = running = Some(clients)

  private def clients = running.getOrElse(throw IllegalStateException("exchange has not started"))()
  def services: ServiceClients         = clients._1
  def components: ComponentClient      = clients._2
  /** For the components whose context has no view client: the reconciliation's check reads its keys' view. */
  def views: ViewClient                = clients._3
