package io.eitheror.casino.gameprovider

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime, TimerRuntime}
import io.eitheror.casino.gameprovider.api.*
import io.eitheror.casino.gameprovider.catalogue.*
import io.eitheror.casino.gameprovider.facts.RoundFacts
import io.eitheror.casino.gameprovider.players.*
import io.eitheror.casino.gameprovider.rounds.*
import io.eitheror.casino.gameprovider.tickets.Ticket
import io.eitheror.casino.gameprovider.views.*

/** Every component of the service (data-model.md), registered by the service and by its test kit alike. */
object GameProviderService:

  val descriptors: Seq[ComponentDescriptor] = Seq(
    Ticket.descriptor,
    LaunchWindow.descriptor,
    KnownPlayer.descriptor,
    Round.descriptor,
    BetWorkflow.descriptor,
    Game.descriptor,
    BrandCatalogueConfiguration.descriptor,
    GameRounds.descriptor,
    RefreshStatus.descriptor,
    PlayerStatus.descriptor,
    RoundsByPlayer.descriptor,
    RoundsBySession.descriptor,
    Unacknowledged.descriptor,
    Catalogue.descriptor,
    GamePopularity.descriptor,
    RoundFacts.descriptor,
    RoundCounter.descriptor,
    CatalogueRefresh.descriptor
  )

  /** The endpoints, one per prefix (FR-002, FR-027): the root form only for ST8, as only ST8 strips the path. */
  def endpoints(settings: Settings): Seq[EndpointClients => HttpEndpoint] =
    def ctx(clients: EndpointClients) = Context(settings, clients)
    val providerPaths = settings.adapters.map(a => (clients: EndpointClients) => ProviderPathEndpoint(ctx(clients), a.name): HttpEndpoint)
    val root          = settings.adapter("st8").toVector.map(_ => (clients: EndpointClients) => RootCallbackEndpoint(ctx(clients), "st8"): HttpEndpoint)
    Seq[EndpointClients => HttpEndpoint](
      clients => BrandsEndpoint(ctx(clients)),
      clients => SessionsEndpoint(ctx(clients)),
      clients => CatalogEndpoint(ctx(clients)),
      clients => BackofficeEndpoint(ctx(clients))
    ) ++ providerPaths ++ root

  /**
   * What the refresh timer reads at start (the adapters, the scheduler and the configuration cache), and
   * what the bet workflow's steps read (the brand and the clock).
   */
  def wire(settings: Settings, timers: TimerRuntime): Unit =
    BetRuntime.brand = settings.brand
    BetRuntime.now = settings.now
    RefreshRuntime.scheduler = Some(() => timers.timerScheduler)
    RefreshRuntime.adapters = settings.adapter
    RefreshRuntime.configurations = Some(settings.configurations)
    RefreshRuntime.now = settings.now

@main def run(): Unit =
  val settings = Settings.fromEnvironment(() => Oidc.authenticate())
  val timers   = TimerRuntime()
  // fromEnv, not withKafka(bootstrap): on an installation the broker is a TLS listener known by the service's
  // own certificate, which only fromEnv sets up. A plaintext client reads the TLS handshake as a message size
  // and dies of `Java heap space` at any instance size (T082, 2026-10-10).
  val broker   = ProjectionRuntime.fromEnv()
  GameProviderService.wire(settings, timers)
  val service = Ankka.service
    .registerAll(GameProviderService.descriptors)
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(GameProviderService.endpoints(settings)*))
    .start()
  RefreshRuntime.views = Some(service.viewClient)
  // The nightly refresh of every configured provider is scheduled at start, idempotently (research R11).
  val configs = settings.configurations(service.secrets)
  configs.providers.foreach(RefreshRuntime.scheduleNightly)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
