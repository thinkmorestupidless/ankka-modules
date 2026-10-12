package io.eitheror.casino.simulator

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Principal}
import com.thinkmorestupidless.ankka.runtime.{ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.eitheror.casino.simulator.api.Access
import io.eitheror.casino.simulator.brands.BrandTable
import io.eitheror.casino.simulator.core.*
import io.eitheror.casino.simulator.st8.Keys

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration as JDuration
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * The simulator over a real runtime and a throwaway Postgres, with a fake brand on a scripted HTTP
 * server (research R11). One per test JVM: the core's wiring is process-wide, as in a deployment.
 * `goldvibe` is a test brand; `realvibe` is not (FR-025).
 */
object SimulatorTestKit:

  final case class Answer(status: Int, body: String, contentType: String)

  /** Stands in for ankka-auth-oidc's verifier, as the contract's suites do: a token is `<issuer>|<iss>|<roles,...>`. */
  val verified: Acl = Acl.Authenticate { context =>
    context.header("authorization").map(_.stripPrefix("Bearer ")) match
      case None => AuthDecision.Unauthenticated("realm=\"eitheror\"")
      case Some(token) =>
        token.split('|') match
          case Array(issuer, iss, roles) =>
            AuthDecision.Allow(Principal("subject", roles = roles.split(',').filter(_.nonEmpty).toSet, claims = Map("iss" -> iss), issuer = Some(issuer)))
          case _ => AuthDecision.Unauthenticated("realm=\"eitheror\", error=\"invalid_token\"")
  }

  def staff(roles: String*): String = s"staff|https://auth-casino.example/realms/eitheror|${roles.mkString(",")}"

  lazy val kit: Kit = Kit()

  final class Kit:
    private val holder = AtomicReference[Simulator]()
    private var started: AnkkaTestKit = null
    private var simulatorPort         = 0
    private var brandHolder: FakeBrand = null

    val http: HttpClient = HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(10)).build()

    // The brand table names the fake brand's address, which exists only once it has started; the
    // fake brand needs the simulator's keys, which exist only once the runtime has. So the sender is
    // rebuilt with the brand's address after both are up.
    lazy val table: BrandTable = BrandTable.parse(
      s"""[{"slug":"goldvibe","baseAddress":"http://127.0.0.1:0","testBrand":true,"providerWaitMillis":2000,"keyRefreshBoundSeconds":1},
         | {"slug":"realvibe","baseAddress":"http://127.0.0.1:0","testBrand":false}]""".stripMargin,
      "http://simulator.test", Some("30"), None, Map.empty
    ).fold(e => throw IllegalStateException(e), identity)

    def start(): Unit = synchronized {
      if started == null then
        val server = Simulation.server(tableWithBrand, Access(verified), Some(("127.0.0.1", 0)), holder)
        started = AnkkaTestKit.start(Simulation.descriptors, Seq(ProjectionRuntime(), TimerRuntime(200.millis), server))
        simulatorPort = server.boundPort.getOrElse(throw IllegalStateException("the simulator did not bind"))
        val keys = Simulation.install(started.service, tableWithBrand)
        Simulation.prepare(started.service, tableWithBrand, keys, None, Option(holder.get()))
        brandHolder = FakeBrand(keys)
        // the brand's address is known now: point both brands at it
        rebuildTable(brandHolder.address)
        sys.addShutdownHook(stop())
    }

    @volatile private var current: BrandTable = null
    private def tableWithBrand: BrandTable = Option(current).getOrElse(table)

    private def rebuildTable(address: String): Unit =
      val rebuilt = table.copy(brands = table.brands.map(b => b.copy(baseAddress = URI.create(address))))
      current = rebuilt
      Wiring.install(Wiring.protocol, io.eitheror.casino.simulator.brands.Sender(rebuilt, keys), keys, rebuilt, () => clock())

    @volatile var offset: Long = 0L
    /** The core's clock, which a suite moves forward to age runs (FR-022). */
    def clock(): Long = System.currentTimeMillis() + offset

    def stop(): Unit = synchronized {
      if started != null then
        started.stop()
        brandHolder.stop()
        started = null
    }

    def testKit: AnkkaTestKit = { start(); started }
    def brand: FakeBrand      = { start(); brandHolder }
    def keys: Keys            = Keys(testKit.secrets)
    def sim: Simulator        = { start(); holder.get() }
    def base: String          = { start(); s"http://127.0.0.1:$simulatorPort" }

    def call(method: String, path: String, body: Option[String] = None, token: Option[String] = None): Answer =
      val builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(JDuration.ofSeconds(60))
      token.foreach(t => builder.header("Authorization", s"Bearer $t"))
      body match
        case Some(json) =>
          builder.header("Content-Type", "application/json")
          builder.method(method, HttpRequest.BodyPublishers.ofString(json)): Unit
        case None => builder.method(method, HttpRequest.BodyPublishers.noBody()): Unit
      val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
      Answer(response.statusCode(), response.body(), response.headers().firstValue("content-type").orElse(""))

    def get(path: String, token: Option[String] = None): Answer                  = call("GET", path, None, token)
    def post(path: String, body: String, token: Option[String] = None): Answer   = call("POST", path, Some(body), token)
    def put(path: String, body: String, token: Option[String] = None): Answer    = call("PUT", path, Some(body), token)

    /** A launch as a brand makes one: the brand issues the ticket, then calls the provider's launch. */
    def launch(ticket: String, player: String, game: String = "book-of-ra"): Answer =
      brand.issue(ticket, player)
      post("/api/operator/v1/launch", s"""{"game_code":"$game","currency":"EUR","token":"$ticket","player":"$player","fun_mode":false,"site":"goldvibe"}""")

    /** Launch and load the game page: a session ready for instructions. */
    def open(ticket: String, player: String, game: String = "book-of-ra"): Session =
      launch(ticket, player, game)
      get(s"/game?token=$ticket")
      session(ticket)

    def session(ticket: String): Session =
      testKit.componentClient.forEventSourcedEntity(EntityId(ticket)).call(SessionEntity.get).invoke()

    /** Puts a scenario as an operator would (FR-019). */
    def putScenario(json: String): Answer =
      val name = """"name"\s*:\s*"([^"]+)"""".r.findFirstMatchIn(json).map(_.group(1)).get
      put(s"/v1/simulator/scenarios/$name", json, Some(staff("platform_admin")))

    def select(ticket: String, scenario: String): Answer =
      put(s"/v1/simulator/brands/goldvibe/sessions/$ticket/scenario", s"""{"name":"$scenario"}""", Some(staff("brand_goldvibe")))

    /** Waits for a condition the eventually consistent parts settle into. */
    def eventually[A](within: FiniteDuration = 30.seconds)(probe: => Option[A]): A =
      val deadline = System.nanoTime() + within.toNanos
      var found    = probe
      while found.isEmpty && System.nanoTime() < deadline do
        Thread.sleep(100)
        found = probe
      found.getOrElse(throw AssertionError(s"nothing within $within"))

    /** The session's verdicts once its scenario has finished. */
    def verdict(ticket: String): Answer =
      eventually(60.seconds) {
        val a = get(s"/v1/simulator/brands/goldvibe/runs/$ticket/verdict", Some(staff("brand_goldvibe")))
        Option.when(a.status == 200 && a.body.contains("\"finished\":true"))(a)
      }
