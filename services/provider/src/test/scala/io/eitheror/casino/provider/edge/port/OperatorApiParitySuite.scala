package io.eitheror.casino.provider.edge.port

import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.port.{Recording, Replay}
import io.eitheror.casino.provider.edge.ProviderTestKit

import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Parity with the stand-in (spec 026 SC-001, constitution I): the recordings the simulator replays
 * (`tests/port/simulator/`, made against `fake-st8`) replayed against the house provider's operator
 * API, with the test game registering the stand-in's catalogue and `goldvibe` a test brand running
 * only those games. The stand-in admitted any caller; the provider admits a brand by its signature,
 * so each request is signed with goldvibe's key as the brand's adapter signs it (a POST over its
 * body, a GET over its query as written). The page address is under the recordings' own `vary`.
 */
class OperatorApiParitySuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val mapper = ObjectMapper()
  private val dir    = Paths.get(sys.props("casino.root"), "tests", "port", "simulator")
  private def kit    = ProviderTestKit.kit

  private val recordings = Files.list(dir).iterator().asScala.toVector.filter(_.toString.endsWith(".json")).sorted

  /** The stand-in's catalogue (`fake-st8`), in its order. */
  private val catalogue = Vector(
    ("book-of-ra", "Book of Ra", "slots", Vector("free_bets", "bonus_buy"), Vector("expanding_symbol", "gamble"), Vector("egypt", "adventure")),
    ("fake-slot-002", "Fake Slots II", "slots", Vector("bonus_buy"), Vector("cascading_reels"), Vector("fruit")),
    ("fake-table-001", "Fake Blackjack", "table", Vector.empty[String], Vector.empty[String], Vector.empty[String])
  )

  override def beforeAll(): Unit =
    kit.start()
    kit.game.up()
    catalogue.foreach { (code, name, category, bonusTypes, features, themes) =>
      val r = kit.game.registerGame(code, category, name = Some(name), bonusTypes = bonusTypes, features = features, themes = themes)
      assertEquals(r.status, 200, r.body.toString)
    }
    kit.putEntry(kit.entry("goldvibe", kit.brand.address).copy(games = catalogue.map(_._1)))

  override def afterAll(): Unit = kit.restoreGoldvibe()

  private def send(request: Recording.Request): Recording.Response =
    val answer = request.method match
      case "GET" =>
        val (path, query) = request.path.span(_ != '?')
        kit.signedGet(path, query.drop(1))
      case _ => kit.signedPost(request.path, request.body.fold("")(mapper.writeValueAsString))
    Recording.Response(answer.status, Option(answer.body).filter(_.nonEmpty).flatMap(b => Try(mapper.readTree(b)).toOption))

  test("there are recordings to replay") { assert(recordings.nonEmpty, s"no recordings under $dir") }

  /**
   * The one difference beyond the page address (research, Found during implementation, T031): every
   * house game is eitheror's (data-model, Game), so a game's `developer` and the listing's
   * `developers` are not the stand-in's studios.
   */
  private val houseDeveloper = "every house game's developer is eitheror (data-model, Game; spec 026 SC-001)"
  private def excepted(r: Recording): Recording =
    r.copy(vary = r.vary ++ (Map("$.developers" -> houseDeveloper) ++ catalogue.indices.map(i => s"$$.games[$i].developer" -> houseDeveloper)))

  recordings.foreach { path =>
    val recording = Recording.load(path).fold(e => throw IllegalStateException(e), identity)
    test(recording.scenario) {
      assertEquals(Replay.run(excepted(recording), send).map(_.message), Vector.empty)
    }
  }
