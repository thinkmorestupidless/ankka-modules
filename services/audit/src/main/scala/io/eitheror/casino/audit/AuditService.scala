package io.eitheror.casino.audit

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.sdk.TimerScheduler
import io.eitheror.casino.audit.ingest.{Consumers, Quarantine, Quarantined, SourceState}
import io.eitheror.casino.audit.registry.{Identity, Pseudonym, Seed}
import io.eitheror.casino.audit.store.{Record, Records}
import org.slf4j.LoggerFactory

import java.time.Clock
import scala.concurrent.duration.*

/** Every component of the service (plan.md's structure), registered by the service and by its test kit alike. */
object AuditService:
  def descriptors(settings: Settings): Seq[ComponentDescriptor] = Seq(
    Record.descriptor,
    Records.descriptor,
    Pseudonym.descriptor,
    Identity.descriptor,
    Quarantined.descriptor,
    Quarantine.descriptor,
    SourceState.descriptor,
    Seed.descriptor,
    exports.Export.descriptor,
    exports.ExportFormat.descriptor
  ) ++ Consumers.of(settings).map(_.descriptor)

  /** One endpoint per prefix (contracts/routes.md), none sharing one; each user story adds its own. */
  def endpoints(authenticate: () => Acl): Seq[EndpointClients => HttpEndpoint] = Seq(
    clients => api.RecordsEndpoint(api.Ctx(authenticate, clients)),
    clients => api.ExportsEndpoint(api.Ctx(authenticate, clients)),
    clients => api.IdentitiesEndpoint(api.Ctx(authenticate, clients))
  )

  /** What the consumers and timed actions are constructed without (see `Wiring`). */
  def wire(settings: Settings, clock: Clock): Unit =
    Wiring.settings.set(settings)
    Wiring.clock.set(clock)

  /** At every start: the seed, once, a moment after the service is up (setting it again replaces it). */
  def started(timers: TimerScheduler): Unit =
    timers.createSingleTimer(Seed.TimerName, 1.second, Seed.seed.deferred)

@main def run(): Unit =
  val log      = LoggerFactory.getLogger("audit")
  val settings = Settings.fromEnvironment(sys.env).fold(problem => { System.err.println(problem); sys.exit(2) }, identity)
  val timers   = TimerRuntime()
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      log.warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: the brands' records are read in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  AuditService.wire(settings, Clock.systemUTC())
  val service = Ankka.service
    .registerAll(AuditService.descriptors(settings))
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(AuditService.endpoints(() => Oidc.authenticate())*))
    .start(name = "audit")
  Wiring.viewClient.set(Some(service.viewClient))
  AuditService.started(timers.timerScheduler)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
