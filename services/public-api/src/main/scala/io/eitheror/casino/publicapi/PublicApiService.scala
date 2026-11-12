package io.eitheror.casino.publicapi

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{EndpointClients, HttpEndpoint, HttpServer}
import io.eitheror.casino.publicapi.api.{HealthEndpoint, KeysEndpoint, Serve, V1Endpoint}
import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.http.Acl

import java.time.Instant
import io.eitheror.casino.publicapi.contract.{Contract, Refusals}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime}

/** Every component of the service (plan.md's structure), registered by the service and by its test kit alike. */
object PublicApiService:
  def descriptors(settings: Settings): Seq[ComponentDescriptor] =
    val _ = settings
    Seq(keys.AccessKey.descriptor, keys.AccessKeys.descriptor)

  /** The contract the settings' address is served in, or why the service cannot start. */
  def contract(settings: Settings): Contract = Contract.load(settings.serverUrl).fold(e => throw IllegalStateException(e), identity)

  def endpoints(settings: Settings, contract: Contract, authenticate: () => Acl = () => Oidc.authenticate(),
      now: () => Instant = () => Instant.now()): Seq[EndpointClients => HttpEndpoint] =
    Seq(
      clients => KeysEndpoint(settings.brand, clients, authenticate, now),
      clients => V1Endpoint(contract, Serve(settings, contract, clients, now, Map.empty)(_, _),
        c => if c.authorization.isEmpty then Refusals.keyMissing.respond else Refusals.notFound.respond),
      _ => HealthEndpoint()
    )

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(e => throw IllegalStateException(e), identity)
  val log      = org.slf4j.LoggerFactory.getLogger("public-api")
  val timers   = TimerRuntime()
  // A local run with no broker reads in memory, so the service starts without Kafka; an installation sets one.
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      log.warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are read in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  val builder = Ankka.service
    .registerAll(PublicApiService.descriptors(settings))
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(PublicApiService.endpoints(settings, PublicApiService.contract(settings))*))
  // The installation names its keyring for a service with personal fields; a local run keeps its keys in its own
  // database (feature 042).
  val keyed =
    if sys.env.get("ANKKA_KEYRING_ADDRESS").exists(_.trim.nonEmpty) then builder
    else
      log.warn("ANKKA_KEYRING_ADDRESS is not set: personal fields are sealed under keys in this service's database (a local run)")
      builder.withLocalKeyring()
  val service = keyed.start(name = "public-api")
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
