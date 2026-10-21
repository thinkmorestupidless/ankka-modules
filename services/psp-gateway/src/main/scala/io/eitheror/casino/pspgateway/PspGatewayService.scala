package io.eitheror.casino.pspgateway

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime, TimerRuntime}
import io.eitheror.casino.pspgateway.api.*
import io.eitheror.casino.pspgateway.delivery.{DepositDeliverer, PayoutDeliverer, Redrive}
import io.eitheror.casino.pspgateway.sessions.*

import java.time.Instant

/** Every component of the service (data-model.md), registered by the service and by its test kit alike. */
object PspGatewayService:

  val descriptors: Seq[ComponentDescriptor] = Seq(
    DepositSession.descriptor,
    PayoutSession.descriptor,
    ReferenceIndex.descriptor,
    DepositDeliverer.descriptor,
    PayoutDeliverer.descriptor,
    DepositRows.descriptor,
    PayoutRows.descriptor,
    Redrive.descriptor
  )

  /** One endpoint per prefix (contracts/routes.md); nested prefixes by ankka's longest match. */
  def endpoints(settings: Settings, authenticate: () => Acl, now: () => Instant = () => Instant.now()): Seq[EndpointClients => HttpEndpoint] =
    def ctx(c: EndpointClients) = Ctx(settings, authenticate, now, c)
    Seq[EndpointClients => HttpEndpoint](
      c => PspEndpoint(ctx(c)),
      c => CheckoutEndpoint(ctx(c)),
      c => WebhooksEndpoint(ctx(c)),
      c => WithdrawalsEndpoint(ctx(c)),
      c => WithdrawalTerminalsEndpoint(ctx(c)),
      c => OutcomesEndpoint(ctx(c)),
      c => StatusEndpoint(ctx(c))
    )

  /** What is scheduled once the service has started: the re-drive (FR-016), which reads the views it is given. */
  def started(timers: TimerRuntime, settings: Settings, views: com.thinkmorestupidless.ankka.runtime.ViewClient): Unit =
    Redrive.views = Some(views)
    Redrive.schedule(timers.timerScheduler,
      scala.concurrent.duration.FiniteDuration(settings.redriveInterval.toMillis, "millis"))

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(e => throw IllegalStateException(e), identity)
  GatewayRuntime.wire(settings)
  val timers   = TimerRuntime()
  val service = Ankka.service
    .registerAll(PspGatewayService.descriptors)
    .withExtension(ProjectionRuntime()) // the deliverers (consumers) run under it; nothing is published to a topic
    .withExtension(timers)
    .withExtension(HttpServer.of(PspGatewayService.endpoints(settings, () => Oidc.authenticate())*))
    .start(name = "psp-gateway") // the name the local console and `ankka mcp` list it under (T071)
  PspGatewayService.started(timers, settings, service.viewClient)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
