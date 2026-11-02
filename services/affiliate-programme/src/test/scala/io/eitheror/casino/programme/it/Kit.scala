package io.eitheror.casino.programme.it

import com.fasterxml.jackson.databind.JsonNode
import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, HttpServer, LocalCallers, Principal}
import com.thinkmorestupidless.ankka.runtime.{InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, MovableClock, TimerProbe}
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.facts.{AffiliatesAttribution, AttributionFact}
import io.eitheror.casino.programme.{ProgrammeService, Settings}
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.brands.{CreatePlan, Plan, Seeding}
import io.eitheror.casino.programme.links.{CreateLink, Link}
import io.eitheror.casino.programme.sealing.{Digest, Identity, Sealing}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, Instant}
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.*

/** An HTTP answer from the service. */
final case class Reply(status: Int, text: String, headers: Map[String, Vector[String]] = Map.empty):
  def json: JsonNode =
    scala.util.Try(FakeServer.mapper.readTree(text)).toOption.filter(_ != null).getOrElse(throw AssertionError(s"not JSON ($status): $text"))
  def header(name: String): Option[String] = headers.collectFirst { case (k, v) if k.equalsIgnoreCase(name) => v.head }

/**
 * The whole programme against a throwaway Postgres (ankka's integration kit): an in-memory broker
 * carrying `affiliates.attribution` of the brands here, `goldvibe` and `silvervibe`; timers on a clock
 * the test moves; Customer.io and the realm in process; postbacks to `PostbackReceiver`. A stand-in for
 * Keycloak reads a bearer token `staff|<id>|<roles>` as a manager and `affiliate|<id>` as an affiliate
 * whose `eitheror_scope_id` is that id.
 */
final class Kit(val brands: Vector[String] = Vector("goldvibe", "silvervibe"), beforeStart: Kit => Unit = _ => ()):
  val broker    = InMemoryBroker()
  val clock     = MovableClock.at(Instant.parse("2026-10-25T08:00:00Z"))
  val probe     = TimerProbe()
  val customerIo = FakeCustomerIo()
  val realm     = FakeKeycloak()
  val postbacks = PostbackReceiver()

  val settings = Settings(brands = brands, outboundTimeout = Duration.ofSeconds(2), plainHttp = true)

  val timers = TimerRuntime(200.millis, probe, clock)
  private val server = HttpServer.at("127.0.0.1", 0)(ProgrammeService.endpoints(settings, () => Kit.authenticate, realm, () => clock.instant())*)

  wire()
  beforeStart(this)

  val testKit: AnkkaTestKit = AnkkaTestKit.start(
    ProgrammeService.descriptors(settings),
    Seq(ProjectionRuntime.withBroker(broker, broker), timers, server)
  )
  probe.bind(testKit)
  // The seed the start timer runs, run now so every suite begins seeded.
  Seeding.run(testKit.componentClient, testKit.secrets, brands, clock.instant())

  def base: String = s"http://127.0.0.1:${server.boundPort.get}"
  def components   = testKit.componentClient
  def views        = testKit.service.viewClient
  def secrets      = testKit.secrets

  private def wire(): Unit = ProgrammeService.wire(settings, customerIo, clock, realm)

  /** Points the process-wide wiring back at this kit, after another kit in the same JVM took it. */
  def rewire(): Unit = wire()

  def stop(): Unit = { testKit.stop(); postbacks.stop() }

  /** Clears the fakes between scenarios. The database keeps what earlier scenarios wrote; ids are fresh per scenario. */
  def reset(): Unit = { customerIo.reset(); realm.reset(); postbacks.reset() }

  def fresh(prefix: String): String = s"$prefix-${UUID.randomUUID().toString.take(8)}"

  // ── time ────────────────────────────────────────────────────────────────────────────────────────

  def now: Instant = clock.instant()

  /** Moves the clock forward; the timers run what fell due within a poll. */
  def moveClock(to: Instant): Unit = clock.moveTo(to)

  // ── HTTP ────────────────────────────────────────────────────────────────────────────────────────

  private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  def request(method: String, path: String, body: Option[String] = None, headers: Seq[(String, String)] = Nil): Reply =
    val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(30))
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(t => HttpRequest.BodyPublishers.ofString(t)))
    if body.isDefined && !headers.exists(_._1.equalsIgnoreCase("content-type")) then b.header("Content-Type", "application/json")
    headers.foreach((k, v) => b.header(k, v))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    import scala.jdk.CollectionConverters.*
    Reply(r.statusCode, r.body, r.headers.map.asScala.view.mapValues(_.asScala.toVector).toMap)

  /** A request from the internet, through the gateway, with no token. */
  def fromInternet: Seq[(String, String)] = Seq(LocalCallers.header(Caller.Gateway))

  /** A manager holding `brand_<slug>` for each brand named, through the gateway. */
  def asManager(brands: Seq[String] = Seq("goldvibe"), id: String = "mgr-1", role: String = "affiliate_manager"): Seq[(String, String)] =
    fromInternet :+ ("Authorization" -> s"Bearer staff|$id|${(role +: brands.map(b => s"brand_$b")).mkString(",")}")

  def asPlatformAdmin(id: String = "admin-1"): Seq[(String, String)] =
    fromInternet :+ ("Authorization" -> s"Bearer staff|$id|platform_admin")

  /** An affiliate signed in, their `eitheror_scope_id` the account. */
  def asAffiliate(affiliate: String): Seq[(String, String)] =
    fromInternet :+ ("Authorization" -> s"Bearer affiliate|$affiliate")

  // ── the broker ──────────────────────────────────────────────────────────────────────────────────

  /** Publishes a fact on its brand's `affiliates.attribution`, as that brand's `attribution` would. */
  def publish(fact: AttributionFact, brand: Option[String] = None): Unit =
    val topic = s"${brand.getOrElse(fact.brand)}/${AffiliatesAttribution.name}"
    Await.result(broker.publish(topic, Some(fact.key), writeToArray(fact)(using AttributionFact.given_JsonValueCodec_AttributionFact),
      Metadata.empty.set(Metadata.CeSubject, fact.key).set(Metadata.CeType, AffiliatesAttribution.contractName)), 10.seconds): Unit

  // ── setup through the components, for what a scenario starts from ───────────────────────────────

  /** A plan at a brand, as a manager would make it; answers its id. */
  def plan(brand: String, name: String, planType: String = Plan.Cpa, cpa: Long = 0, currency: String = "EUR", revShareBp: Int = 0,
      minimumDeposit: Long = 0, carryover: Boolean = false): String =
    components.forKeyValueEntity(Plan.entityId(brand, Plan.idOf(brand, name)))
      .call(Plan.createPlan).invoke(CreatePlan(brand, name, planType, cpa, currency, revShareBp, minimumDeposit, carryover, "kit", now)): Unit
    Plan.idOf(brand, name)

  /** An account with an active membership at `brand` on `plan`, its identity sealed as the routes seal it. */
  def affiliate(id: String, brand: String, plan: String, email: Option[String] = None, parent: Option[String] = None,
      overrideBp: Int = 0): String =
    val address = email.getOrElse(s"$id@example.com")
    val store   = Secrets(secrets)
    val key     = store.dataKey(id).fold(e => throw AssertionError(e.reason), identity)
    val sealedId = Sealing.seal(Identity(id, None, address, None, None, None, None), key, id)
    val digest  = Digest.of(address, store.emailIndexKey.fold(e => throw AssertionError(e.reason), identity))
    components.forEventSourcedEntity(EntityId(id)).call(Affiliate.createAccount)
      .invoke(CreateAffiliate(id, sealedId, digest, parent, overrideBp, AccountFields(), "kit", now)): Unit
    val m = components.forEventSourcedEntity(Membership.entityId(id, brand))
    m.call(Membership.applyFor).invoke(Apply(id, brand, Application(now, None, "kit", parent))): Unit
    m.call(Membership.approve).invoke(Approve(plan, "kit", now)): Unit
    id

  /** A link of an affiliate's at a brand, under the id given (a legacy btag, in a replay). */
  def link(id: String, affiliate: String, brand: String): String =
    components.forKeyValueEntity(EntityId(id)).call(Link.createLink)
      .invoke(CreateLink(id, affiliate, brand, None, None, Map.empty, Vector.empty, None, None, "kit", now)): Unit
    id

  /** Polls until `f` answers, as a view's or consumer's effect must be waited for. */
  def eventually[A](what: String, within: FiniteDuration = 20.seconds)(f: => Option[A]): A =
    testKit.eventually(what, within)(f)

/** One service per JVM for the suites that share it (build.sbt's grouping). */
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
            AuthDecision.Allow(Principal(id, None, None, false, roles.split(',').toSet, Map("iss" -> Issuer), Some(Routes.StaffIssuer)))
          case "affiliate" :: id :: Nil =>
            AuthDecision.Allow(Principal(s"kc-$id", None, None, false, Set("affiliate"),
              Map("iss" -> Issuer, Routes.ScopeIdClaim -> id, Routes.ScopeLevelClaim -> "affiliate"), Some(Routes.StaffIssuer)))
          case _ => AuthDecision.Unauthenticated("realm=\"eitheror\", error=\"invalid_token\"")
  }
