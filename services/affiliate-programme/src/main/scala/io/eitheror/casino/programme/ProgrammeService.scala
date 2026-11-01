package io.eitheror.casino.programme

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.sdk.TimerScheduler
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.activity.AuditEntry
import io.eitheror.casino.programme.api.*
import io.eitheror.casino.programme.brands.{BrandProgramme, Plan, Seed}
import io.eitheror.casino.programme.comms.{Communications, CustomerIo, EmailSend, Notification}
import io.eitheror.casino.programme.intake.{BrandConsumers, FactInbox, Inbox}
import io.eitheror.casino.programme.ledger.{CommissionAccount, Ledger, ToLedger, Unsettled}
import io.eitheror.casino.programme.links.{Link, SigningKeys}
import io.eitheror.casino.programme.players.{PlayerAttribution, Players}
import io.eitheror.casino.programme.settlement.{Payout, Payouts}

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
      AuditEntry.descriptor,
      Notification.descriptor,
      EmailSend.descriptor,
      Accounts.descriptor,
      Memberships.descriptor,
      Players.descriptor,
      Payouts.descriptor,
      Inbox.descriptor,
      Ledger.descriptor,
      Unsettled.descriptor,
      ToLedger.descriptor,
      Communications.descriptor,
      Seed.descriptor
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
  def wire(settings: Settings, customerIo: CustomerIo, clock: Clock): Unit =
    Wiring.settings.set(settings)
    Wiring.clock.set(clock)
    EmailSend.customerIo.set(customerIo)
    EmailSend.clock.set(clock)

  /** At every start: the seed, once, a moment after the service is up (setting it again replaces it). */
  def started(timers: TimerScheduler): Unit =
    timers.createSingleTimer(Seed.TimerName, 1.second, Seed.seed.deferred)

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
  ProgrammeService.wire(settings, CustomerIo.http(settings.outboundTimeout), Clock.systemUTC())
  val service = Ankka.service
    .registerAll(ProgrammeService.descriptors(settings))
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(ProgrammeService.endpoints(settings, () => Oidc.authenticate(), ProgrammeService.realm(settings))*))
    .start()
  ProgrammeService.start(timers)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
