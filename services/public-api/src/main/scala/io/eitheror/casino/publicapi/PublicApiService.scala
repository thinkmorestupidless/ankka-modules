package io.eitheror.casino.publicapi

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{EndpointClients, HttpEndpoint, HttpServer}
import io.eitheror.casino.publicapi.api.{HealthEndpoint, KeysEndpoint, Pipeline, V1Endpoint}
import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.http.Acl

import java.time.Instant
import io.eitheror.casino.publicapi.contract.{Contract, Refusals}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime}

/** Every component of the service (plan.md's structure), registered by the service and by its test kit alike. */
object PublicApiService:
  def descriptors(settings: Settings): Seq[ComponentDescriptor] =
    import records.*
    Seq(keys.AccessKey.descriptor, keys.AccessKeys.descriptor, keys.KeyWindow.descriptor, keys.KeyUse.descriptor, keys.KeyLog.descriptor,
      audit.KeyAudit(settings.brand).descriptor, audit.ReadAudit(settings.brand).descriptor,
      PlayerRecord.descriptor, SessionRecord.descriptor, RoundRecord.descriptor, BetRecord.descriptor, BetsByRoundKey.descriptor,
      TransactionRecord.descriptor, WithdrawalsByRequest.descriptor, BonusAwardRecord.descriptor,
      Players.descriptor, Sessions.descriptor, Rounds.descriptor, Bets.descriptor, Transactions.descriptor, BonusAwards.descriptor,
      stats.StatsPointer.descriptor, stats.GameFigures.descriptor, stats.GameFiguresView.descriptor, stats.PlayerFigures.descriptor,
      stats.PlayerFiguresView.descriptor, stats.ComputeStats.descriptor) ++
      Intake.descriptors

  /** At every start: what the hourly run is built without, and its timer (setting it again replaces it). */
  def started(settings: Settings, views: com.thinkmorestupidless.ankka.runtime.ViewClient,
      timers: com.thinkmorestupidless.ankka.sdk.TimerScheduler, now: Instant): Unit =
    stats.StatsRuntime.views.set(Some(views))
    stats.StatsRuntime.brand.set(settings.brand)
    stats.ComputeStats.schedule(timers, now)

  /** The contract the settings' address is served in, or why the service cannot start. */
  def contract(settings: Settings): Contract = Contract.load(settings.serverUrl).fold(e => throw IllegalStateException(e), identity)

  def endpoints(settings: Settings, contract: Contract, authenticate: () => Acl = () => Oidc.authenticate(),
      now: () => Instant = () => Instant.now(),
      catalogueOf: EndpointClients => () => Vector[io.eitheror.casino.publicapi.games.CatalogueGame] = null): Seq[EndpointClients => HttpEndpoint] =
    given scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    val load = Option(catalogueOf).getOrElse((c: EndpointClients) => io.eitheror.casino.publicapi.games.Catalogue.fromProvider(c.services, settings.brand))
    Seq(
      clients => KeysEndpoint(settings.brand, clients, authenticate, now),
      clients =>
        val usage     = api.Usage(clients.componentClient)
        val catalogue = io.eitheror.casino.publicapi.games.Catalogue(load(clients), settings.catalogueFor, now,
          scala.concurrent.duration.FiniteDuration(settings.budget.toMillis, "ms"))
        val handlers = api.RecordRoutes(settings.brand, contract, clients.viewClient).handlers ++
          api.GameRoutes(settings.brand, contract, catalogue, clients.componentClient, clients.viewClient).handlers ++
          api.PlayerStatsRoutes(contract, clients.componentClient, clients.viewClient).handlers
        V1Endpoint(contract, Pipeline(settings, contract, clients, now, handlers, usage.rate, usage.served(now))(_, _),
          c => if c.authorization.isEmpty then Refusals.keyMissing.respond else Refusals.notFound.respond,
          (brand, player) =>
            if brand != settings.brand then Refusals.notFound.respond
            else Refusals.json(200, api.Erasure.erase(clients.componentClient, player.toLowerCase))),
      clients => api.StatusEndpoint(settings.brand, clients, authenticate, now),
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
    .withErasureHandler(api.Erasure.handler)
    .withExtension(HttpServer.of(PublicApiService.endpoints(settings, PublicApiService.contract(settings))*))
  // The installation names its keyring for a service with personal fields; a local run keeps its keys in its own
  // database (feature 042).
  val keyed =
    if sys.env.get("ANKKA_KEYRING_ADDRESS").exists(_.trim.nonEmpty) then builder
    else
      log.warn("ANKKA_KEYRING_ADDRESS is not set: personal fields are sealed under keys in this service's database (a local run)")
      builder.withLocalKeyring()
  val service = keyed.start(name = "public-api")
  PublicApiService.started(settings, service.viewClient, timers.timerScheduler, Instant.now())
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
