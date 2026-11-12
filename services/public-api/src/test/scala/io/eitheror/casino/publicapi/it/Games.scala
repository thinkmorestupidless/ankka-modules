package io.eitheror.casino.publicapi.it

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.publicapi.records.{Records, Rounds}
import io.eitheror.casino.publicapi.stats.{PointerState, StatsRunner}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/**
 * `game-provider`'s `GET /v1/casino/brands/{brand}/game-provider/games` (T015, facts.md G8): the brand's listed games
 * as the route answers them, set by the test. `status` answers every call with that status instead.
 */
final class FakeGameProvider(brand: String):
  val games: AtomicReference[Vector[String]] = AtomicReference(Vector.empty)
  val status: AtomicReference[Int]           = AtomicReference(200)
  val calls: AtomicInteger                   = AtomicInteger()

  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
  server.createContext("/", (ex: HttpExchange) =>
    calls.incrementAndGet()
    val path = ex.getRequestURI.getRawPath
    val (code, body) =
      if path != s"/v1/casino/brands/$brand/game-provider/games" then (404, """{"error":"not_found"}""")
      else if status.get != 200 then (status.get, """{"error":"unavailable"}""")
      else (200, s"""{"games":[${games.get.mkString(",")}]}""")
    val bytes = body.getBytes(UTF_8)
    ex.getResponseHeaders.add("Content-Type", "application/json")
    ex.sendResponseHeaders(code, bytes.length.toLong)
    ex.getResponseBody.write(bytes); ex.close())
  server.start()

  val address: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  def set(gs: String*): Unit = games.set(gs.toVector)
  def add(gs: String*): Unit = games.updateAndGet(_ ++ gs): Unit

object FakeGameProvider:
  /** One game as `CatalogueReads.toJson` answers it, with `removedFromFeed`. */
  def game(code: String, name: String = "", category: String = "slots", releaseDate: Option[String] = None, removed: Boolean = false): String =
    s"""{"gameId":"$code","provider":"st8","name":"${if name.isEmpty then s"Game $code" else name}","category":"$category",""" +
      s""""developerId":"dev-a","developerName":"Developer A","tags":[],"enabled":true,"funMode":true,"bonusTypes":["free_spins"],""" +
      s""""features":[],"themes":["Egypt"],"restrictedTerritories":[],"prohibitedTerritories":[],"thumbnailUrl":null,"rtp":"96.5",""" +
      s""""volatility":3,"hitRatio":null,"maxPayoutCoeff":null,"releaseDate":${releaseDate.fold("null")(d => s""""$d"""")},""" +
      s""""deprecationDate":null,"removedFromFeed":$removed}"""

/** Rounds and sessions of chosen games, and a run of the figures, for the game suites (US5). */
object Games:
  def code(): String = s"g-${java.util.UUID.randomUUID().toString.take(8)}"

  /** A settled round of `game` with real stake and win, its id; free spins carry a grant, cancelled the outcome. */
  def round(kit: Kit, game: String, player: String, at: Instant, stake: Long, win: Long, currency: String = "EUR",
      outcome: String = "win", free: Boolean = false): String =
    val key = s"st8/${FactSet.id()}"
    val grant = Option.when(free)("grant-1")
    kit.publish(f.CasinoRounds, FactSet.settled(player, key, FactSet.id(), at, stake, win, outcome, game,
      real = Option.when(!free)((stake, win)), grant = grant, currency = Currency.unsafe(currency)))
    key

  def session(kit: Kit, game: String, player: String, at: Instant): Unit =
    kit.publish(f.CasinoSessions, FactSet.sessionStarted(player, FactSet.id(), at, game))

  /** Waits for the rounds to reach the view, so a run reads them. */
  def seen(kit: Kit, keys: Seq[String]): Unit =
    kit.eventually("the rounds in the view")(Option.when(keys.forall(k => kit.views.forView(Rounds).get(Records.roundId(k)).exists(_.status.nonEmpty)))(()))

  def compute(kit: Kit): PointerState = StatsRunner(kit.components, kit.views, kit.brand, () => kit.now).run()
