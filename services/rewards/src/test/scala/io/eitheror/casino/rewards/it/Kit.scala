package io.eitheror.casino.rewards.it

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.rewards.{Json, RewardsRuntime, RewardsService, Settings}
import io.eitheror.casino.rewards.clients.{GameProvider, Players, Programme, Wallet}
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** An HTTP answer from the service. */
final case class Reply(status: Int, text: String):
  def json: JsonNode             = Json.parse(text).getOrElse(throw AssertionError(s"not JSON ($status): $text"))
  def str(field: String): String = Json.parse(text).flatMap(n => Option(n.get(field))).filterNot(_.isNull).map(_.asText).getOrElse("")

/**
 * The whole service against a throwaway Postgres (ankka's integration kit), as payouts': the brand's wallet as
 * [[FakeWallet]], `game-provider` as [[FakeGameProvider]] and `players` as [[FakePlayers]] under `localServices`, the
 * programme's code document at [[FakeProgramme]]'s address, an in-memory broker for every topic the service reads and
 * publishes, timers polled every 200 ms, and a clock the kit moves. A token `player|<id>` is a player of the brand;
 * `staff|<subject>|<roles>` an operator. The waits are shortened so a suite sees a poll or a refresh in seconds.
 */
final class Kit(base: Settings = Kit.Base):
  val slug      = base.brand
  val wallet    = FakeWallet(slug)
  val provider  = FakeGameProvider(slug)
  val players   = FakePlayers(slug)
  val programme = FakeProgramme()
  val broker    = InMemoryBroker()

  /** The kit's settings: the programme's document is the fake's unless the suite named one. */
  val settings: Settings = base.copy(codeDocumentUrl = base.codeDocumentUrl.orElse(Some(programme.url(slug))))

  @volatile var clockOffset: JDuration = JDuration.ZERO
  def now(): Instant              = Instant.now().plus(clockOffset)
  def advance(d: JDuration): Unit = clockOffset = clockOffset.plus(d)
  /** The clock at `at` from now on, moving with real time. */
  def setClock(at: Instant): Unit = clockOffset = JDuration.between(Instant.now(), at)

  private def rewire(): Unit =
    RewardsRuntime.wire(settings, () => now())
    RewardsRuntime.wallet = services => Wallet.http(services, settings)
    RewardsRuntime.gameProvider = services => GameProvider.http(services, settings)
    RewardsRuntime.players = services => Players.http(services, settings)
    RewardsRuntime.programme = _ => Programme.http(settings)
    io.eitheror.casino.rewards.steps.Wiring.install()
  rewire()

  /** Every log line of the service, from the start, for the leak suite. */
  val log: ListAppender[ILoggingEvent] = ListAppender[ILoggingEvent]()
  log.start()
  LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].addAppender(log)

  private val timers = TimerRuntime(200.millis)
  private val server = HttpServer.at("127.0.0.1", 0)(RewardsService.endpoints(() => Kit.authenticate)*)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    RewardsService.descriptors,
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, server),
    localServices = Map(settings.wallet -> wallet.address, settings.gameProvider -> provider.address, settings.players -> players.address)
  )
  RewardsService.started(timers)

  def views      = testKit.service.viewClient
  def components = testKit.componentClient
  /** The service's own clients of its neighbours, through `localServices`, as a component's context has them. */
  def services   = testKit.service.services
  def scheduler  = timers.timerScheduler
  def baseUrl    = s"http://127.0.0.1:${server.boundPort.get}"

  def stop(): Unit =
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].detachAppender(log): Unit
    testKit.stop()
    wallet.stop()
    provider.stop()
    players.stop()
    programme.stop()

  /** Back to the kit's own clock, settings and fakes, for the next suite. */
  def reset(): Unit =
    wallet.reset()
    provider.reset()
    players.reset()
    programme.reset()
    clockOffset = JDuration.ZERO
    rewire()

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

  private def gatewayCaller = testKit.asCaller(Caller.Gateway)
  def asPlayer(playerId: String): Seq[(String, String)] = Seq(gatewayCaller, "Authorization" -> s"Bearer player|$playerId")
  /** An operator of the brand holding `roles` beside the brand's own role (research R7's admin roles). */
  def asOperator(roles: String = "brand_admin", subject: String = "op-1"): Seq[(String, String)] =
    Seq(gatewayCaller, "Authorization" -> s"Bearer staff|$subject|brand_$slug,$roles")
  def asService(name: String, project: String = "local"): Seq[(String, String)] = Seq(testKit.asCaller(Caller.Service(project, name)))

  /** The four prefixes (research R7). */
  def rewardsPath(rest: String)   = s"/v1/casino/brands/$slug/rewards$rest"
  def offersPath(rest: String)    = s"/v1/casino/brands/$slug/offers$rest"
  def grantsPath(rest: String)    = s"/v1/casino/brands/$slug/reward-grants$rest"
  def campaignsPath(rest: String) = s"/v1/casino/brands/$slug/reward-campaigns$rest"

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
    Kit.query(testKit.jdbcUrl, s"SELECT count(*) FROM ankka_timers WHERE timer_name = '${name.replace("'", "''")}'").headOption.contains("1")

  /** The timer named, due now as far as the timer runtime is concerned. */
  def fireTimer(name: String): Unit =
    val c = java.sql.DriverManager.getConnection(testKit.jdbcUrl, "ankka", "ankka")
    try
      val s = c.prepareStatement("UPDATE ankka_timers SET due_at = now() WHERE timer_name = ?")
      s.setString(1, name)
      s.executeUpdate(): Unit
    finally c.close()

  def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A = testKit.eventually(what, within)(f)

object Kit:
  /** The original's waits, shortened: the provider polled every 1 s, the code document refreshed every 2 s, stale after 6 s. */
  val Base: Settings = Settings(brand = "goldvibe", pollEvery = JDuration.ofSeconds(1), codesEvery = JDuration.ofSeconds(2),
    codesStaleAfter = JDuration.ofSeconds(6))

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
