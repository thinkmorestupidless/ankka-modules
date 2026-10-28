package io.eitheror.casino.rg.it

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpServer as JdkServer
import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.rg.{Json, RgRuntime, RgService, Settings}

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
 * `players`' exclusion route as this service calls it: every call recorded as (method, player, body);
 * `failing` answers 503, `silent` holds the call past the client's timeout.
 */
final class FakePlayers(brand: String):
  final case class Call(method: String, playerId: String, body: String)

  private val server = JdkServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  private val log    = ConcurrentLinkedQueue[Call]()
  @volatile var failing: Boolean = false
  @volatile var silent: Boolean  = false

  private val Path = s"/v1/casino/brands/$brand/players/([^/]+)/exclusion".r

  server.createContext("/", exchange => {
    val body = String(exchange.getRequestBody.readAllBytes(), UTF_8)
    exchange.getRequestURI.getPath match
      case Path(id) =>
        log.add(Call(exchange.getRequestMethod, java.net.URLDecoder.decode(id, UTF_8), body))
        if silent then Thread.sleep(5_000)
        val status = if failing then 503 else 200
        exchange.sendResponseHeaders(status, -1)
      case _ => exchange.sendResponseHeaders(404, -1)
    exchange.close()
  })
  server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
  server.start()

  def address: String     = s"http://127.0.0.1:${server.getAddress.getPort}"
  def calls: Vector[Call] = log.asScala.toVector
  def callsFor(playerId: String): Vector[Call] = calls.filter(_.playerId == playerId)
  def reset(): Unit = { log.clear(); failing = false; silent = false }
  def stop(): Unit  = server.stop(0)

/**
 * The whole service against a throwaway Postgres (ankka's integration kit): `players` as [[FakePlayers]]
 * under `localServices`, an in-memory broker for the four topics, timers polled every 200 ms, a clock the
 * kit moves. A token `player|<id>` is a player of the brand; `staff|<subject>|<roles>` an operator.
 */
final class Kit(base: Settings = Kit.Base):
  val slug    = base.brand
  val players = FakePlayers(slug)
  val broker  = InMemoryBroker()

  @volatile var clockOffset: JDuration = JDuration.ZERO
  def now(): Instant              = Instant.now().plus(clockOffset)
  def advance(d: JDuration): Unit = clockOffset = clockOffset.plus(d)

  RgRuntime.wire(base, () => now())

  private val timers = TimerRuntime(200.millis)
  private val server = HttpServer.at("127.0.0.1", 0)(RgService.endpoints(() => Kit.authenticate)*)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    RgService.descriptors,
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, server),
    localServices = Map("players" -> players.address)
  )
  RgService.started(timers)

  def components = testKit.componentClient
  def scheduler  = timers.timerScheduler
  def baseUrl    = s"http://127.0.0.1:${server.boundPort.get}"

  def stop(): Unit =
    testKit.stop()
    players.stop()

  def reset(): Unit =
    players.reset()
    clockOffset = JDuration.ZERO
    RgRuntime.wire(base, () => now())
    RgService.started(timers)

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

  // ── who is calling ────────────────────────────────────────────────────────

  private def gateway = testKit.asCaller(Caller.Gateway)
  def asPlayer(playerId: String): Seq[(String, String)] = Seq(gateway, "Authorization" -> s"Bearer player|$playerId")
  def asOperator(subject: String = "op-1", roles: String = s"brand_$slug"): Seq[(String, String)] =
    Seq(gateway, "Authorization" -> s"Bearer staff|$subject|$roles")
  def asService(name: String): Seq[(String, String)] = Seq(testKit.asCaller(Caller.Service("local", name)))

  def playerPath(rest: String)   = s"/v1/casino/rg/$slug$rest"
  def operatorPath(rest: String) = s"/v1/casino/backoffice/$slug/rg$rest"
  def servicePath(rest: String)  = s"/v1/casino/brands/$slug/rg$rest"

  // ── facts and timers ──────────────────────────────────────────────────────

  def facts[F <: Fact](topic: Topic[F]): Vector[F] =
    broker.publishedTo(topic.name).toVector.flatMap(d => scala.util.Try(topic.serializer.fromBytes(d.message.payload)).toOption)

  def publish[F <: Fact](topic: Topic[F], key: String, fact: F): Unit =
    scala.concurrent.Await.result(broker.publish(topic.name, Some(key), topic.serializer.toBytes(fact),
      Metadata.empty.set(Metadata.CeSubject, key).set(Metadata.CeType, topic.contractName)), 10.seconds): Unit

  def timerExists(name: String): Boolean =
    Kit.query(testKit.jdbcUrl, s"SELECT count(*) FROM ankka_timers WHERE timer_name = '$name'").headOption.contains("1")

  /** The timer named, due now as far as the timer runtime is concerned. */
  def fireTimer(name: String): Unit =
    val c = java.sql.DriverManager.getConnection(testKit.jdbcUrl, "ankka", "ankka")
    try c.createStatement().executeUpdate(s"UPDATE ankka_timers SET due_at = now() WHERE timer_name = '$name'"): Unit
    finally c.close()

  def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = testKit.eventually(what, within)(f)

object Kit:
  val Base: Settings = Settings(brand = "goldvibe", syncRetry = JDuration.ofSeconds(1))

  /** Stands in for the token verifier: what is tested is what each audience's ACL decides once a token is verified. */
  val authenticate: Acl = Acl.Authenticate { context =>
    context.header("authorization").map(_.stripPrefix("Bearer ")).map(_.split('|').toList) match
      case Some("staff" :: subject :: roles :: _) =>
        AuthDecision.Allow(Principal(subject, None, None, false, roles.split(',').filter(_.nonEmpty).toSet,
          Map("iss" -> "https://auth.example/realms/eitheror"), Some(Routes.StaffIssuer)))
      case Some("player" :: id :: Nil) =>
        AuthDecision.Allow(Principal(id, None, None, false, Set.empty,
          Map("iss" -> "https://auth.example/realms/casino-goldvibe", "eitheror_twin_id" -> id), Some(Routes.PlayerIssuer)))
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
