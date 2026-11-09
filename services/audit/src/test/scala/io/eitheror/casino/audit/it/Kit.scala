package io.eitheror.casino.audit.it

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, MovableClock, TimerProbe}
import io.eitheror.casino.audit.{AuditService, Settings, Wiring}
import io.eitheror.casino.audit.query.Filters
import io.eitheror.casino.audit.registry.{QuarantineKey, RegistryKey, Seed}
import io.eitheror.casino.audit.store.Records
import io.eitheror.casino.audit.stored.Stored
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.facts.{AuditRecord, ModuleChange, PlatformAudit, PlatformAuditFact, PlatformModules}

import java.time.Instant
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * The whole audit against a throwaway Postgres (ankka's integration kit): an in-memory broker carrying
 * `platform.audit` and `platform.modules` of two brands, `goldvibe` and `spinvibe`, and `payments`'
 * `platform.audit`; timers on a clock the test moves; the object store in memory. A stand-in for
 * Keycloak reads a bearer token `staff|<id>|<roles>` as a member of staff holding those roles.
 */
final class Kit(val brands: Vector[String] = Vector("goldvibe", "spinvibe"), configure: Settings => Settings = identity):
  val broker = InMemoryBroker()
  val clock  = MovableClock.at(Instant.parse("2026-11-09T08:00:00Z"))
  val probe  = TimerProbe()

  val settings = configure(Settings(brands = brands))

  val timers = TimerRuntime(200.millis, probe, clock)

  RegistryKey.forget()
  QuarantineKey.forget()
  AuditService.wire(settings, clock)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    AuditService.descriptors(settings),
    Seq(ProjectionRuntime.withBroker(broker, broker), timers)
  )
  probe.bind(testKit)
  // The seed the start timer runs, run now so every suite begins with its keys.
  Seed.run(testKit.secrets)

  def components = testKit.componentClient
  def views      = testKit.service.viewClient
  def secrets    = testKit.secrets

  /** Points the process-wide wiring back at this kit, after another kit in the same JVM took it. */
  def rewire(): Unit =
    RegistryKey.forget()
    QuarantineKey.forget()
    AuditService.wire(settings, clock)
    Seed.run(secrets)

  def stop(): Unit = testKit.stop()

  def fresh(prefix: String): String = s"$prefix-${UUID.randomUUID().toString.take(8)}"

  def now: Instant = clock.instant()

  def moveClock(to: Instant): Unit = clock.moveTo(to)

  // ── the broker ──────────────────────────────────────────────────────────────────────────────────

  private def send(topic: String, key: String, bytes: Array[Byte], contract: String, id: Option[String]): Unit =
    val meta = Metadata.empty.set(Metadata.CeSubject, key).set(Metadata.CeType, contract)
    Await.result(broker.publish(topic, Some(key), bytes, id.fold(meta)(i => meta.set("ce-id", i))), 10.seconds): Unit

  /** A record on `project`'s `platform.audit`, as its services' audit consumers publish it. */
  def publish(project: String, record: AuditRecord): Unit =
    send(s"$project/${PlatformAudit.name}", record.key, PlatformAudit.serializer.toBytes(record: PlatformAuditFact),
      PlatformAudit.contractName, Some(record.recordId))

  /** Bytes as they are, on `project`'s `platform.audit`: a malformed record. */
  def publishBytes(project: String, key: String, bytes: Array[Byte]): Unit =
    send(s"$project/${PlatformAudit.name}", key, bytes, PlatformAudit.contractName, None)

  /** A module change on a brand's `platform.modules`, as the vendor's agent writes it. */
  def publishModule(brand: String, change: ModuleChange): Unit =
    send(s"$brand/${PlatformModules.name}", change.key, PlatformModules.serializer.toBytes(change), PlatformModules.contractName, None)

  // ── reads ───────────────────────────────────────────────────────────────────────────────────────

  def records: Vector[Stored] = views.forView(Records).ordered(io.eitheror.casino.audit.query.Query.condition(Filters()),
    io.eitheror.casino.audit.query.Query.Oldest, 100000)

  /** Waits until `check` holds, polling, for at most `within`. */
  def eventually[A](within: FiniteDuration = 20.seconds)(check: => Option[A]): A =
    val deadline = System.nanoTime() + within.toNanos
    var result   = check
    while result.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(100)
      result = check
    result.getOrElse(throw AssertionError(s"not within $within"))

object Kit:
  private val Issuer = "https://auth.example/realms/eitheror"

  /** Stands in for Keycloak: what is tested is what the route classes decide once a token is verified. */
  val authenticate: Acl = Acl.Authenticate { context =>
    context.header("authorization").map(_.stripPrefix("Bearer ")) match
      case None => AuthDecision.Unauthenticated("realm=\"eitheror\"")
      case Some(token) =>
        token.split('|').toList match
          case "staff" :: id :: roles :: Nil =>
            AuthDecision.Allow(Principal(id, None, None, false, roles.split(',').filter(_.nonEmpty).toSet, Map("iss" -> Issuer),
              Some(Routes.StaffIssuer)))
          case _ => AuthDecision.Unauthenticated("realm=\"eitheror\", error=\"invalid_token\"")
  }

  def record(brand: String = "goldvibe", id: String = UUID.randomUUID().toString, service: String = "wallet",
      action: String = "wallet.adjust", actorKind: String = "operator", actorId: String = "op-1", approverId: Option[String] = None,
      subjectKind: String = "player", subjectId: String = "p-1", outcome: String = "done", reasonCode: Option[String] = None,
      changes: Vector[io.eitheror.casino.contract.facts.AuditChange] = Vector.empty, correlationId: String = "corr-1",
      causationId: String = "cause-1", category: String = "operator", occurredAt: Instant = Instant.parse("2026-11-09T07:59:00Z"))
      : AuditRecord =
    AuditRecord(id, brand, service, action, actorKind, actorId, approverId, subjectKind, subjectId, outcome, reasonCode, changes,
      correlationId, causationId, category, occurredAt)
