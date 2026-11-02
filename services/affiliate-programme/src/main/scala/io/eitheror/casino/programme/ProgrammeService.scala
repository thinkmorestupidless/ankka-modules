package io.eitheror.casino.programme

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime, ViewClient}
import com.thinkmorestupidless.ankka.sdk.TimerScheduler
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.activity.{Activity, AuditEntry, Creative, Creatives, FraudFlag, FraudFlags, Note}
import io.eitheror.casino.programme.api.*
import io.eitheror.casino.programme.brands.{BrandProgramme, Plan, Plans, Seed}
import io.eitheror.casino.programme.comms.{Communications, CustomerIo, EmailSend, FlagNotices, Notification, PayoutNotices}
import io.eitheror.casino.programme.postbacks.{Postback, PostbackOutcome, PostbackOutcomes, PostbackRetry}
import io.eitheror.casino.programme.intake.{BrandConsumers, FactInbox, Inbox}
import io.eitheror.casino.programme.ledger.{CommissionAccount, Ledger, Overrides, ToLedger, Unsettled}
import io.eitheror.casino.programme.links.{BonusCode, BonusCodes, Clicks, InviteLink, InviteLinks, Link, Links, Revocations, RevocationsStamp, SigningKeys}
import io.eitheror.casino.programme.players.{ClickRecord, PlayerAttribution, PlayerFacts, Players}
import io.eitheror.casino.programme.settlement.{Close, CloseRun, Payout, Payouts}

import java.time.{Clock, Instant}
import scala.concurrent.duration.*

/** Every component of the service (data-model.md), registered by the service and by its test kit alike. */
object ProgrammeService:

  /** A consumer per brand whose topic this programme reads (`PROGRAMME_BRANDS`). */
  def descriptors(settings: Settings): Seq[ComponentDescriptor] =
    Seq(
      BrandProgramme.descriptor,
      Plan.descriptor,
      Affiliate.descriptor,
      Membership.descriptor,
      EmailIndex.descriptor,
      Token.descriptor,
      FactInbox.descriptor,
      PlayerAttribution.descriptor,
      CommissionAccount.descriptor,
      Payout.descriptor,
      SigningKeys.descriptor,
      Link.descriptor,
      InviteLink.descriptor,
      AuditEntry.descriptor,
      Notification.descriptor,
      EmailSend.descriptor,
      Accounts.descriptor,
      Memberships.descriptor,
      Players.descriptor,
      PlayerFacts.descriptor,
      Plans.descriptor,
      InviteLinks.descriptor,
      Activity.descriptor,
      Payouts.descriptor,
      Close.descriptor,
      CloseRun.descriptor,
      Inbox.descriptor,
      Ledger.descriptor,
      Unsettled.descriptor,
      ToLedger.descriptor,
      Overrides.descriptor,
      Communications.descriptor,
      Provisioning.descriptor,
      Seed.descriptor,
      BonusCode.descriptor,
      ClickRecord.descriptor,
      Links.descriptor,
      Revocations.descriptor,
      BonusCodes.descriptor,
      Clicks.descriptor,
      RevocationsStamp.descriptor,
      FraudFlag.descriptor,
      FraudFlags.descriptor,
      Note.descriptor,
      Creative.descriptor,
      Creatives.descriptor,
      FlagNotices.descriptor,
      PayoutNotices.descriptor,
      Postback.descriptor,
      PostbackRetry.descriptor,
      PostbackOutcome.descriptor,
      PostbackOutcomes.descriptor,
      Erasure.descriptor,
      ErasureDue.descriptor
    ) ++ BrandConsumers.of(settings.brands).map(_.descriptor)

  /** The realm the service provisions in, or none configured (a local run). */
  def realm(settings: Settings): KeycloakAdmin =
    settings.keycloakUrl.fold(KeycloakAdmin.unconfigured) { url =>
      KeycloakAdmin.http(url, settings.keycloakRealm, () =>
        settings.keycloakAdminUsername.zip(settings.keycloakAdminPassword).map(KeycloakAdmin.Credentials.apply))
    }

  /** The four endpoints of contracts/routes.md, one per prefix. */
  def endpoints(settings: Settings, authenticate: () => Acl, realm: KeycloakAdmin, now: () => Instant = () => Instant.now()): Seq[EndpointClients => HttpEndpoint] =
    val access = Access(authenticate())
    def api(clients: EndpointClients) = Api(settings, clients, access, realm, now)
    Seq[EndpointClients => HttpEndpoint](
      clients => OperatorEndpoint(api(clients)),
      clients => PortalEndpoint(api(clients)),
      clients => JoinEndpoint(api(clients)),
      clients => ProgrammeEndpoint(api(clients))
    )

  /** What the consumers, the timed actions and the workflows' steps are constructed without (see `Wiring`). */
  def wire(settings: Settings, customerIo: CustomerIo, clock: Clock, realm: KeycloakAdmin): Unit =
    Wiring.settings.set(settings)
    Wiring.realm.set(realm)
    Wiring.clock.set(clock)
    EmailSend.customerIo.set(customerIo)
    EmailSend.clock.set(clock)
    Postback.http.set(Postback.get(settings.outboundTimeout))

  /** What a workflow's steps read of the views, which exist only once the service has started. */
  def wireViews(views: ViewClient): Unit =
    Wiring.owed.set((brand, period, after) => views.forView(Unsettled).ask(Unsettled.owed, "brand" -> brand, "period" -> period, "after" -> after))

  /** At every start: the seed, once, a moment after the service is up (setting it again replaces it). */
  def started(timers: TimerScheduler): Unit =
    Postback.timers.set(Some(timers))
    Erasure.timers.set(Some(timers))
    timers.createSingleTimer(Seed.TimerName, 1.second, Seed.seed.deferred)
    timers.createRecurringTimer(RevocationsStamp.TimerName, 1.second, RevocationsStamp.RefreshSeconds.seconds, RevocationsStamp.stamp.deferred)

  def start(timers: TimerRuntime): Unit = started(timers.timerScheduler)

@main def run(): Unit =
  val settings = Settings.fromEnvironment(sys.env).fold(problem => { System.err.println(problem); sys.exit(2) }, identity)
  val timers   = TimerRuntime()
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      org.slf4j.LoggerFactory.getLogger("programme").warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are read in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  val realm = ProgrammeService.realm(settings)
  ProgrammeService.wire(settings, CustomerIo.http(settings.outboundTimeout), Clock.systemUTC(), realm)
  val service = Ankka.service
    .registerAll(ProgrammeService.descriptors(settings))
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(ProgrammeService.endpoints(settings, () => Oidc.authenticate(), realm)*))
    .start()
  ProgrammeService.wireViews(service.viewClient)
  ProgrammeService.start(timers)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
