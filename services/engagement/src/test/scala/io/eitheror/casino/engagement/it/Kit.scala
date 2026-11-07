package io.eitheror.casino.engagement.it

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.{EntityId, Metadata}
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, HttpServer, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.engagement.{EngagementRuntime, EngagementService, Json, Settings}
import io.eitheror.casino.engagement.config.{Configuration, CrmConfig, Secrets}
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
 * The whole service against a throwaway Postgres (ankka's integration kit), as rewards': Customer.io as [[FakeCrm]]
 * at the settings' two URLs, `players` and `rewards` as [[FakePlayers]] and [[FakeRewards]] under `localServices`,
 * an in-memory broker for every topic, timers polled every 200 ms, a keyring in memory, and a clock the kit moves.
 * A token `player|<id>` is a player of the brand; `staff|<subject>|<roles>` an operator. The attempts' waits are
 * shortened so a parked send is reached in under a second.
 */
final class Kit(base: Settings = Kit.Base):
  val slug    = base.brand
  val crm     = FakeCrm()
  val players = FakePlayers(slug)
  val rewards = FakeRewards(slug)
  val broker  = InMemoryBroker()

  val settings: Settings = base.copy(trackUrl = Some(crm.trackUrl), appUrl = Some(crm.appUrl))

  @volatile var clockOffset: JDuration = JDuration.ZERO
  def now(): Instant              = Instant.now().plus(clockOffset)
  def advance(d: JDuration): Unit = clockOffset = clockOffset.plus(d)
  def setClock(at: Instant): Unit = clockOffset = JDuration.between(Instant.now(), at)

  private def rewire(): Unit = EngagementRuntime.wire(settings, () => now())
  rewire()

  /** Every log line of the service, from the start, for the leak suite. */
  val log: ListAppender[ILoggingEvent] = ListAppender[ILoggingEvent]()
  log.start()
  LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].addAppender(log)

  private val timers = TimerRuntime(200.millis)
  private val server = HttpServer.at("127.0.0.1", 0)(EngagementService.endpoints(() => Kit.authenticate, project = "local")*)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    EngagementService.descriptors,
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, server),
    localServices = Map(settings.players -> players.address, settings.rewards -> rewards.address)
  )
  EngagementService.started(timers)

  def views      = testKit.service.viewClient
  def components = testKit.componentClient
  def secrets    = testKit.secrets
  def scheduler  = timers.timerScheduler
  def baseUrl    = s"http://127.0.0.1:${server.boundPort.get}"

  /** The brand configured against the fake: its site, its three keys in the secret store, and `triggers`. */
  def configure(triggers: Map[String, String] = Map.empty, region: String = "us"): CrmConfig =
    val c = CrmConfig(FakeCrm.SiteId, region, s"hello@$slug.example", triggers)
    components.forKeyValueEntity(EntityId(slug)).call(Configuration.put).invoke(c): Unit
    secrets.put(Secrets.track(slug), FakeCrm.TrackKey)
    secrets.put(Secrets.app(slug), FakeCrm.AppKey)
    secrets.put(Secrets.signing(slug), Kit.SigningKey)
    EngagementRuntime.secrets.forget()
    c

  def stop(): Unit =
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].detachAppender(log): Unit
    testKit.stop()
    crm.stop()
    players.stop()
    rewards.stop()

  /** Back to the kit's own clock, settings and fakes, for the next suite. */
  def reset(): Unit =
    crm.reset()
    players.reset()
    rewards.reset()
    clockOffset = JDuration.ZERO
    rewire()

  /** The service stopped and started again on the same database, keyring and secrets; `whileStopped` runs between. */
  def restartService(whileStopped: => Unit = ()): Unit =
    testKit.restartService(whileStopped = whileStopped)
    EngagementService.started(timers)

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
  def asOperator(roles: String = "brand_admin", subject: String = "op-1"): Seq[(String, String)] =
    Seq(gatewayCaller, "Authorization" -> s"Bearer staff|$subject|brand_$slug,$roles")
  def asService(name: String, project: String = "local"): Seq[(String, String)] = Seq(testKit.asCaller(Caller.Service(project, name)))

  def engagementPath(rest: String) = s"/v1/casino/brands/$slug/engagement$rest"

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
  /** The attempts' first wait 50 ms (so four attempts take under a second), and a CRM call bounded at 1 s. */
  val Base: Settings = Settings(brand = "goldvibe", attemptBase = JDuration.ofMillis(50), crmTimeout = JDuration.ofSeconds(1),
    secretsTtl = JDuration.ofMillis(200))

  val SigningKey = "kit-signing-key"

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
