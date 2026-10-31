package io.eitheror.casino.kyc.it

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpServer as JdkServer
import com.thinkmorestupidless.ankka.core.{EntityId, Metadata}
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.kyc.{Json, KycRuntime, KycService, Settings}
import io.eitheror.casino.kyc.documents.{S3Store, Store}
import io.eitheror.casino.kyc.policy.{ApplyProfile, PolicyEntity}
import org.slf4j.LoggerFactory

import java.net.{InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration as JDuration, Instant}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** An HTTP answer from the service. */
final case class Reply(status: Int, text: String):
  def json: JsonNode             = Json.parse(text).getOrElse(throw AssertionError(s"not JSON ($status): $text"))
  def str(field: String): String = Json.parse(text).flatMap(n => Option(n.get(field))).filterNot(_.isNull).map(_.asText).getOrElse("")

/**
 * `players`' suspend route as this service calls it (contracts/routes.md *Calls out*): every call
 * recorded as (player, body); `failing` answers 503.
 */
final class FakePlayers(brand: String):
  final case class Call(playerId: String, body: String)

  private val server = JdkServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  private val log    = ConcurrentLinkedQueue[Call]()
  @volatile var failing: Boolean = false

  private val Path = s"/v1/casino/brands/$brand/players/([^/]+)/suspend".r

  server.createContext("/", exchange => {
    val body = String(exchange.getRequestBody.readAllBytes(), UTF_8)
    exchange.getRequestURI.getPath match
      case Path(id) if exchange.getRequestMethod == "POST" =>
        log.add(Call(java.net.URLDecoder.decode(id, UTF_8), body))
        exchange.sendResponseHeaders(if failing then 503 else 200, -1)
      case _ => exchange.sendResponseHeaders(404, -1)
    exchange.close()
  })
  server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
  server.start()

  def address: String                         = s"http://127.0.0.1:${server.getAddress.getPort}"
  def calls: Vector[Call]                     = log.asScala.toVector
  def callsFor(playerId: String): Vector[Call] = calls.filter(_.playerId == playerId)
  def reset(): Unit                           = { log.clear(); failing = false }
  def stop(): Unit                            = server.stop(0)

/**
 * The whole service against a throwaway Postgres (ankka's integration kit) and Garage, the installation's
 * object store: `players` as [[FakePlayers]] under `localServices`, an in-memory broker for
 * `casino.players`, `money.transactions` and `platform.audit`, timers polled every 200 ms, a clock the kit
 * moves. A token `player|<id>` is a player of the brand; `staff|<subject>|<roles>` an operator.
 */
final class Kit(base: Settings = Kit.Base):
  val slug    = base.brand
  val players = FakePlayers(slug)
  val broker  = InMemoryBroker()
  val garage  = GarageContainer.start()
  val bucket  = garage.bucket("kyc-it")
  val store   = S3Store(bucket)

  @volatile var clockOffset: JDuration = JDuration.ZERO
  def now(): Instant              = Instant.now().plus(clockOffset)
  def advance(d: JDuration): Unit = clockOffset = clockOffset.plus(d)

  private val settings = base.copy(bucket = Some(bucket))
  KycRuntime.wire(settings, () => now())

  /** Every log line of the service, from the start, for the leak suite. */
  val log: ListAppender[ILoggingEvent] = ListAppender[ILoggingEvent]()
  log.start()
  LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].addAppender(log)

  private val timers = TimerRuntime(200.millis)
  private val server = HttpServer.at("127.0.0.1", 0)(KycService.endpoints(() => Kit.authenticate)*)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    KycService.descriptors,
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, server),
    localServices = Map("players" -> players.address)
  )
  KycService.started(timers, store, views)

  def limit: Long  = settings.maxUploadBytes
  def views       = testKit.service.viewClient
  def components = testKit.componentClient
  def scheduler  = timers.timerScheduler
  def baseUrl    = s"http://127.0.0.1:${server.boundPort.get}"

  def stop(): Unit =
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].detachAppender(log): Unit
    testKit.stop()
    players.stop()
    garage.stop()

  /** Back to the kit's own clock, settings and store, and the brand's default policy (Curaçao), for the next suite. */
  def reset(s: Store = store): Unit =
    players.reset()
    clockOffset = JDuration.ZERO
    KycRuntime.wire(settings, () => now())
    KycService.started(timers, s, views)
    components.forEventSourcedEntity(EntityId(slug)).call(PolicyEntity.applyProfile).invoke(ApplyProfile(Some("CURACAO"), "kit", now())): Unit

  // ── HTTP ──────────────────────────────────────────────────────────────────

  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  def request(method: String, path: String, body: Option[String] = None, headers: Seq[(String, String)] = Nil): Reply =
    val b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(java.time.Duration.ofSeconds(30))
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(t => HttpRequest.BodyPublishers.ofString(t)))
    if body.isDefined then b.header("Content-Type", "application/json")
    headers.foreach((k, v) => b.header(k, v))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    Reply(r.statusCode, r.body)

  def json(method: String, path: String, body: JsonNode, headers: Seq[(String, String)]): Reply =
    request(method, path, Some(Json.render(body)), headers)

  /** The browser's PUT through a signed link: the bytes and the content type it was signed for. */
  def putSigned(url: String, bytes: Array[Byte], contentType: String): Int =
    val r = http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", contentType)
      .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding())
    r.statusCode

  def getSigned(url: String): (Int, Array[Byte]) =
    val r = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray())
    (r.statusCode, r.body)

  // ── who is calling ────────────────────────────────────────────────────────

  private def gateway = testKit.asCaller(Caller.Gateway)
  def asPlayer(playerId: String): Seq[(String, String)] = Seq(gateway, "Authorization" -> s"Bearer player|$playerId")
  def asOperator(subject: String = "op-1", roles: String = s"brand_$slug,brand_compliance"): Seq[(String, String)] =
    Seq(gateway, "Authorization" -> s"Bearer staff|$subject|$roles")
  def asService(name: String): Seq[(String, String)] = Seq(testKit.asCaller(Caller.Service("local", name)))
  def asPublic: Seq[(String, String)]                = Seq(gateway)

  def playerPath(rest: String)   = s"/v1/casino/kyc/$slug$rest"
  def publicPath(rest: String)   = s"/v1/casino/kyc-requirements/$slug$rest"
  def operatorPath(rest: String) = s"/v1/casino/backoffice/$slug/kyc$rest"
  def servicePath(rest: String)  = s"/v1/casino/brands/$slug/kyc$rest"

  // ── facts and timers ──────────────────────────────────────────────────────

  def facts[F <: Fact](topic: Topic[F]): Vector[F] =
    broker.publishedTo(topic.name).toVector.flatMap(d => scala.util.Try(topic.serializer.fromBytes(d.message.payload)).toOption)

  def publish[F <: Fact](topic: Topic[F], key: String, fact: F): Unit =
    scala.concurrent.Await.result(broker.publish(topic.name, Some(key), topic.serializer.toBytes(fact),
      Metadata.empty.set(Metadata.CeSubject, key).set(Metadata.CeType, topic.contractName)), 10.seconds): Unit

  def factTexts(topic: Topic[?]): Vector[String] = broker.publishedTo(topic.name).toVector.map(d => String(d.message.payload, UTF_8))

  /** Every row of every table in the service's schema, as text: the journal, the views, the timers. */
  def tables: Map[String, Vector[String]] =
    Kit.query(testKit.jdbcUrl, "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'")
      .map(t => t -> Kit.query(testKit.jdbcUrl, s"SELECT CAST(r AS text) FROM \"$t\" r")).toMap

  def logText: Vector[String] =
    log.list.asScala.toVector.map(e => s"${e.getFormattedMessage} ${Option(e.getThrowableProxy).fold("")(_.getMessage)}")

  def timerExists(name: String): Boolean =
    Kit.query(testKit.jdbcUrl, s"SELECT count(*) FROM ankka_timers WHERE timer_name = '$name'").headOption.contains("1")

  /** The timer named, due now as far as the timer runtime is concerned. */
  def fireTimer(name: String): Unit =
    val c = java.sql.DriverManager.getConnection(testKit.jdbcUrl, "ankka", "ankka")
    try c.createStatement().executeUpdate(s"UPDATE ankka_timers SET due_at = now() WHERE timer_name = '$name'"): Unit
    finally c.close()

  def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = testKit.eventually(what, within)(f)

object Kit:
  // A small upload limit, so a scenario can pass it without sending megabytes.
  val Base: Settings = Settings(brand = "goldvibe", maxUploadBytes = 64 * 1024)

  /** Stands in for the token verifier: what is tested is what each audience's ACL decides once a token is verified. */
  val authenticate: Acl = Acl.Authenticate { context =>
    context.header("authorization").map(_.stripPrefix("Bearer ")).map(_.split('|').toList) match
      case Some("staff" :: subject :: roles :: _) =>
        AuthDecision.Allow(Principal(subject, None, None, false, roles.split(',').filter(_.nonEmpty).toSet,
          Map("iss" -> "https://auth.example/realms/eitheror"), Some(Routes.StaffIssuer)))
      case Some("player" :: id :: Nil) =>
        // `sub` is the realm's own user id; the player id travels in `eitheror_twin_id` (`Routes.playerId`).
        AuthDecision.Allow(Principal(s"realm-user-$id", None, None, false, Set.empty,
          Map("iss" -> "https://auth.example/realms/casino-goldvibe", Routes.PlayerIdClaim -> id), Some(Routes.PlayerIssuer)))
      case Some(_) => AuthDecision.Unauthenticated("realm=\"casino\", error=\"invalid_token\"")
      case None    => AuthDecision.Unauthenticated("realm=\"casino\"")
  }

  def query(jdbcUrl: String, sql: String): Vector[String] =
    val c = java.sql.DriverManager.getConnection(jdbcUrl, "ankka", "ankka")
    try
      val rs  = c.createStatement().executeQuery(sql)
      val out = Vector.newBuilder[String]
      while rs.next() do out += rs.getString(1)
      out.result()
    finally c.close()

/** One kit for every suite in the test JVM. */
object SharedKit:
  lazy val kit: Kit =
    val k = Kit()
    sys.addShutdownHook(k.stop())
    k
