package io.eitheror.casino.reporting

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime, ViewClient}
import com.thinkmorestupidless.ankka.sdk.TimerScheduler
import io.eitheror.casino.reporting.intake.*

import java.time.{Clock, Instant}

/**
 * Every component of the service (plan.md's structure), registered by the service and by its test kit alike. The
 * consumers are built for the settings' generation (research R8); `clock` dates a correction's arrival.
 */
object ReportingService:
  def descriptors(settings: Settings, clock: Clock = Clock.systemUTC()): Seq[ComponentDescriptor] = Seq(
    WalletBook.descriptor,
    MoneyFacts(settings).descriptor,
    Payment.descriptor,
    PaymentsFacts(settings).descriptor,
    Withdrawal.descriptor,
    WithdrawalsFacts(settings).descriptor,
    Grant.descriptor,
    PlayerLog.descriptor,
    PlayersFacts(settings).descriptor,
    Session.descriptor,
    SessionsFacts(settings).descriptor,
    RoundCheck.descriptor,
    RoundLegs(settings).descriptor,
    Coverage.descriptor,
    io.eitheror.casino.reporting.settings.ReportingSettings.descriptor,
    documents.Document.descriptor,
    day.BrandDay.descriptor,
    day.OpenDays.descriptor,
    day.DaysView.descriptor,
    day.CloseDays.descriptor,
    reads.Wallets.descriptor,
    reads.Players.descriptor,
    reads.Sessions.descriptor,
    reads.PaymentsView.descriptor,
    reads.WithdrawalsView.descriptor,
    reads.Rounds.descriptor,
    reads.PlayerDays.descriptor,
    reads.Activity.descriptor,
    reads.Transactions.descriptor
  ) ++ day.ToDaysConsumers.all(settings, clock) ++ casino(settings)

  /**
   * The one endpoint (research R13). Its prefix names the brand of the settings `wire` set, so `wire` comes first;
   * `now` is the clock "today" and a document's times are read from.
   */
  def endpoints(authenticate: () => Acl, now: () => Instant = () => Instant.now()): Seq[EndpointClients => HttpEndpoint] = Seq(
    clients => api.ReportingEndpoint(api.Ctx(clients, authenticate, now))
  )

  /** What the timed actions and the endpoint are constructed without (see `ReportingRuntime`), before the service starts. */
  def wire(settings: Settings): Unit = ReportingRuntime.settings.set(settings)

  /** The reads that need the running service's view client, and the recurring timers; at every start. */
  def started(settings: Settings, views: ViewClient, timers: TimerScheduler): Unit =
    ReportingRuntime.openBefore.set(d =>
      views.forView(day.OpenDays).ask(day.OpenDays.openBefore, 10_000, "generation" -> settings.generation.toString, "day" -> d))
    day.CloseDays.schedule(timers)

  /** `casino.rewards` and `casino.rounds` exist to be read only when the brand runs the casino module (R12). */
  private def casino(settings: Settings): Seq[ComponentDescriptor] =
    if !settings.casino then Nil
    else Seq(RewardsFacts(settings).descriptor, RoundsFacts(settings).descriptor)

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(e => throw IllegalStateException(e), identity)
  ReportingService.wire(settings)
  val log    = org.slf4j.LoggerFactory.getLogger("reporting")
  val timers = TimerRuntime()
  // A local run with no broker reads in memory, so the service starts without Kafka; an installation sets one.
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      log.warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are read in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  val builder = Ankka.service
    .registerAll(ReportingService.descriptors(settings))
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(ReportingService.endpoints(() => Oidc.authenticate())*))
  // The installation names its keyring for a service with personal fields; a local run keeps its keys in its own
  // database (feature 042).
  val keyed =
    if sys.env.get("ANKKA_KEYRING_ADDRESS").exists(_.trim.nonEmpty) then builder
    else
      log.warn("ANKKA_KEYRING_ADDRESS is not set: personal fields are sealed under keys in this service's database (a local run)")
      builder.withLocalKeyring()
  val service = keyed.start(name = "reporting")
  ReportingService.started(settings, service.viewClient, timers.timerScheduler)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
