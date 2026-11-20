package io.eitheror.casino.players.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.it.Reply

import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** features/players/games-played.feature (spec 030 P9, T105): the games from `casino.rounds`' settlements. */
class GamesPlayedFeatures extends PlayersFeatures("games-played.feature"):
  protected def pending = Map.empty

  private val brl = Currency.unsafe("BRL")
  private var playerId = ""
  private var played = Map.empty[String, Int]
  private var reply: Reply = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    playerId = ""; played = Map.empty; reply = null

  /** One settled round of `game`: a stake of 100 and, every third round, a win of 250, in BRL. */
  private def settle(game: String, n: Int): Unit =
    val p   = UUID.randomUUID().toString.take(12)
    val win = if n % 3 == 0 then 250L else 0L
    kit.publish(CasinoRounds, s"$playerId/BRL", RoundSettled(playerId, brl, s"st8/$p", "st8", p, game, s"gs-$game", MinorUnits.unsafe(100),
      MinorUnits.unsafe(win), SignedMinorUnits(win - 100), if win > 0 then "win" else "loss", Vector(s"tx-$p"), Vector(s"ptx-$p"),
      Vector(s"wtx-$p"), None, kit.now()))

  private def games: Vector[JsonNode] = reply.json.get("games").elements.asScala.toVector

  Given("a player who played {string} rounds of one game and {string} of another") { (a: String, b: String) =>
    playerId = kit.activePlayer()._2
    played = Map("vs20olympgate" -> a.toInt, "crazy-time" -> b.toInt)
    played.foreach((game, n) => (1 to n).foreach(i => settle(game, i)))
  }

  When("an operator reads what the player plays") { () =>
    val total = played.values.sum
    reply = kit.eventually("every round counted", 30.seconds) {
      val r = kit.request("GET", kit.operatorPath(s"/players/$playerId/games"), None, kit.asOperator())
      Option.when(r.status == 200 && r.json.get("games").elements.asScala.map(_.get("rounds").asInt).sum == total)(r)
    }
  }

  Then("both games are listed, the one with more rounds first") { () =>
    assertEquals(games.map(g => (g.textOr("gameId", ""), g.get("rounds").asInt)), Vector("vs20olympgate" -> 12, "crazy-time" -> 3))
    assertEquals(reply.json.get("truncated").asBoolean, false)
  }

  Then("each carries its rounds, the total staked and the total won") { () =>
    games.foreach { g =>
      val n = played(g.textOr("gameId", ""))
      assertEquals((g.textOr("currency", ""), g.get("stakedMinorUnits").asLong, g.get("wonMinorUnits").asLong, g.get("netMinorUnits").asLong),
        ("BRL", 100L * n, 250L * (n / 3), 250L * (n / 3) - 100L * n), g.toString)
    }
  }
