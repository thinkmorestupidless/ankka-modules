package io.eitheror.casino.publicapi.it

import io.eitheror.casino.publicapi.api

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, EndpointClients, HttpEndpoint, HttpServer, LocalCallers, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, InMemoryKeyring, MovableClock, TimerProbe}
import io.eitheror.casino.contract.facts.{Fact, Topic}
import io.eitheror.casino.publicapi.{PublicApiService, Settings}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** An HTTP answer from the service. */
final case class Reply(status: Int, text: String, headers: Map[String, Vector[String]] = Map.empty):
  def json: JsonNode = Try(Kit.mapper.readTree(text)).toOption.filter(_ != null).getOrElse(throw AssertionError(s"not JSON ($status): $text"))
  def header(name: String): Option[String] = headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v.head }
  def error: String = json.path("error").asText("")

/**
 * The whole service against a throwaway Postgres (ankka's integration kit), as reporting's: an in-memory broker
 * carrying the brand's topics, timers on a clock the test moves. A stand-in for Keycloak reads `staff|<id>|<roles>` as
 * an operator. One kit is one brand's service; two kits are two brands (IsolationSuite).
 */
final class Kit(val brand: String = "goldvibe"):
  val broker   = InMemoryBroker()
  val clock    = MovableClock.at(Instant.parse("2026-11-12T08:00:00Z"))
  val probe    = TimerProbe()
  val settings = Settings(brand, project = brand, host = Some(URI.create(s"https://public-api-$brand.example")))
  val contract = PublicApiService.contract(settings)

  val timers = TimerRuntime(200.millis, probe, clock)
  private val server = HttpServer.at("127.0.0.1", 0)(PublicApiService.endpoints(settings, contract, () => Kit.authenticate, () => clock.instant())*)

  /** `game-provider`'s games route for this brand (T015), as the service calls it. */
  val provider = FakeGameProvider(brand)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    PublicApiService.descriptors(settings),
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, server),
    configure = _.withErasureHandler(api.Erasure.handler),
    keyring = Kit.keyring,
    localServices = Map("game-provider" -> provider.address)
  )
  probe.bind(testKit)
  // The hourly run is not scheduled here: the suites run it when they choose (`Games.compute`), and several kits share
  // the JVM-wide `StatsRuntime` the timer reads.

  def base: String = s"http://127.0.0.1:${server.boundPort.get}"
  def components   = testKit.componentClient
  def views        = testKit.service.viewClient

  def stop(): Unit = testKit.stop()

  def now: Instant                     = clock.instant()
  def moveClock(by: FiniteDuration): Unit = clock.moveBy(by)

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────

  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  def request(method: String, path: String, body: Option[String] = None, headers: Seq[(String, String)] = Nil, at: String = ""): Reply =
    val b = HttpRequest.newBuilder(URI.create((if at.isEmpty then base else at) + path)).timeout(java.time.Duration.ofSeconds(30))
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(t => HttpRequest.BodyPublishers.ofString(t)))
    if body.isDefined then b.header("Content-Type", "application/json")
    headers.foreach((k, v) => b.header(k, v))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    Reply(r.statusCode, r.body, r.headers.map.asScala.view.mapValues(_.asScala.toVector).toMap)

  /** A `/v1` GET from the internet, with the access key when one is given. */
  def v1(path: String, key: Option[String] = None, method: String = "GET", at: String = ""): Reply =
    request(method, s"/v1$path", headers = fromInternet ++ key.map(k => "Authorization" -> s"Bearer $k"), at = at)

  // ── who is calling ──────────────────────────────────────────────────────────────────────────────

  /** A request from the internet, through the gateway, with no token. */
  def fromInternet: Seq[(String, String)] = Seq(LocalCallers.header(Caller.Gateway))

  /** An operator through the gateway holding `roles`; the brand's role is added unless `brandRole` is false. */
  def asStaff(roles: Seq[String], op: String = "op-1", brandRole: Boolean = true): Seq[(String, String)] =
    val all = (if brandRole then Seq(s"brand_$brand") else Nil) ++ roles
    fromInternet :+ ("Authorization" -> s"Bearer staff|$op|${all.mkString(",")}")

  /** An operator of the brand holding the access keys permission. */
  def asKeyManager: Seq[(String, String)] = asStaff(Seq("api_keys"), "keys-1")

  /** A service of this project, as `players` calls the erasure route. */
  def asService(name: String = "players", project: String = "local"): Seq[(String, String)] =
    Seq(LocalCallers.header(Caller.Service(project, name)))

  // ── the broker ──────────────────────────────────────────────────────────────────────────────────

  /** Publishes a fact on its topic as its owner would. */
  def publish[F <: Fact](topic: Topic[F], fact: F): Unit =
    Await.result(broker.publish(topic.name, Some(fact.key), topic.serializer.toBytes(fact),
      Metadata.empty.set(Metadata.CeSubject, fact.key).set(Metadata.CeType, topic.contractName)), 10.seconds): Unit

  /** The facts published on a topic so far, as its consumers would read them. */
  def facts[F <: Fact](topic: Topic[F]): Vector[F] =
    broker.publishedTo(topic.name).toVector.flatMap(d => Try(topic.serializer.fromBytes(d.message.payload)).toOption)

  /** The bytes of each fact on a topic, as text: what a reader of the topic could see. */
  def factTexts(topic: Topic[?]): Vector[String] =
    broker.publishedTo(topic.name).toVector.map(d => String(d.message.payload, java.nio.charset.StandardCharsets.UTF_8))

  /** A second instance of the service, joined to this one's cluster and database, answering on its own port. */
  def peer(endpoints: Seq[EndpointClients => HttpEndpoint] = PublicApiService.endpoints(settings, contract, () => Kit.authenticate,
      () => clock.instant())): (AnkkaTestKit.Peer, String) =
    val http = HttpServer.at("127.0.0.1", 0)(endpoints*)
    val p    = testKit.startPeer(Seq(ProjectionRuntime.withBroker(broker, broker), TimerRuntime(200.millis, TimerProbe(), clock), http))
    (p, s"http://127.0.0.1:${http.boundPort.get}")

  /** Polls until `f` answers, as a view's or consumer's effect must be waited for. */
  def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A =
    testKit.eventually(what, within)(f)

object Kit:
  /**
   * One keyring for every kit and peer in the test JVM: ankka installs a keyring process-wide (`Keyring.install`, the
   * last service started wins), so kits with keyrings of their own would read each other's sealed fields as
   * unavailable. A stopped peer uninstalls it, so `stopPeer` installs the shared kit's again.
   */
  val keyring: InMemoryKeyring = InMemoryKeyring()

  def stopPeer(peer: AnkkaTestKit.Peer): Unit =
    peer.stop()
    com.thinkmorestupidless.ankka.core.Keyring.install(
      com.thinkmorestupidless.ankka.runtime.keyring.ServiceKeyring.of(SharedKit.kit.testKit.service.system))

  val mapper: ObjectMapper = ObjectMapper()

  /** Stands in for Keycloak: what is tested is what the route classes decide once a token is verified. */
  val authenticate: Acl = Acl.Authenticate { context =>
    context.header("authorization").map(_.stripPrefix("Bearer ")) match
      case None => AuthDecision.Unauthenticated("realm=\"eitheror\"")
      case Some(token) =>
        token.split('|').toList match
          case "staff" :: id :: roles :: Nil =>
            AuthDecision.Allow(Principal(id, None, None, false, roles.split(',').filter(_.nonEmpty).toSet,
              Map("iss" -> "https://auth.example/realms/eitheror"), Some("staff")))
          case _ => AuthDecision.Unauthenticated("realm=\"eitheror\", error=\"invalid_token\"")
  }

/** One service per test JVM for every suite that shares it. */
object SharedKit:
  lazy val kit: Kit =
    val k = Kit()
    sys.addShutdownHook(k.stop())
    k

  /** A second brand in a second instance on its own database (isolation, US2). */
  lazy val other: Kit =
    val k = Kit("spinvibe")
    sys.addShutdownHook(k.stop())
    k

  /**
   * The kit the suites that start a second instance use (`Kit.peer`): a stopped peer leaves its cluster's shards to
   * move, which would stall the other suites' views and consumers on the shared kit.
   */
  lazy val peered: Kit =
    val k = Kit()
    sys.addShutdownHook(k.stop())
    k

  /** The kit the player-ranking suites share (US6): its rankings hold only the players they made. */
  lazy val ranked: Kit =
    val k = Kit()
    sys.addShutdownHook(k.stop())
    k
