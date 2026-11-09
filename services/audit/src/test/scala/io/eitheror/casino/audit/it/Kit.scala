package io.eitheror.casino.audit.it

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.thinkmorestupidless.ankka.core.Metadata
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, MovableClock, TimerProbe}
import io.eitheror.casino.audit.{AuditService, Settings, Wiring}
import io.eitheror.casino.audit.query.Filters
import io.eitheror.casino.audit.registry.{QuarantineKey, RegistryKey, Seed}
import io.eitheror.casino.audit.store.Records
import io.eitheror.casino.audit.stored.Stored
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.facts.{AuditRecord, ModuleChange, PlatformAudit, PlatformAuditFact, PlatformModules}

import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.{DriverManager, Types}
import java.time.Instant
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.*

final case class Reply(status: Int, text: String):
  def json: JsonNode = Reply.mapper.readTree(text)
  def code: String   = Option(json.get("code")).map(_.asText).getOrElse("")
  /** The records of a page, as the store holds them. */
  def records: Vector[JsonNode] =
    import scala.jdk.CollectionConverters.*
    json.get("records").elements.asScala.toVector

object Reply:
  val mapper = ObjectMapper()

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

  /** Every log line of the service, from the start, for the leak suite. */
  val log: ListAppender[ILoggingEvent] = ListAppender[ILoggingEvent]()
  log.start()
  LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].addAppender(log)

  /** Projects whose grant is revoked: what they write no longer reaches the audit (research R2). */
  @volatile var revoked: Set[String] = Set.empty

  RegistryKey.forget()
  QuarantineKey.forget()
  AuditService.wire(settings, clock)

  private val server = HttpServer.at("127.0.0.1", 0)(AuditService.endpoints(() => Kit.authenticate)*)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    AuditService.descriptors(settings),
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, server)
  )
  probe.bind(testKit)
  Wiring.viewClient.set(Some(testKit.service.viewClient))
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
    Wiring.viewClient.set(Some(testKit.service.viewClient))
    Seed.run(secrets)

  def stop(): Unit =
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].detachAppender(log): Unit
    testKit.stop()

  /** Every log line so far, formatted, with its exception's message. */
  def logLines: Vector[String] =
    import scala.jdk.CollectionConverters.*
    log.list.asScala.toVector.map(e => s"${e.getFormattedMessage} ${Option(e.getThrowableProxy).map(_.getMessage).getOrElse("")}")

  /** Every row of every table in the service's database, as the table and the row's values joined; `bytea` read as UTF-8. */
  def database(): Vector[(String, String)] =
    val c = DriverManager.getConnection(testKit.jdbcUrl, "ankka", "ankka")
    try
      val tables = Iterator.unfold(c.getMetaData.getTables(null, null, "%", Array("TABLE"))) { rs =>
        Option.when(rs.next())((rs.getString("TABLE_SCHEM") + "." + rs.getString("TABLE_NAME"), rs))
      }.toVector.filterNot(t => t.startsWith("pg_catalog.") || t.startsWith("information_schema."))
      tables.flatMap { table =>
        val quoted = table.split('.').map(p => "\"" + p + "\"").mkString(".")
        val rs     = c.createStatement().executeQuery(s"SELECT * FROM $quoted")
        val meta   = rs.getMetaData
        val out    = Vector.newBuilder[(String, String)]
        while rs.next() do
          val values = (1 to meta.getColumnCount).flatMap { i =>
            meta.getColumnType(i) match
              case Types.BINARY | Types.VARBINARY | Types.LONGVARBINARY => Option(rs.getBytes(i)).map(String(_, UTF_8))
              case _                                                     => Option(rs.getString(i))
          }
          out += table -> values.mkString(" | ")
        out.result()
      }
    finally c.close()

  def fresh(prefix: String): String = s"$prefix-${UUID.randomUUID().toString.take(8)}"

  def now: Instant = clock.instant()

  def moveClock(to: Instant): Unit = clock.moveTo(to)

  // ── the routes ──────────────────────────────────────────────────────────────────────────────────

  def baseUrl = s"http://127.0.0.1:${server.boundPort.get}"

  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  def request(method: String, path: String, body: Option[String] = None, headers: Seq[(String, String)] = Nil): Reply =
    val b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(java.time.Duration.ofSeconds(30))
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(t => HttpRequest.BodyPublishers.ofString(t)))
    if body.isDefined then b.header("Content-Type", "application/json")
    headers.foreach((k, v) => b.header(k, v))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    Reply(r.statusCode, r.body)

  /** A member of staff holding `roles`, through the gateway. */
  def as(subject: String, roles: String*): Seq[(String, String)] =
    Seq(testKit.asCaller(Caller.Gateway), "Authorization" -> s"Bearer staff|$subject|${roles.mkString(",")}")

  def asBrandCompliance(brand: String, subject: String = "officer-1") = as(subject, "brand_compliance", s"brand_$brand")
  def asInstallationCompliance(subject: String = "officer-9")          = as(subject, Routes.AuditCompliance)
  def asOwner(subject: String = "owner-1")                             = as(subject, "platform_admin")
  def asReidentifier(brand: String, subject: String = "officer-r")    =
    as(subject, "brand_compliance", s"brand_$brand", Routes.reidentifyRole(brand))

  /** `POST /v1/audit-identities/<route>` with a JSON body. */
  def identities(route: String, as: Seq[(String, String)], body: String): Reply =
    request("POST", s"/v1/audit-identities/$route", Some(body), as)

  /** `GET /v1/audit-records` with these query parameters. */
  def query(as: Seq[(String, String)], params: (String, String)*): Reply =
    val q = params.map((k, v) => s"$k=${java.net.URLEncoder.encode(v, UTF_8)}").mkString("&")
    request("GET", s"/v1/audit-records${if q.isEmpty then "" else "?" + q}", headers = as)

  /** The audit's own records of an action, oldest first. */
  def own(action: String): Vector[Stored] = records.filter(r => r.source == "audit" && r.action == action)

  // ── the broker ──────────────────────────────────────────────────────────────────────────────────

  private def send(topic: String, key: String, bytes: Array[Byte], contract: String, id: Option[String]): Unit =
    if !revoked(topic.takeWhile(_ != '/')) then
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

/** One kit for the feature suites of a run: its database outlives scenarios, so every id a scenario names is its own. */
object SharedKit:
  lazy val kit: Kit =
    val k = Kit()
    sys.addShutdownHook(k.stop())
    k

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
