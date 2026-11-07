package io.eitheror.casino.provider.edge

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.Caller
import com.thinkmorestupidless.ankka.runtime.{ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.eitheror.casino.provider.{FakeBrand, TestGame}
import io.eitheror.casino.provider.core.*
import io.eitheror.casino.provider.edge.brands.{BrandCache, BrandEntry, BrandEntryEntity, BrandIndex}
import io.eitheror.casino.provider.st8.{Keys, Signer, St8Protocol}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.security.KeyPair
import java.time.Duration as JDuration
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * The house provider over a real runtime and a throwaway Postgres (Docker), with `FakeBrand` as the
 * brand `goldvibe` and `TestGame` as the game service `test-game`, which calls the provider as the
 * service `games/test-game` and is reached by that name. One per test JVM: the core's wiring is
 * process-wide, as in a deployment. The retry schedule is short so a suite runs in seconds.
 */
object ProviderTestKit:

  final case class Answer(status: Int, body: String):
    def json: JsonNode = ObjectMapper().readTree(body)

  /** A short schedule of the provider's shape: every 300 ms until 20 s. */
  val schedule: Schedule = Schedule(1.second, Vector(Schedule.Interval(300.millis, None)), 20.seconds, St8Protocol.defaultRetryOn)

  lazy val kit: Kit = Kit()

  final class Kit:
    private val holder         = AtomicReference[Provider.Parts]()
    private var started: AnkkaTestKit = null
    private var port           = 0
    private var brandHolder: FakeBrand = null

    val game: TestGame        = TestGame()
    val cache: BrandCache     = BrandCache(Map.empty)
    /** The brand's operator key pair for the house provider: it signs launches and the games list. */
    val operator: KeyPair     = Signer.generate()
    val http: HttpClient      = HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(10)).build()
    val settings: Settings    = Settings(URI("http://provider.test"), 90, schedule, None, None)

    def start(): Unit = synchronized {
      if started == null then
        val server = Provider.server(settings, cache, holder, Some(("127.0.0.1", 0)))
        started = AnkkaTestKit.start(Provider.descriptors, Seq(ProjectionRuntime(), TimerRuntime(200.millis), server),
          localServices = Map(game.name -> game.address))
        port = server.boundPort.getOrElse(throw IllegalStateException("the provider did not bind"))
        val keys = Provider.install(started.service, settings, cache)
        Deliver.schedule = schedule
        Provider.prepare(started.service, cache, keys, None, Option(holder.get()))
        brandHolder = FakeBrand(keys)
        putEntry(entry("goldvibe", brandHolder.address))
        game.provider = base
        game.identity = Seq(started.asCaller(Caller.Service("games", game.name)))
        sys.addShutdownHook(stop())
    }

    def entry(slug: String, address: String): BrandEntry = BrandEntry(
      slug, address, slug, Signer.publicKeyText(operator.getPublic), "pt", testBrand = true, Vector.empty, keyDocument = false,
      providerWaitMillis = 1000, keyRefreshBoundSeconds = 1, caPem = None, realMoney = false
    )

    /** An entry written as the operator routes write one: the entity, the index, the cache. */
    def putEntry(e: BrandEntry): Unit =
      testKit.componentClient.forKeyValueEntity(EntityId(e.slug)).call(BrandEntryEntity.put).invoke(e): Unit
      testKit.componentClient.forKeyValueEntity(EntityId(BrandIndex.Id)).call(BrandIndex.add).invoke(e.slug): Unit
      cache.put(e)

    def stop(): Unit = synchronized {
      if started != null then
        started.stop()
        brandHolder.stop()
        game.server.stop()
        started = null
    }

    def testKit: AnkkaTestKit = { start(); started }
    def brand: FakeBrand      = { start(); brandHolder }
    def keys: Keys            = Keys(testKit.secrets)
    def base: String          = { start(); s"http://127.0.0.1:$port" }

    def call(method: String, path: String, body: Option[String] = None, headers: Seq[(String, String)] = Nil): Answer =
      val b = HttpRequest.newBuilder(URI.create(base + path)).timeout(JDuration.ofSeconds(60))
      headers.foreach((k, v) => b.header(k, v))
      body match
        case Some(json) => b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json)): Unit
        case None       => b.method(method, HttpRequest.BodyPublishers.noBody()): Unit
      val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
      Answer(r.statusCode(), r.body())

    /** A brand's operator call, signed as its adapter signs it: over the body. */
    def signedPost(path: String, body: String, key: KeyPair = operator): Answer =
      call("POST", path, Some(body), Seq(Signer.Header -> Signer.sign(body.getBytes("UTF-8"), key.getPrivate)))

    /** A brand's GET, signed over its query as written. */
    def signedGet(path: String, query: String, key: KeyPair = operator): Answer =
      call("GET", s"$path?$query", None, Seq(Signer.Header -> Signer.sign(query.getBytes("UTF-8"), key.getPrivate)))

    /** A launch as the brand makes one: it issues the ticket (unless told not to), then calls launch. */
    def launch(ticket: String, player: String, code: String, funMode: Boolean = false, issue: Boolean = true, site: String = "goldvibe",
        profile: String = """{"email":"player@example.com","first_name":"Ana"}"""): Answer =
      if issue then brand.issue(ticket, player)
      signedPost("/api/operator/v1/launch",
        s"""{"game_code":"$code","currency":"BRL","token":"$ticket","player":"$player","fun_mode":$funMode,"site":"$site","player_profile":$profile}""")

    def games(site: String = "goldvibe"): Answer = signedGet("/api/operator/v1/games", s"site=$site")

    def session(ticket: String): Session =
      testKit.componentClient.forEventSourcedEntity(EntityId(ticket)).call(SessionEntity.get).invoke()

    def callback(id: String): Callback =
      testKit.componentClient.forEventSourcedEntity(EntityId(id)).call(CallbackEntity.get).invoke()

    def eventually[A](within: FiniteDuration = 30.seconds)(probe: => Option[A]): A =
      val deadline = System.nanoTime() + within.toNanos
      var found    = scala.util.Try(probe).toOption.flatten
      while found.isEmpty && System.nanoTime() < deadline do
        Thread.sleep(100)
        found = scala.util.Try(probe).toOption.flatten
      found.getOrElse(throw AssertionError(s"nothing within $within"))
