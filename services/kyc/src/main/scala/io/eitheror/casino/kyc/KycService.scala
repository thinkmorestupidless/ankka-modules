package io.eitheror.casino.kyc

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime, ViewClient}
import io.eitheror.casino.kyc.api.{Ctx, OperatorEndpoint, PlayerEndpoint, PublicEndpoint, ServiceEndpoint}
import io.eitheror.casino.kyc.consumers.{ClosureConsumer, DepositsConsumer, PolicyFlagger, ProfileConsumer, RegistrationConsumer}
import io.eitheror.casino.kyc.consumers.PolicyFlagger.Search
import io.eitheror.casino.kyc.documents.Store
import io.eitheror.casino.kyc.facts.{AuditPublisher, KycPublisher, PolicyAuditPublisher}
import io.eitheror.casino.kyc.policy.PolicyEntity
import io.eitheror.casino.kyc.queue.KycQueue
import io.eitheror.casino.kyc.record.KycRecord
import io.eitheror.casino.kyc.timers.{RecordTimers, WindowTimer}

/** Every component of the service (plan.md's table), registered by the service and by its test kit alike. */
object KycService:

  val descriptors: Seq[ComponentDescriptor] = Seq(
    KycRecord.descriptor,
    PolicyEntity.descriptor,
    KycPublisher.descriptor,
    AuditPublisher.descriptor,
    PolicyAuditPublisher.descriptor,
    ProfileConsumer.descriptor,
    DepositsConsumer.descriptor,
    KycQueue.descriptor,
    PolicyFlagger.descriptor,
    RegistrationConsumer.descriptor,
    ClosureConsumer.descriptor,
    RecordTimers.descriptor,
    WindowTimer.descriptor
  )

  /** One endpoint per prefix (contracts/routes.md), none sharing one. */
  def endpoints(authenticate: () => Acl): Seq[EndpointClients => HttpEndpoint] =
    def ctx(c: EndpointClients) = Ctx(authenticate, c)
    Seq[Ctx => HttpEndpoint](PlayerEndpoint(_), PublicEndpoint(_), OperatorEndpoint(_), ServiceEndpoint(_)).map(e => (c: EndpointClients) => e(ctx(c)))

  /** Once the service has started: the timers, the store and the queue reachable from the components and endpoints. */
  def started(timers: TimerRuntime, store: Store, views: ViewClient): Unit =
    KycRuntime.timers = () => timers.timerScheduler
    KycRuntime.store = () => store
    val queue = views.forView(KycQueue)
    KycRuntime.queue = () => {
      case Search.Threshold(c, a) => queue.ask(KycQueue.overThreshold, 10_000, "currency" -> c, "amount" -> a.toString).map(_.playerId)
      case Search.Sof(c, a)       => queue.ask(KycQueue.overSof, 10_000, "currency" -> c, "amount" -> a.toString).map(_.playerId)
    }

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(e => throw IllegalStateException(e), identity)
  KycRuntime.wire(settings)
  val timers = TimerRuntime()
  // A local run with no broker reads and publishes in memory, so the service starts without Kafka; an installation sets one.
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      org.slf4j.LoggerFactory.getLogger("kyc")
        .warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are read and published in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  val service = Ankka.service
    .registerAll(KycService.descriptors)
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(KycService.endpoints(() => Oidc.authenticate())*))
    .start(name = "kyc")
  KycService.started(timers, Store.of(settings.bucket), service.viewClient)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
