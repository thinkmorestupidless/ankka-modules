package io.eitheror.casino.markets.it

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpServer as JdkServer
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import com.thinkmorestupidless.predictions.markets.Market
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.markets.{Json, MarketsRuntime, MarketsService, Settings}

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
 * `exchange`'s service route for a market's book, as the driver calls it: every call recorded as
 * (market, command, body); `answer` is what it replies, 200 by default.
 */
final class FakeExchange(brand: String):
  final case class Call(market: String, command: String, body: JsonNode)

  private val server = JdkServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  private val log    = ConcurrentLinkedQueue[Call]()
  @volatile var answer: (Int, String) = (200, "{}")

  private val Path = s"/v1/casino/brands/$brand/exchange/markets/([^/]+)/([^/]+)".r

  server.createContext("/", exchange => {
    val body = String(exchange.getRequestBody.readAllBytes(), UTF_8)
    exchange.getRequestURI.getPath match
      case Path(m, c) if exchange.getRequestMethod == "POST" =>
        log.add(Call(java.net.URLDecoder.decode(m, UTF_8), c, Json.parse(body).getOrElse(Json.obj())))
        val (status, text) = answer
        val bytes = text.getBytes(UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
      case _ => exchange.sendResponseHeaders(404, -1)
    exchange.close()
  })
  server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
  server.start()

  def address: String                     = s"http://127.0.0.1:${server.getAddress.getPort}"
  def calls: Vector[Call]                 = log.asScala.toVector
  def callsFor(market: String): Vector[Call] = calls.filter(_.market == market)
  def reset(): Unit                       = { log.clear(); answer = (200, "{}") }
  def stop(): Unit                        = server.stop(0)

/**
 * The whole service against a throwaway Postgres (ankka's integration kit): `exchange` as
 * [[FakeExchange]] under `localServices`, an in-memory broker for `markets.events` and
 * `platform.audit`, timers polled every 200 ms, a clock the kit moves. A token `staff|<subject>|<roles>`
 * is an operator; `player|<id>` a player of the brand.
 */
final class Kit(val base: Settings = Kit.Base):
  val slug     = base.brand
  val exchange = FakeExchange(slug)
  val broker   = InMemoryBroker()

  @volatile var clockOffset: JDuration = JDuration.ZERO
  def now(): Instant              = Instant.now().plus(clockOffset)
  def advance(d: JDuration): Unit = clockOffset = clockOffset.plus(d)

  MarketsRuntime.wire(base, () => now())

  private val timers  = TimerRuntime(200.millis)
  val markets         = MarketsService(timers, base)
  private val server  = HttpServer.at("127.0.0.1", 0)(markets.endpoints(() => Kit.authenticate)*)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    markets.descriptors,
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, server),
    localServices = Map("exchange" -> exchange.address)
  )

  def components = testKit.componentClient
  def baseUrl    = s"http://127.0.0.1:${server.boundPort.get}"

  def stop(): Unit =
    testKit.stop()
    exchange.stop()

  /** The kit's own settings and clock, and the fake exchange cleared, for the next scenario. */
  def reset(settings: Settings = base): Unit =
    exchange.reset()
    clockOffset = JDuration.ZERO
    MarketsRuntime.wire(settings, () => now())

  def market(id: String): Market = components.forEventSourcedEntity(EntityId(id)).call(markets.components.market.get).invoke()

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
  def asOperator(subject: String = "op-1", roles: String = s"brand_$slug,markets"): Seq[(String, String)] =
    Seq(gateway, "Authorization" -> s"Bearer staff|$subject|$roles")
  def asService(name: String): Seq[(String, String)] = Seq(testKit.asCaller(Caller.Service("local", name)))

  def operatorPath(rest: String) = s"/v1/casino/backoffice/$slug/markets$rest"
  def playerPath(rest: String)   = s"/v1/casino/markets/$slug$rest"

  // ── facts ─────────────────────────────────────────────────────────────────

  def facts[F <: Fact](topic: Topic[F]): Vector[(Option[String], F)] =
    broker.publishedTo(topic.name).toVector.flatMap(d => scala.util.Try(topic.serializer.fromBytes(d.message.payload)).toOption.map(d.message.key -> _))

  def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = testKit.eventually(what, within)(f)

object Kit:
  val Base: Settings = Settings("goldvibe", Vector("politics", "sports", "economics"), Vector("EUR"), 100000, 10000, 0)

  /** Stands in for the token verifier: what is tested is what each audience's ACL decides once a token is verified. */
  val authenticate: Acl = Acl.Authenticate { context =>
    context.header("authorization").map(_.stripPrefix("Bearer ")).map(_.split('|').toList) match
      case Some("staff" :: subject :: roles :: _) =>
        AuthDecision.Allow(Principal(subject, None, None, false, roles.split(',').filter(_.nonEmpty).toSet,
          Map("iss" -> "https://auth.example/realms/eitheror"), Some(Routes.StaffIssuer)))
      case Some("player" :: id :: Nil) =>
        AuthDecision.Allow(Principal(s"realm-user-$id", None, None, false, Set.empty,
          Map("iss" -> "https://auth.example/realms/casino-goldvibe", Routes.PlayerIdClaim -> id), Some(Routes.PlayerIssuer)))
      case Some(_) => AuthDecision.Unauthenticated("realm=\"casino\", error=\"invalid_token\"")
      case None    => AuthDecision.Unauthenticated("realm=\"casino\"")
  }

/** One kit for every suite in the test JVM. */
object SharedKit:
  lazy val kit: Kit =
    val k = Kit()
    sys.addShutdownHook(k.stop())
    k
