package io.eitheror.casino.attribution

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, EntityId}
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, AnkkaService, InMemoryBroker, ProjectionRuntime, TimerRuntime, ViewClient}
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, SecretStore, TimerScheduler}
import io.eitheror.casino.attribution.api.*
import io.eitheror.casino.attribution.carriers.*
import io.eitheror.casino.attribution.clicks.{Click, ClickFacts}
import io.eitheror.casino.attribution.facts.{Facts, RecordFacts, TallyFacts}
import io.eitheror.casino.attribution.inputs.*
import io.eitheror.casino.attribution.money.*
import io.eitheror.casino.attribution.networks.*
import io.eitheror.casino.attribution.record.AttributionRecord
import io.eitheror.casino.attribution.settings.SettingsEntity
import io.eitheror.casino.attribution.views.Records

import java.security.SecureRandom
import java.time.{Clock, Instant}
import java.util.Base64
import scala.concurrent.duration.*

/** Every component of the service (data-model.md), registered by the service and by its test kit alike. */
object AttributionService:

  /** `RewardsFacts` only when the brand runs the casino module, so `casino.rewards` exists to be read (R7). */
  def descriptors(settings: Settings): Seq[ComponentDescriptor] =
    Seq(
      SettingsEntity.descriptor,
      Network.descriptor,
      NetworkDocuments.descriptor,
      Click.descriptor,
      AttributionRecord.descriptor,
      WalletCursor.descriptor,
      RoundIndex.descriptor,
      DayTally.descriptor,
      NetworkFeed.descriptor,
      WebhookSubscription.descriptor,
      Delivery.descriptor,
      DeliveryRetry.descriptor,
      Records.descriptor,
      OpenDays.descriptor,
      FeedRows.descriptor,
      Deliveries.descriptor,
      Subscriptions.descriptor,
      Networks.descriptor,
      PlayersFacts.descriptor,
      PaymentsFacts.descriptor,
      WithdrawalsFacts.descriptor,
      MoneyFacts.descriptor,
      ClickFacts.descriptor,
      RecordFacts.descriptor,
      TallyFacts.descriptor,
      FeedAppender.descriptor,
      WebhookFanout.descriptor,
      DayCloser.descriptor,
      DocumentRefresh.descriptor
    ) ++ (if settings.casino then Seq(RewardsFacts.descriptor) else Nil)

  /** The four endpoints of contracts/routes.md, one per prefix. */
  def endpoints(settings: Settings, authenticate: () => Acl, now: () => Instant = () => Instant.now()): Seq[EndpointClients => HttpEndpoint] =
    def api(clients: EndpointClients) = Api(settings, clients, authenticate, now)
    Seq[EndpointClients => HttpEndpoint](
      clients => TrackEndpoint(api(clients)),
      clients => AttributionEndpoint(api(clients)),
      clients => BackofficeEndpoint(api(clients)),
      clients => WebhooksEndpoint(api(clients))
    )

  /** What the consumers, the timed actions and the delivery's step are constructed without (see `Wiring`). */
  def wire(settings: Settings, fetch: DocumentFetch, http: WebhookHttp, clock: Clock): Unit =
    Wiring.brand.set(settings.brand)
    Wiring.settings.set(settings)
    Wiring.fetch.set(fetch)
    Delivery.http.set(http)
    Delivery.clock.set(clock)
    Wiring.clock.set(clock)

  /** The reads that need the running service's view client. */
  def wireViews(views: ViewClient): Unit =
    Wiring.closedDays.set { player =>
      views.forView(OpenDays).ask(OpenDays.closedFor, "player" -> player)
        .flatMap(r => r.closedAt.map(at => Facts.ClosedDay(r.currency, r.day, r.stake, r.win, at)))
    }
    Wiring.openBefore.set(day => views.forView(OpenDays).ask(OpenDays.openBefore, 10_000, "day" -> day))
    Wiring.networks.set(() => views.forView(Networks).all(10_000))
    Wiring.activeSubscriptions.set(network => views.forView(Subscriptions).ask(Subscriptions.activeFor, "network" -> network).map(_.id))

  val ClickSalt = "click-salt"

  /**
   * At every start: the brand's first day (once), the two recurring timers (setting one again under its
   * name replaces it), and the click salt in the store when absent (research R5).
   */
  def started(components: ComponentClient, secrets: SecretStore, timers: TimerScheduler, settings: Settings, now: Instant): Unit =
    components.forKeyValueEntity(EntityId(SettingsEntity.Id)).call(SettingsEntity.start).invoke(Days.dayOf(now, settings.reportingZone)): Unit
    Delivery.timers.set(Some(timers))
    timers.createRecurringTimer(DayCloser.TimerName, 5.minutes, 5.minutes, DayCloser.closeDays.deferred)
    timers.createRecurringTimer(DocumentRefresh.TimerName, 1.minute, 1.minute, DocumentRefresh.refreshDocuments.deferred)
    if secrets.get(ClickSalt).isEmpty then
      val salt = new Array[Byte](32)
      SecureRandom().nextBytes(salt)
      secrets.put(ClickSalt, Base64.getEncoder.encodeToString(salt))

  def start(service: AnkkaService, timers: TimerRuntime, settings: Settings, clock: Clock): Unit =
    wireViews(service.viewClient)
    started(service.componentClient, service.secrets, timers.timerScheduler, settings, clock.instant())

@main def run(): Unit =
  val settings = Settings.fromEnvironment(sys.env).fold(problem => { System.err.println(problem); sys.exit(2) }, identity)
  val timers   = TimerRuntime()
  // fromEnv: on an installation the broker is a TLS listener known by the service's own certificate. A local run
  // with no broker reads and publishes in memory, so the service starts without Kafka; an installation sets one.
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      org.slf4j.LoggerFactory.getLogger("attribution").warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are read and published in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  AttributionService.wire(settings, DocumentFetch.http(settings.documentTimeout), WebhookHttp.client(settings.webhookTimeout), Clock.systemUTC())
  val service = Ankka.service
    .registerAll(AttributionService.descriptors(settings))
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(AttributionService.endpoints(settings, () => Oidc.authenticate())*))
    .start()
  AttributionService.start(service, timers, settings, Clock.systemUTC())
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
