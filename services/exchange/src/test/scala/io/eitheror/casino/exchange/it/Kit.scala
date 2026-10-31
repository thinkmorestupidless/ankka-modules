package io.eitheror.casino.exchange.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import com.thinkmorestupidless.predictions.exchange.Exchange
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.exchange.{ExchangeRuntime, ExchangeService, Json, Settings}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.*

/** An HTTP answer from the service. */
final case class Reply(status: Int, text: String):
  def json: JsonNode             = Json.parse(text).getOrElse(throw AssertionError(s"not JSON ($status): $text"))
  def str(field: String): String = Json.parse(text).flatMap(n => Option(n.get(field))).filterNot(_.isNull).map(_.asText).getOrElse("")

/**
 * The whole service against a throwaway Postgres (ankka's integration kit): the gates and the wallet
 * as [[FakeGates]] and [[FakeWallet]] under `localServices`, an in-memory broker, timers polled every
 * 200 ms, a clock the kit moves. A token `player|<id>` is a player of the brand.
 */
final class Kit(val base: Settings = Kit.Base):
  val slug   = base.brand
  val gates  = FakeGates(slug)
  val wallet = FakeWallet(slug)
  val broker = InMemoryBroker()

  @volatile var clockOffset: JDuration = JDuration.ZERO
  def now(): Instant              = Instant.now().plus(clockOffset)
  def advance(d: JDuration): Unit = clockOffset = clockOffset.plus(d)

  ExchangeRuntime.wire(base, () => now())

  private val timers = TimerRuntime(200.millis)
  val exchange       = ExchangeService()
  private val server = HttpServer.at("127.0.0.1", 0)(exchange.endpoints(() => Kit.authenticate)*)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    exchange.descriptors,
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, server),
    localServices = Map("players" -> gates.address, "kyc" -> gates.address, "responsible-gambling" -> gates.address,
      "wallet" -> wallet.address)
  )
  ExchangeRuntime.started(() => (testKit.service.services, testKit.service.componentClient))

  def components = testKit.componentClient
  def baseUrl    = s"http://127.0.0.1:${server.boundPort.get}"

  def stop(): Unit =
    testKit.stop()
    gates.stop()
    wallet.stop()

  def reset(settings: Settings = base): Unit =
    gates.reset()
    wallet.reset()
    clockOffset = JDuration.ZERO
    ExchangeRuntime.wire(settings, () => now())

  def book(market: String): Exchange = components.forEventSourcedEntity(EntityId(market)).call(exchange.components.exchange.get).invoke()

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

  private def gateway = testKit.asCaller(Caller.Gateway)
  def asPlayer(playerId: String): Seq[(String, String)] = Seq(gateway, "Authorization" -> s"Bearer player|$playerId")
  def asService(name: String): Seq[(String, String)]   = Seq(testKit.asCaller(Caller.Service("local", name)))

  def playerPath(rest: String)  = s"/v1/casino/exchange/$slug$rest"
  def servicePath(rest: String) = s"/v1/casino/brands/$slug/exchange$rest"

  /** Opens a book as `markets` does, unit 100, at the kit's now. */
  def open(market: String, outcomes: Vector[String], currency: String, maxOrderCost: Long = 100000, maxPosition: Long = 10000): Reply =
    json("POST", servicePath(s"/markets/$market/open"), Json.obj("outcomes" -> outcomes, "currency" -> currency, "unit" -> 100L,
      "maxOrderCost" -> maxOrderCost, "maxPosition" -> maxPosition, "takerFeeBps" -> 0, "at" -> now().toString), asService("markets"))

  /** Places an order as `player`, resting until close unless told otherwise. */
  def place(player: String, market: String, order: String, side: String, outcome: String, quantity: Long, price: Long,
      duration: String = "rest-until-close"): Reply =
    json("POST", playerPath(s"/orders/$market"), Json.obj("order" -> order, "outcome" -> outcome, "side" -> side, "price" -> price,
      "quantity" -> quantity, "duration" -> duration), asPlayer(player))

  // ── facts ─────────────────────────────────────────────────────────────────

  def facts[F <: Fact](topic: Topic[F]): Vector[(Option[String], F)] =
    broker.publishedTo(topic.name).toVector.flatMap(d => scala.util.Try(topic.serializer.fromBytes(d.message.payload)).toOption.map(d.message.key -> _))

  def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = testKit.eventually(what, within)(f)

object Kit:
  val Base: Settings = Settings("goldvibe")

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
