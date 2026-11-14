package io.eitheror.casino.publicapi.features

import io.eitheror.casino.publicapi.it.{FactSet, FakeGameProvider, Games, Keys, Reply, Seeded, SharedKit}
import FakeGameProvider.game

import java.time.Duration as JDuration
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** features/public-api/games.feature (T073): the catalogue through the fake game-provider route, the figures by a run. */
class GamesFeatures extends PublicApiFeatures("games.feature"):
  private def key = Seeded.none
  private var codes: Vector[String]     = Vector.empty
  private var answers: Vector[Reply]    = Vector.empty
  private var target: String            = ""
  private var player: String            = ""

  private def ago(h: Long) = kit.now.minus(JDuration.ofHours(h))
  private def listed: Vector[String] =
    kit.v1("/games?limit=100", Some(key.secret)).json.path("data").elements().asScala.map(_.path("id").asText()).toVector

  /** The provider's games, read by the service once its cache has turned. */
  private def catalogue(gs: String*)(visible: Vector[String]): Unit =
    kit.provider.set(gs*)
    kit.moveClock(6.minutes)
    kit.v1("/games", Some(key.secret)): Unit
    kit.eventually("the catalogue reloaded")(Option.when(listed.sorted == visible.sorted)(()))

  private def computed(keys: Seq[String]): Unit = { Games.seen(kit, keys); Games.compute(kit): Unit }
  private def rows(r: Reply) = r.json.path("data").elements().asScala.toVector

  Given("brand {string} whose catalogue the brand has configured") { (_: String) =>
    codes = Vector.fill(3)(Games.code()).sorted
    catalogue(codes.reverse.map(c => game(c))*)(codes)
  }
  When("a caller lists games") { () => last = kit.v1("/games?limit=100", Some(key.secret)) }
  Then("it receives the games available on the brand in game-code order, each with its catalogue details") { () =>
    assertEquals(rows(last).map(_.path("id").asText()), codes)
    rows(last).foreach(n => Vector("name", "category", "developerId", "developerName", "tags", "rtp").foreach(f => assert(n.has(f), s"$f in $n")))
  }

  private def oneOf(hidden: String): Unit =
    val shown = Games.code(); target = Games.code()
    catalogue(game(shown), hidden)(Vector(shown))
  Given("a game the brand disabled") { () =>
    // game-provider applies the brand's switches: a game the brand disabled is not on its route at all.
    val shown = Games.code(); target = Games.code()
    catalogue(game(shown))(Vector(shown))
  }
  Given("a game the game provider withdrew") { () => target = Games.code(); val shown = Games.code(); catalogue(game(shown), game(target, removed = true))(Vector(shown)) }
  Given("a game released only next month") { () =>
    target = Games.code(); val shown = Games.code()
    catalogue(game(shown), game(target, releaseDate = Some(kit.now.plus(JDuration.ofDays(31)).toString.take(10))))(Vector(shown))
  }
  When("a caller lists games or reads it by code") { () =>
    answers = Vector(kit.v1("/games?limit=100", Some(key.secret)), kit.v1(s"/games/$target", Some(key.secret)))
  }
  Then("it is absent from the listing") { () => assert(!rows(answers(0)).exists(_.path("id").asText() == target)) }
  Then("reading it by code is told it is not found") { () => assertEquals((answers(1).status, answers(1).error), (404, "not_found")) }

  Given("an operator disables a game of brand {string}") { (_: String) =>
    target = Games.code(); val other = Games.code()
    catalogue(game(target), game(other))(Vector(target, other))
    kit.provider.set(game(other))
  }
  When("five minutes have passed") { () =>
    kit.moveClock(5.minutes + 1.second)
    kit.v1("/games", Some(key.secret)): Unit
  }
  Then("the game is absent from the listing") { () =>
    kit.eventually("the game gone")(Option.when(!listed.contains(target))(()))
  }

  Given("rounds across games with known stakes and wins") { () =>
    codes = Vector.fill(3)(Games.code())
    catalogue(codes.map(c => game(c))*)(codes)
    val p = FactSet.id()
    val (a, b, c) = (codes(0), codes(1), codes(2))
    val keys = Vector(1, 2, 3).map(h => Games.round(kit, a, p, ago(h), 100, 0)) ++ Vector(1, 2).map(h => Games.round(kit, b, p, ago(h * 24), 100, 50)) ++
      Vector(Games.round(kit, c, p, ago(24 * 9), 100, 0))
    Games.seen(kit, keys)
  }
  Given("the figures have been computed") { () => Games.compute(kit): Unit }
  When("a caller ranks games by rounds over 7 days") { () => last = kit.v1("/game-stats?period=7d&sort=rounds", Some(key.secret)) }
  Then("the ranking matches a hand count, in descending order") { () =>
    val mine = rows(last).filter(n => codes.contains(n.path("gameId").asText())).map(n => n.path("gameId").asText() -> n.path("rounds").asInt())
    assertEquals(mine, Vector(codes(0) -> 3, codes(1) -> 2))
  }
  Then("it says when the figures were computed") { () => assert(last.json.hasNonNull("computedAt"), last.text) }

  Given("rounds in {string} and in {string}") { (x: String, y: String) =>
    codes = Vector.fill(2)(Games.code()).sorted
    catalogue(codes.map(c => game(c))*)(codes)
    val p = FactSet.id()
    computed(Vector(Games.round(kit, codes(0), p, ago(1), 700, 0, x), Games.round(kit, codes(1), p, ago(1), 300, 0, y),
      Games.round(kit, codes(1), p, ago(2), 200, 0, x)))
  }
  When("a caller ranks games by stake without a currency") { () => last = kit.v1("/game-stats?period=1d&sort=stake", Some(key.secret)) }
  Then("it is refused as an invalid request") { () => assertEquals((last.status, last.error), (400, "invalid_request")) }
  Then("ranking by stake in {string} ranks figures in {string} only") { (ccy: String, _: String) =>
    val r = rows(kit.v1(s"/game-stats?period=1d&sort=stake&currency=$ccy", Some(key.secret))).filter(n => codes.contains(n.path("gameId").asText()))
    assertEquals(r.map(_.path("gameId").asText()), Vector(codes(1)))
    r.foreach(n => assertEquals(n.path("amounts").elements().asScala.map(_.path("currency").asText()).toVector, Vector(ccy)))
  }

  Given("a game with rounds in the last 30 days and none in the last day") { () =>
    target = Games.code(); catalogue(game(target))(Vector(target))
    player = FactSet.id()
    computed(Vector(Games.round(kit, target, player, ago(48), 1250, 250), Games.round(kit, target, player, ago(24 * 20), 750, 0)))
  }
  When("a caller reads that game's figures") { () => last = kit.v1(s"/games/$target/stats", Some(key.secret)) }
  Then("the 1-day figures are zero") { () =>
    val d = last.json.path("windows").path("1d")
    assertEquals((d.path("rounds").asInt(), d.path("sessions").asInt(), d.path("uniquePlayers").asInt(), d.path("amounts").size()), (0, 0, 0, 0))
  }
  Then("the 30-day figures match a hand count") { () =>
    val w = last.json.path("windows").path("30d")
    assertEquals((w.path("rounds").asInt(), w.path("uniquePlayers").asInt()), (2, 1))
    val a = w.path("amounts").path(0)
    assertEquals((a.path("currency").asText(), a.path("stake").asText(), a.path("win").asText(), a.path("ggr").asText()), ("EUR", "20.00", "2.50", "17.50"))
  }

  private var keys: Vector[String] = Vector.empty
  Given("a cancelled round and a free-spins round of a game") { () =>
    target = Games.code(); catalogue(game(target))(Vector(target))
    val p = FactSet.id()
    keys = Vector(Games.round(kit, target, p, ago(1), 500, 0, outcome = "cancelled"), Games.round(kit, target, p, ago(1), 0, 300, free = true),
      Games.round(kit, target, p, ago(1), 100, 0))
  }
  When("the figures are computed") { () => computed(keys); last = kit.v1(s"/games/$target/stats", Some(key.secret)) }
  Then("the cancelled round counts in no figure") { () =>
    val d = last.json.path("windows").path("1d")
    assertEquals(d.path("rounds").asInt(), 2)
    assertEquals(d.path("amounts").path(0).path("stake").asText(), "1.00")
  }
  Then("the free-spins round counts as a round with no stake, win or GGR") { () =>
    val a = last.json.path("windows").path("1d").path("amounts").path(0)
    assertEquals((a.path("win").asText(), a.path("ggr").asText()), ("0.00", "1.00"))
  }

  Given("a brand whose figures have never been computed") { () =>
    // The second brand's kit: no run has ever been made there.
    val other = SharedKit.other
    val k     = Keys.noFields(other)
    other.provider.set(game("never-a"))
    answers = Vector(other.v1("/game-stats?period=7d&sort=rounds", Some(k.secret)))
  }
  When("a caller ranks its games") { () => last = answers.head }
  Then("the ranking is empty") { () => assertEquals((last.status, rows(last)), (200, Vector.empty)) }
  Then("it gives no computation time") { () => assert(!last.json.has("computedAt"), last.text) }
