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
    reads.SendsView.descriptor,
    reads.ProfilesView.descriptor
  )

  /** One endpoint per prefix (contracts/routes.md), none sharing one; each user story adds its own. */
  def endpoints(authenticate: () => Acl, project: String = ""): Seq[EndpointClients => HttpEndpoint] = Seq.empty

  /** Once the service has started: the timers and the views reachable from the components. */
  def started(timers: TimerRuntime, views: ViewClient): Unit =
    EngagementRuntime.timers = () => timers.timerScheduler
    EngagementRuntime.views = () => views

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
  // The installation names its keyring for a service with personal fields; a local run keeps its keys in its own
  // database (feature 042).
  val keyed =
    if sys.env.get("ANKKA_KEYRING_ADDRESS").exists(_.trim.nonEmpty) then builder
    else
      log.warn("ANKKA_KEYRING_ADDRESS is not set: personal fields are sealed under keys in this service's database (a local run)")
      builder.withLocalKeyring()
  val service = keyed.start(name = "engagement")
  EngagementService.started(timers, service.viewClient)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
