package io.eitheror.casino.engagement

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime, ViewClient}

/** Every component of the service (plan.md's structure), registered by the service and by its test kit alike. */
object EngagementService:
  val descriptors: Seq[ComponentDescriptor] = Seq(
    profile.Profile.descriptor,
    config.Configuration.descriptor,
    send.Send.descriptor,
    channel.Channel.descriptor,
    channel.AgentTicket.descriptor,
    channel.AgentChannel.descriptor,
    reads.SendsView.descriptor,
    reads.ProfilesView.descriptor,
    reads.PresenceView.descriptor,
    facts.SendAudit.descriptor,
    facts.ConfigAudit.descriptor,
    consumers.PlayersConsumer.descriptor,
    consumers.MoneyConsumer.descriptor,
    consumers.PaymentsConsumer.descriptor,
    consumers.SessionsConsumer.descriptor,
    consumers.WithdrawalsConsumer.descriptor,
    consumers.RewardsConsumer.descriptor,
    timers.DepositDayTimer.descriptor,
    facts.PushPublisher.descriptor,
    timers.SweepTimer.descriptor
  )

  /** One endpoint per prefix (contracts/routes.md), none sharing one; each user story adds its own. */
  def endpoints(authenticate: () => Acl, project: String = ""): Seq[EndpointClients => HttpEndpoint] = Seq(
    clients => api.BackofficeEndpoint(api.Ctx(authenticate, clients, project)),
    clients => api.SendEndpoint(api.Ctx(authenticate, clients, project)),
    clients => api.WebhookEndpoint(api.Ctx(authenticate, clients, project)),
    clients => api.StreamEndpoint(api.Ctx(authenticate, clients, project)),
    clients => api.ConfigEndpoint(api.Ctx(authenticate, clients, project))
  )

  /** Once the service has started: the timers and the views reachable from the components. */
  def started(timers: TimerRuntime, views: ViewClient): Unit =
    EngagementRuntime.timers = () => timers.timerScheduler
    EngagementRuntime.views = () => views
    api.Stream.resume()
    io.eitheror.casino.engagement.timers.DepositDayReset.schedule(EngagementRuntime.timers())

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(e => throw IllegalStateException(e), identity)
  EngagementRuntime.wire(settings)
  val log    = org.slf4j.LoggerFactory.getLogger("engagement")
  val timers = TimerRuntime()
  // A local run with no broker reads and publishes in memory, so the service starts without Kafka; an installation sets one.
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      log.warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are read and published in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  val builder = Ankka.service
    .registerAll(EngagementService.descriptors)
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(EngagementService.endpoints(() => Oidc.authenticate())*))
    // An erasure filed at the installation reaches the CRM too (R15, feature 042), as the route `players` calls does.
    .withErasureHandler(api.Erasure.handler)
  // The installation names its keyring for a service with personal fields; a local run keeps its keys in its own
  // database (feature 042).
  val keyed =
    if sys.env.get("ANKKA_KEYRING_ADDRESS").exists(_.trim.nonEmpty) then builder
    else
      log.warn("ANKKA_KEYRING_ADDRESS is not set: personal fields are sealed under keys in this service's database (a local run)")
      builder.withLocalKeyring()
  val service = keyed.start(name = "engagement")
  EngagementService.started(timers, service.viewClient)
  // Every open stream is told `shutting_down` at its next tick, a second at most, before the server stops.
  sys.addShutdownHook {
    api.Stream.stop()
    Thread.sleep(settings.pollEvery.toMillis + 500)
    service.terminate()
  }
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
