package io.eitheror.casino.payouts

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime}
import io.eitheror.casino.payouts.api.{ConfigEndpoint, Ctx, OperatorEndpoint, OutcomeEndpoint, PlayerEndpoint, ServiceEndpoint}
import io.eitheror.casino.payouts.consumers.{DepositsConsumer, PlayersConsumer, RequestsConsumer}
import io.eitheror.casino.payouts.facts.{AuditPublisher, ConfigAuditPublisher, PlayerAuditPublisher, WithdrawalPublisher}
import io.eitheror.casino.payouts.limits.WithdrawalLimits
import io.eitheror.casino.payouts.player.PlayerPayouts
import io.eitheror.casino.payouts.queue.WithdrawalQueue
import io.eitheror.casino.payouts.request.WithdrawalRequest
import io.eitheror.casino.payouts.terminals.{CatalogueTimer, TerminalCatalogue}
import io.eitheror.casino.payouts.workflow.WithdrawalWorkflow

import scala.concurrent.duration.*

/** Every component of the service (plan.md's table), registered by the service and by its test kit alike. */
object PayoutsService:
  val descriptors: Seq[ComponentDescriptor] = Seq(
    PlayerPayouts.descriptor,
    WithdrawalLimits.descriptor,
    WithdrawalRequest.descriptor,
    TerminalCatalogue.descriptor,
    CatalogueTimer.descriptor,
    PlayersConsumer.descriptor,
    DepositsConsumer.descriptor,
    RequestsConsumer.descriptor,
    WithdrawalPublisher.descriptor,
    AuditPublisher.descriptor,
    PlayerAuditPublisher.descriptor,
    ConfigAuditPublisher.descriptor,
    WithdrawalWorkflow.descriptor,
    WithdrawalQueue.descriptor
  )

  /** One endpoint per prefix (contracts/routes.md), none sharing one; each user story adds its own. */
  def endpoints(authenticate: () => Acl): Seq[EndpointClients => HttpEndpoint] =
    def ctx(c: EndpointClients) = Ctx(authenticate, c)
    Seq[Ctx => HttpEndpoint](PlayerEndpoint(_), OperatorEndpoint(_), ConfigEndpoint(_), OutcomeEndpoint(_), ServiceEndpoint(_)).map(e => (c: EndpointClients) => e(ctx(c)))

  /** Once the service has started: the timers reachable from the components, and the catalogue's refresh scheduled. */
  def started(timers: TimerRuntime): Unit =
    PayoutsRuntime.timers = () => timers.timerScheduler
    val every = PayoutsRuntime.settings.refreshEvery
    CatalogueTimer.schedule(timers.timerScheduler, PayoutsRuntime.settings.brand, every.toMillis.millis)

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(e => throw IllegalStateException(e), identity)
  PayoutsRuntime.wire(settings)
  val timers = TimerRuntime()
  // A local run with no broker reads and publishes in memory, so the service starts without Kafka; an installation sets one.
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      org.slf4j.LoggerFactory.getLogger("payouts")
        .warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are read and published in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  val service = Ankka.service
    .registerAll(PayoutsService.descriptors)
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(PayoutsService.endpoints(() => Oidc.authenticate())*))
    .start(name = "payouts")
  PayoutsService.started(timers)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
