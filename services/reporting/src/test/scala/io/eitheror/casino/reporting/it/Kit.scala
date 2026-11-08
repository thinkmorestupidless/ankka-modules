package io.eitheror.casino.reporting.it

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.core.{EntityId, Metadata}
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, HttpServer, LocalCallers, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, MovableClock, TimerProbe}
import io.eitheror.casino.contract.facts.{Fact, Topic}
import io.eitheror.casino.reporting.{ReportingService, Settings}
import io.eitheror.casino.reporting.intake.Coverage
import io.eitheror.casino.reporting.settings.{ReportingSettings, ReportingSettingsState}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Instant, LocalDate, ZoneId}
import java.util.concurrent.atomic.AtomicInteger
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
 * The whole service against a throwaway Postgres (ankka's integration kit, research R17), as attribution's: an
 * in-memory broker carrying the brand's topics, timers polled every 200 ms on a clock the test moves, in the zone
 * `Africa/Johannesburg` beside the casino module. A stand-in for Keycloak reads `staff|<id>|<roles>` as an operator and
 * `player|<id>` as a player. At start it publishes one fact on each topic `Days` folds at the midnight of `SeedDay`, so
 * every view is complete from that day and a suite's period after it is never `incomplete_period`; every suite takes
 * days of its own with `days`, since the database and the brand's days outlive suites.
 */
final class Kit(val brand: String = "goldvibe", modules: Set[String] = Set("casino", "reporting")):
  val zone: ZoneId = ZoneId.of("Africa/Johannesburg")
  val broker       = InMemoryBroker()
  val clock        = MovableClock.at(Instant.parse("2026-11-08T08:00:00Z"))
  val probe        = TimerProbe()

  @volatile private var current: Settings = Settings(brand, project = brand, reportingZone = zone, modules = modules)
  def settings: Settings = current

  val timers = TimerRuntime(200.millis, probe, clock)
  ReportingService.wire(current)
  private val server = HttpServer.at("127.0.0.1", 0)(ReportingService.endpoints(() => Kit.authenticate, () => clock.instant())*)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    ReportingService.descriptors(current, clock),
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, server)
  )
  probe.bind(testKit)
  ReportingService.started(current, testKit.service.viewClient, timers.timerScheduler)

  def base: String = s"http://127.0.0.1:${server.boundPort.get}"
  def components   = testKit.componentClient
  def views        = testKit.service.viewClient

  /** Every currency a fact the kit published was in: what the brand holds figures in. */
  private val currencies = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
  def heldCurrencies: Vector[String] = currencies.asScala.toVector.sorted

  /** Every fact set published on this kit, so a figure over the whole brand is held to all of them. */
  private val sets = java.util.concurrent.ConcurrentLinkedQueue[FactSet]()
  def remember(set: FactSet): Unit = sets.add(set): Unit

  /** What the whole brand must show, over every set published so far. */
  def everything: FactSet.Brand = FactSet.Brand(sets.asScala.toVector, zone)

  FactSet.seed(this, Kit.SeedDay)

  /**
   * The rebuild of research R8: the service stopped and started on the next generation, whose consumers read every
   * topic again from the earliest offset into new entity ids. Answers the new generation.
   */
  def rebuild(): Int =
    val next = current.copy(generation = current.generation + 1)
    testKit.stopService()
    current = next
    ReportingService.wire(next)
    Coverage.forget()
    testKit.startService(descriptors = ReportingService.descriptors(next, clock))
    ReportingService.started(next, testKit.service.viewClient, timers.timerScheduler)
    next.generation

  def stop(): Unit = testKit.stop()

  // ── time ────────────────────────────────────────────────────────────────────────────────────────

  def now: Instant = clock.instant()
  def today: LocalDate = now.atZone(zone).toLocalDate

  /** Moves the clock forward; the timers run what fell due within a poll. */
  def moveClock(by: FiniteDuration): Unit = clock.moveBy(by)

  /**
   * A today of its own, for what reads today and yesterday (the dashboard): the clock moved to 10:00 in the zone two days
   * on, so today and yesterday are days no set has published on yet. Every block of `days` is long before it.
   */
  def freshToday(): LocalDate = synchronized {
    val day = today.plusDays(2)
    clock.moveBy(java.time.Duration.between(now, day.atTime(10, 0).atZone(zone).toInstant).toMillis.millis)
    day
  }

  /** The first of `Kit.Block` reporting days no other suite of this JVM uses. */
  def days(): LocalDate = Kit.FirstDay.plusDays(Kit.Block.toLong * Kit.blocks.getAndIncrement())

  // ── the brand's reporting settings ──────────────────────────────────────────────────────────────

  def reporting: ReportingSettingsState =
    components.forKeyValueEntity(EntityId(ReportingSettings.Id)).call(ReportingSettings.get).invoke()

  def setReporting(f: ReportingSettingsState => ReportingSettingsState): Unit =
    components.forKeyValueEntity(EntityId(ReportingSettings.Id)).call(ReportingSettings.put).invoke(f(reporting)): Unit

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────

  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  def request(method: String, path: String, body: Option[String] = None, headers: Seq[(String, String)] = Nil): Reply =
    val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(30))
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(t => HttpRequest.BodyPublishers.ofString(t)))
    if body.isDefined then b.header("Content-Type", "application/json")
    headers.foreach((k, v) => b.header(k, v))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    Reply(r.statusCode, r.body, r.headers.map.asScala.view.mapValues(_.asScala.toVector).toMap)

  /** A path under the endpoint's prefix. */
  def path(rest: String, slug: String = brand): String = s"/v1/casino/brands/$slug/reporting$rest"

  def get(rest: String, as: Seq[(String, String)]): Reply = request("GET", path(rest), headers = as)

  // ── who is calling ──────────────────────────────────────────────────────────────────────────────

  /** A request from the internet, through the gateway, with no token. */
  def fromInternet: Seq[(String, String)] = Seq(LocalCallers.header(Caller.Gateway))

  /** An operator through the gateway holding `roles`; the brand's role is added unless `brandRole` is false. */
  def asStaff(roles: Seq[String], op: String = "op-1", brandRole: Boolean = true): Seq[(String, String)] =
    val all = (if brandRole then Seq(s"brand_$brand") else Nil) ++ roles
    fromInternet :+ ("Authorization" -> s"Bearer staff|$op|${all.mkString(",")}")

  /** Finance: an operator of the brand holding `brand_admin`, whom the Reports tab admits. */
  def asFinance: Seq[(String, String)] = asStaff(Seq("brand_admin"), "finance-1")

  /** An operator of the brand with no role beyond the brand's. */
  def asOperator: Seq[(String, String)] = asStaff(Nil)

  def asPlayer(player: String = "p-1"): Seq[(String, String)] = fromInternet :+ ("Authorization" -> s"Bearer player|$player")

  /** A service of this project, as `players` calls the erasure route. */
  def asService(name: String = "players", project: String = "local"): Seq[(String, String)] =
    Seq(LocalCallers.header(Caller.Service(project, name)))

  // ── the broker ──────────────────────────────────────────────────────────────────────────────────

  /** Publishes a fact on its topic as its owner would; the intake has folded it when this returns. */
  def publish[F <: Fact](topic: Topic[F], fact: F, currency: Option[String] = None): Unit =
    currency.foreach(currencies.add)
    Await.result(broker.publish(topic.name, Some(fact.key), topic.serializer.toBytes(fact),
      Metadata.empty.set(Metadata.CeSubject, fact.key).set(Metadata.CeType, topic.contractName)), 10.seconds): Unit

  /** Polls until `f` answers, as a view's or consumer's effect must be waited for. */
  def eventually[A](what: String, within: FiniteDuration = 30.seconds)(f: => Option[A]): A =
    testKit.eventually(what, within)(f)

object Kit:
  val mapper: ObjectMapper = ObjectMapper()

  /** The day the kit's seed facts fall on, at its midnight in the zone: every view is complete from it. */
  val SeedDay: LocalDate = LocalDate.parse("2024-12-01")

  /** The first day a suite is given, after the seed day; each suite's days are a block of their own. */
  val FirstDay: LocalDate = LocalDate.parse("2025-01-05")
  val Block              = 14
  private val blocks     = AtomicInteger(0)

  /** Stands in for Keycloak: what is tested is what the route classes decide once a token is verified. */
  val authenticate: Acl = Acl.Authenticate { context =>
    context.header("authorization").map(_.stripPrefix("Bearer ")) match
      case None => AuthDecision.Unauthenticated("realm=\"eitheror\"")
      case Some(token) =>
        token.split('|').toList match
          case "staff" :: id :: roles :: Nil =>
            AuthDecision.Allow(Principal(id, None, None, false, roles.split(',').filter(_.nonEmpty).toSet,
              Map("iss" -> "https://auth.example/realms/eitheror"), Some("staff")))
          case "player" :: id :: Nil =>
            AuthDecision.Allow(Principal(id, None, None, false, Set.empty, Map("iss" -> "https://auth.example/realms/casino-goldvibe"),
              Some("player")))
          case _ => AuthDecision.Unauthenticated("realm=\"eitheror\", error=\"invalid_token\"")
  }

/** One service per test JVM for every suite that shares it. */
object SharedKit:
  lazy val kit: Kit =
    val k = Kit()
    sys.addShutdownHook(k.stop())
    k
