package io.eitheror.casino.rg

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime}
import io.eitheror.casino.rg.api.*
import io.eitheror.casino.rg.facts.{AuditPublisher, RgPublisher}
import io.eitheror.casino.rg.profile.ProfileEntity
import io.eitheror.casino.rg.settings.BrandSettingsEntity
import io.eitheror.casino.rg.timers.*
import io.eitheror.casino.rg.usage.*

/** Every component of the service (plan.md's table), registered by the service and by its test kit alike. */
object RgService:

  val descriptors: Seq[ComponentDescriptor] = Seq(
    ProfileEntity.descriptor,
    UsageEntity.descriptor,
    SessionDayEntity.descriptor,
    BrandSettingsEntity.descriptor,
    UsageConsumer.descriptor,
    SessionMinutesConsumer.descriptor,
    RgPublisher.descriptor,
    AuditPublisher.descriptor,
    ProfileTimers.descriptor,
    PendingTimer.descriptor,
    BreakTimer.descriptor,
    ReturnTimer.descriptor,
    SyncTimer.descriptor
  )

  /** One endpoint per prefix (contracts/routes.md), none sharing one. */
  def endpoints(authenticate: () => Acl): Seq[EndpointClients => HttpEndpoint] =
    def ctx(c: EndpointClients) = Ctx(authenticate, c)
    Seq[EndpointClients => HttpEndpoint](
      c => PlayerEndpoint(ctx(c)),
      c => OperatorEndpoint(ctx(c)),
      c => ServiceEndpoint(ctx(c))
    )

  /** Once the service has started: the timers reachable from the consumers and timed actions. */
  def started(timers: TimerRuntime): Unit =
    RgRuntime.timers = () => timers.timerScheduler

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(e => throw IllegalStateException(e), identity)
  RgRuntime.wire(settings)
  val timers = TimerRuntime()
  // A local run with no broker reads and publishes in memory, so the service starts without Kafka; an installation sets one.
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      org.slf4j.LoggerFactory.getLogger("responsible-gambling")
        .warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are read and published in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  val service = Ankka.service
    .registerAll(RgService.descriptors)
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(RgService.endpoints(() => Oidc.authenticate())*))
    .start(name = "responsible-gambling")
  RgService.started(timers)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
