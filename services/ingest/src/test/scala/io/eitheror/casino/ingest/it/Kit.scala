package io.eitheror.casino.ingest.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.{EntityId, Metadata}
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.ingest.{IngestRuntime, IngestService, Json, Settings}
import io.eitheror.casino.ingest.keys.{KeyIndex, KeyRow}
import io.eitheror.casino.ingest.rate.{RateState, RateWindow}
import io.eitheror.casino.ingest.requests.*
import io.eitheror.casino.ingest.shape.Digests

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration as JDuration, Instant}
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** An HTTP answer from the service, with its headers. */
final case class Reply(status: Int, text: String, headers: Map[String, String] = Map.empty):
  def json: JsonNode                = Json.parse(text).getOrElse(throw AssertionError(s"not JSON ($status): $text"))
  def str(field: String): String    = Json.parse(text).flatMap(n => Json.text(n)(field)).getOrElse("")
  def header(name: String): Option[String] = headers.get(name.toLowerCase)

/** A cashier key the kit issued through the broker, as `merchant` would have: the raw key and its id. */
final case class RawKey(raw: String, keyId: String, subBrand: String):
  def header: (String, String) = "merchantKey" -> raw

/**
 * The whole service against a throwaway Postgres (ankka's integration kit), `merchant` as the in-process
 * `FakeMerchant`, an in-memory broker for `payments.merchants` and `platform.audit`, a clock the kit moves,
 * the answer bound at 500 ms and the body bound at 4096 bytes. A stand-in for Keycloak:
 * `staff|<subject>|<roles>|<level>|<scope id>` is a staff token, as merchant's and psp-gateway's kits mint it.
 */
final class Kit(val settings: Settings = Kit.Settings, keys: Vector[(String, String, String)] = Vector.empty):
  val merchant = FakeMerchant()
  merchant.keys = keys
  val broker   = InMemoryBroker()
  @volatile var clockOffset: JDuration = JDuration.ZERO
  @volatile var fixed: Option[Instant] = None
  def now(): Instant = fixed.getOrElse(Instant.now()).plus(clockOffset)

  IngestRuntime.wire(settings, () => now())

  private val server = HttpServer.at("127.0.0.1", 0)(IngestService.endpoints(() => Kit.authenticate)*)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    IngestService.descriptors,
    Seq(ProjectionRuntime.withBroker(broker, broker), server),
    localServices = Map("merchant" -> merchant.address)
  )
  /** What the start refresh issued (FR-004). */
  @volatile var refreshed: Int = IngestService.started(testKit.service)

  def components = testKit.componentClient
  def views      = testKit.service.viewClient
  def base: String = s"http://127.0.0.1:${server.boundPort.get}"

  def stop(): Unit = { testKit.stop(); merchant.stop() }

  /** The service stopped and started again on the same database: its start refresh runs again. */
  def restart(): Unit =
    testKit.restartService()
    refreshed = IngestService.started(testKit.service)

  def reset(): Unit = { merchant.reset(); clockOffset = JDuration.ZERO; fixed = None; IngestRuntime.wire(settings, () => now()) }

  def advance(d: JDuration): Unit = clockOffset = clockOffset.plus(d)

  /** Holds the clock at `second` seconds past the start of the next whole minute. */
  def holdClockInAMinute(second: Int = 1): Instant =
    val t = Instant.ofEpochSecond((Instant.now().getEpochSecond / 60 + 1) * 60 + second)
    fixed = Some(t); clockOffset = JDuration.ZERO
    t

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────

  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  def request(method: String, path: String, body: Option[String] = None, headers: Seq[(String, String)] = Nil): Reply =
    val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(60))
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(t => HttpRequest.BodyPublishers.ofString(t)))
    if body.isDefined then b.header("Content-Type", "application/json")
    headers.foreach((k, v) => b.header(k, v))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    Reply(r.statusCode, r.body, r.headers.map.asScala.toMap.map((k, v) => k.toLowerCase -> v.asScala.headOption.getOrElse("")))

  private def caller(c: Caller): Seq[(String, String)] = Seq(testKit.asCaller(c))
  /** A sub-brand's server or a brand's frontend proxy, through the installation's ingress. */
  def asSubBrand: Seq[(String, String)] = caller(Caller.Gateway)
  def asNobody: Seq[(String, String)]   = asSubBrand

  def asOperator(level: String = "platform", scopeId: String = "*", roles: String = "platform_admin", subject: String = "op-1"): Seq[(String, String)] =
    asSubBrand :+ ("Authorization" -> s"Bearer staff|$subject|$roles|$level|$scopeId")

  /** A staff token whose roles do not name the shared payments: what a `403` is for. */
  def asStaff: Seq[(String, String)] = asOperator(roles = "affiliate")

  // ── keys, through the broker as merchant publishes them ─────────────────────────────────────────

  def publish(fact: PaymentsMerchantsFact): Unit =
    val bytes = PaymentsMerchants.serializer.toBytes(fact)
    scala.concurrent.Await.result(broker.publish(PaymentsMerchants.name, Some(fact.key), bytes,
      Metadata.empty.set(Metadata.CeSubject, fact.key).set(Metadata.CeType, PaymentsMerchants.contractName)), 10.seconds): Unit

  /** A key minted for `subBrand` and issued as `merchant_key_issued`, awaited in the index. */
  def issueKey(subBrand: String = s"sb-${UUID.randomUUID().toString.take(8)}", await: Boolean = true): RawKey =
    val key = RawKey(s"mk_test_${UUID.randomUUID().toString.replace("-", "")}", s"k-${UUID.randomUUID().toString.take(8)}", subBrand)
    issue(key, await)
    key

  def issue(key: RawKey, await: Boolean = true): Unit =
    publish(MerchantKeyIssued(key.keyId, key.subBrand, Digests.ofKey(key.raw), now()))
    if await then eventually(s"key ${key.keyId} indexed")(keyRow(key).filter(_.active)): Unit

  def deactivateKey(key: RawKey, await: Boolean = true): Unit =
    publish(MerchantKeyDeactivated(key.keyId, key.subBrand, now()))
    if await then eventually(s"key ${key.keyId} inactive")(keyRow(key).filterNot(_.active)): Unit

  def keyRow(key: RawKey): Option[KeyRow] = KeyIndex.find(components, Digests.ofKey(key.raw))

  /** What `audit-facts` published on `platform.audit`. */
  def audit(): Vector[AuditRecord] =
    broker.publishedTo(PlatformAudit.name).toVector.map(d => PlatformAudit.serializer.fromBytes(d.message.payload)).collect { case a: AuditRecord => a }

  def auditTexts(): Vector[String] = broker.publishedTo(PlatformAudit.name).toVector.map(d => String(d.message.payload, "UTF-8"))

  // ── the routes ──────────────────────────────────────────────────────────────────────────────────

  /** Quickstart §5's body, with `fields` replacing any of it; a `None` leaves a field out. */
  def body(fields: (String, Any)*): String =
    val base = Vector[(String, Any)]("amountCents" -> 5000L, "currency" -> "EUR", "customerId" -> "goldvibe:p1", "country" -> "GB",
      "redirectUrl" -> "https://example.test/return", "transactionId" -> s"t-${UUID.randomUUID()}")
    val keys = fields.map(_._1).toSet
    Json.render(Json.obj((base.filterNot(f => keys(f._1)) ++ fields.filterNot(_._2 == None))*))

  def create(key: Option[RawKey], body: String, headers: (String, String)*): Reply =
    request("POST", "/v1/cashier/sessions", Some(body), asSubBrand ++ key.map(_.header) ++ headers)

  def create(key: RawKey, fields: (String, Any)*): Reply = create(Some(key), body(fields*))

  def submit(ticket: String, terminal: String = "sim-eur", extra: (String, Any)*): Reply =
    request("POST", s"/v1/cashier/sessions/$ticket/submit", Some(Json.render(Json.obj((("selectedTerminalEntityId" -> terminal) +: extra)*))), asSubBrand)

  def abandon(ticket: String): Reply = request("POST", s"/v1/cashier/sessions/$ticket/abandon", None, asSubBrand)

  def readPayment(paymentId: String, key: Option[RawKey] = None, ticket: Option[String] = None): Reply =
    request("GET", s"/v1/payments/$paymentId", None, asSubBrand ++ key.map(_.header) ++ ticket.map("cashierTicket" -> _))

  // ── reading the service's state ─────────────────────────────────────────────────────────────────

  def records(subBrand: String): Vector[RecordState] =
    RequestsView.of(views, subBrand, Instant.EPOCH, now().plus(JDuration.ofDays(1)), 1000)

  def record(recordId: String): Option[RecordState] =
    components.forKeyValueEntity(EntityId(recordId)).call(RequestRecord.read).invoke()

  /** The record a request left, once the view has it: the newest of the sub-brand's, or of none, matching `p`. */
  def recordWhere(subBrand: String)(p: RecordState => Boolean): RecordState =
    eventually(s"a record of $subBrand")(records(subBrand).find(p))

  def requestState(subBrand: String, requestId: String): RequestState =
    components.forKeyValueEntity(EntityId(Request.id(subBrand, requestId))).call(Request.read).invoke()

  def ownership(id: String): Option[OwnershipState] = components.forKeyValueEntity(EntityId(id)).call(Ownership.read).invoke()

  def rate(subBrand: String): RateState = components.forKeyValueEntity(EntityId(subBrand)).call(RateWindow.read).invoke()

  /** Every record in the service's store, read from the entities through the view of records under no sub-brand too. */
  def recordIds: Vector[String] =
    val sql = s"SELECT payload FROM ${RequestsView.table}"
    Kit.query(testKit.jdbcUrl, sql).flatMap(t => Json.parse(t).flatMap(n => Option(n.get("record"))).flatMap(r => Json.text(r)("recordId")))

  /** The records under no sub-brand (a refusal before the key named one), newest first, as the view's rows hold them. */
  def unbranded(): Vector[JsonNode] =
    val sql = s"SELECT payload FROM ${RequestsView.table} WHERE payload::jsonb->'record'->>'subBrand' IS NULL ORDER BY (payload::jsonb->>'at')::bigint DESC"
    Kit.query(testKit.jdbcUrl, sql).flatMap(t => Json.parse(t).flatMap(n => Option(n.get("record"))))

  def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = testKit.eventually(what, within)(f)

object Kit:
  val Settings: io.eitheror.casino.ingest.Settings =
    io.eitheror.casino.ingest.Settings(answerBound = JDuration.ofMillis(500), bodyBound = 4096, rateLimit = 600)

  /** Stands in for Keycloak: what is tested is what the payments operator ACL decides once a token is verified. */
  val authenticate: Acl = Acl.Authenticate { context =>
    context.header("authorization").map(_.stripPrefix("Bearer ")) match
      case None => AuthDecision.Unauthenticated("realm=\"eitheror\"")
      case Some(token) =>
        token.split('|').toList match
          case "staff" :: subject :: roles :: level :: scope :: Nil =>
            val claims = Map("iss" -> "https://auth.example/realms/eitheror", Routes.ScopeLevelClaim -> level) ++
              Option.when(scope.nonEmpty && scope != "*")(Routes.ScopeIdClaim -> scope)
            AuthDecision.Allow(Principal(subject, None, None, false, roles.split(',').filter(_.nonEmpty).toSet, claims, Some(Routes.StaffIssuer)))
          case _ => AuthDecision.Unauthenticated("realm=\"eitheror\", error=\"invalid_token\"")
  }

  /** One column of a read of the kit's database, as text. */
  def query(jdbcUrl: String, sql: String): Vector[String] =
    val c = java.sql.DriverManager.getConnection(jdbcUrl, "ankka", "ankka")
    try
      val rs  = c.createStatement().executeQuery(sql)
      val out = Vector.newBuilder[String]
      while rs.next() do out += rs.getString(1)
      out.result()
    finally c.close()

/** One kit for every suite in the test JVM that neither stops the fake nor moves the clock. */
object SharedKit:
  lazy val kit: Kit =
    val k = Kit()
    sys.addShutdownHook(k.stop())
    k
