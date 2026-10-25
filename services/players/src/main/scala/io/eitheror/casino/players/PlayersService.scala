package io.eitheror.casino.players

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime}
import io.eitheror.casino.players.api.*
import io.eitheror.casino.players.config.{AllowListEntity, BrandConfigEntity, CurrencyConfigEntity}
import io.eitheror.casino.players.facts.*
import io.eitheror.casino.players.fraud.{FraudCaseEntity, FraudCasesView}
import io.eitheror.casino.players.inbox.{MessageEntity, MessagesView, NoteEntity, NotesView}
import io.eitheror.casino.players.player.{PlayerEntity, PlayersView}
import io.eitheror.casino.players.reads.*
import io.eitheror.casino.players.registration.{LookupIndexEntity, PendingRegistration, RegistrationWorkflow}
import io.eitheror.casino.players.sessions.*

/** Every component of the service (plan.md's table), registered by the service and by its test kit alike. */
object PlayersService:

  val descriptors: Seq[ComponentDescriptor] = Seq(
    PlayerEntity.descriptor,
    PlayerSessionEntity.descriptor,
    GameSessionEntity.descriptor,
    MessageEntity.descriptor,
    NoteEntity.descriptor,
    FraudCaseEntity.descriptor,
    LookupIndexEntity.descriptor,
    PendingRegistration.descriptor,
    RegistrationWorkflow.descriptor,
    SessionEndWorkflow.descriptor,
    BrandConfigEntity.descriptor,
    CurrencyConfigEntity.descriptor,
    AllowListEntity.descriptor,
    PlayersView.descriptor,
    SessionsView.descriptor,
    MessagesView.descriptor,
    NotesView.descriptor,
    FraudCasesView.descriptor,
    WalletSummaryView.descriptor,
    RecentlyPlayedView.descriptor,
    ActivityView.descriptor,
    ActivityEntry.descriptor,
    RecentlyPlayed.descriptor,
    MoneyActivity.descriptor,
    RoundsActivity.descriptor,
    RewardsActivity.descriptor,
    PlayersActivity.descriptor,
    SessionsActivity.descriptor,
    PlayersPublisher.descriptor,
    MessagesPublisher.descriptor,
    LoginsPublisher.descriptor,
    PlayerSessionsPublisher.descriptor,
    GameSessionsPublisher.descriptor,
    AuditPublisher.descriptor,
    MessageAudit.descriptor,
    NoteAudit.descriptor,
    FraudCaseAudit.descriptor,
    GameSessionAudit.descriptor,
    RgLimitsConsumer.descriptor,
    KycFactsConsumer.descriptor,
    WithdrawalsConsumer.descriptor,
    PushDeliveredConsumer.descriptor,
    RewardsConsumer.descriptor,
    SessionLimits.descriptor,
    RealmReconcile.descriptor
  )

  /** One endpoint per prefix (contracts/routes.md), none sharing one. */
  def endpoints(authenticate: () => Acl): Seq[EndpointClients => HttpEndpoint] =
    def ctx(c: EndpointClients) = Ctx(authenticate, c)
    Seq[EndpointClients => HttpEndpoint](
      c => PlayerEndpoint(ctx(c)),
      c => SessionEndpoint(ctx(c)),
      c => OperatorEndpoint(ctx(c)),
      c => ModulesEndpoint(ctx(c)),
      c => ServiceEndpoint(ctx(c)),
      c => RealmEndpoint(ctx(c)),
      c => GeoEndpoint(ctx(c))
    )

  /** Once the service has started: the timers reachable from the consumers, and the realm's reconciler. */
  def started(timers: TimerRuntime, views: com.thinkmorestupidless.ankka.runtime.ViewClient): Unit =
    PlayersRuntime.timers = () => timers.timerScheduler
    PlayersRuntime.views = () => views
    RealmReconcile.schedule(timers.timerScheduler)

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(e => throw IllegalStateException(e), identity)
  PlayersRuntime.wire(settings)
  val timers = TimerRuntime()
  // fromEnv: on an installation the broker is a TLS listener known by the service's own certificate. A local run
  // with no broker reads and publishes in memory, so the service starts without Kafka; an installation sets one.
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      org.slf4j.LoggerFactory.getLogger("players").warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are read and published in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  val service = Ankka.service
    .registerAll(PlayersService.descriptors)
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(PlayersService.endpoints(() => Oidc.authenticate())*))
    .start(name = "players") // the name the local console and `ankka mcp` list it under
  PlayersService.started(timers, service.viewClient)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
