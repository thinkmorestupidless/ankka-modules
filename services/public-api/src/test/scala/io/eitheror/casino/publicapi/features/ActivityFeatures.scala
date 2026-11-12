package io.eitheror.casino.publicapi.features

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.publicapi.it.{FactSet, Reply, Seeded}

import java.time.Instant

/** features/public-api/activity.feature (T045). */
class ActivityFeatures extends PublicApiFeatures("activity.feature"):
  private def key                     = Some(Seeded.none.secret)
  private def data(r: Reply)          = Seeded.data(r)
  private var player: String          = ""
  private var pages: Vector[Reply]    = Vector.empty
  private var served: Vector[String]  = Vector.empty
  private var record: JsonNode        = null
  private val t0                      = Instant.parse("2026-10-01T10:00:00Z")

  private def registered(): String =
    val pl = FactSet.id(); kit.publish(f.CasinoPlayers, FactSet.registered(pl, t0)); pl
  private def total(path: String): Int = kit.v1(path, key).json.path("page").path("total").asInt(-1)

  Given("a player of brand {string} with {int} transactions") { (brand: String, n: Int) =>
    assertEquals(brand, kit.brand)
    player = registered()
    (0 until n).foreach(i => kit.publish(f.MoneyTransactions, FactSet.deposit(player, FactSet.id(), 100L + i, t0.plusSeconds(i))))
    kit.eventually(s"$n transactions")(Option.when(total(s"/players/$player/transactions?limit=1") == n)(()))
  }
  When("a caller pages through their transactions {int} at a time") { (n: Int) =>
    pages = Iterator.iterate(0)(_ + n).map(o => kit.v1(s"/players/$player/transactions?limit=$n&offset=$o", key))
      .takeWhile(p => data(p).nonEmpty).toVector
  }
  Then("it receives three pages") { () => assertEquals(pages.size, 3) }
  Then("every transaction exactly once, oldest first") { () =>
    val rows = pages.flatMap(data)
    assertEquals(rows.map(_.path("id").asText()).distinct.size, rows.size)
    val times = rows.map(r => Instant.parse(r.path("createdAt").asText()))
    assertEquals(times, times.sorted)
  }

  Given("a caller part way through paging a player's rounds") { () =>
    player = registered()
    (0 until 6).foreach(i => kit.publish(f.CasinoRounds, FactSet.opened(player, s"st8/${FactSet.id()}", "", t0.plusSeconds(i))))
    kit.eventually("six rounds")(Option.when(total(s"/players/$player/rounds") == 6)(()))
    served = data(kit.v1(s"/players/$player/rounds?limit=3", key)).map(_.path("id").asText())
  }
  When("new rounds are played") { () =>
    (0 until 2).foreach(i => kit.publish(f.CasinoRounds, FactSet.opened(player, s"st8/${FactSet.id()}", "", t0.plusSeconds(100 + i))))
    kit.eventually("eight rounds")(Option.when(total(s"/players/$player/rounds") == 8)(()))
  }
  When("it asks for the next page") { () => last = kit.v1(s"/players/$player/rounds?limit=3&offset=3", key) }
  Then("no round already served is served again") { () => assert(data(last).map(_.path("id").asText()).intersect(served).isEmpty) }
  Then("no older round is skipped") { () =>
    val all = data(kit.v1(s"/players/$player/rounds", key)).map(_.path("id").asText())
    assertEquals(served ++ data(last).map(_.path("id").asText()), all.take(6))
  }

  Given("a caller holding an access key of brand {string}") { (brand: String) => assertEquals(brand, kit.brand) }
  When("it asks for a page of {int} records") { (n: Int) => last = kit.v1(s"/players?limit=$n", key) }
  Then("it is refused, and told the maximum is {int}") { (n: Int) =>
    assertEquals((last.status, last.error), (400, "limit_exceeded"))
    assert(last.json.path("message").asText().contains(n.toString))
  }

  Given("a player with {int} sessions") { (n: Int) =>
    player = registered()
    (0 until n).foreach(i => kit.publish(f.CasinoSessions, FactSet.sessionStarted(player, FactSet.id(), t0.plusSeconds(i))))
    kit.eventually(s"$n sessions")(Option.when(total(s"/players/$player/sessions") == n)(()))
  }
  When("a caller asks for the page starting at the {int}th") { (n: Int) => last = kit.v1(s"/players/$player/sessions?offset=$n", key) }
  Then("the page is empty") { () => assertEquals(data(last), Vector.empty) }
  Then("it gives a total of {int}") { (n: Int) => assertEquals(last.json.path("page").path("total").asInt(), n) }

  Given("a player with no bonus awards") { () =>
    player = registered(); kit.eventually("the player")(Option.when(kit.v1(s"/players/$player", key).status == 200)(()))
  }
  When("a caller lists their bonus awards") { () => last = kit.v1(s"/players/$player/bonus-awards", key) }
  Then("it receives an empty page") { () =>
    assertEquals((last.status, data(last), last.json.path("page").path("total").asInt()), (200, Vector.empty, 0))
  }

  Given("a bet in a round of a session of a player") { () => val _ = Seeded.set }
  When("a caller reads the bet by id") { () => record = kit.v1(s"/bets/${Seeded.set.bets(0)}", key).json }
  Then("it carries the ids of the player, the session and the round") { () =>
    val s = Seeded.set
    assertEquals((record.path("playerId").asText(), record.path("sessionId").asText(), record.path("roundId").asText()),
      (s.players.head, s.sessions(0), s.rounds(0)))
  }

  Given("a transaction of {int} minor units of {string}") { (n: Int, ccy: String) =>
    player = registered()
    val tx = FactSet.id()
    kit.publish(f.MoneyTransactions, FactSet.deposit(player, tx, n.toLong, t0, io.eitheror.casino.contract.money.Currency.unsafe(ccy)))
    served = Vector(tx)
  }
  When("a caller reads it") { () =>
    if served.nonEmpty then
      record = kit.eventually("the transaction")(Option(kit.v1(s"/transactions/${served.head}", key)).filter(_.status == 200)).json
    else record = kit.eventually("the player")(Option(kit.v1(s"/players/$player", key)).filter(_.status == 200)).json
  }
  Then("its amount is {string} with currency {string}") { (amount: String, ccy: String) =>
    assertEquals((record.path("amount").asText(), record.path("currency").asText()), (amount, ccy))
  }

  Given("a player whose status the public contract does not name") { () =>
    player = registered(); served = Vector.empty
    kit.publish(f.CasinoPlayers, f.PlayerSuspended(player, "active", "blocked", "op", None, None, t0.plusSeconds(1)))
    kit.eventually("the unnamed status")(Option.when(kit.v1(s"/players/$player", key).json.path("status").isNull)(()))
  }
  When("a caller reads them") { () => record = kit.v1(s"/players/$player", key).json }
  Then("their status is null") { () => assert(record.path("status").isNull, record.toString) }

  Given("a closed player and a self-excluded player") { () =>
    val closed = registered(); val excluded = registered()
    kit.publish(f.CasinoPlayers, f.PlayerClosed(closed, "active", "closed", "op", None, None, t0.plusSeconds(1)))
    kit.publish(f.CasinoPlayers, f.PlayerSelfExcluded(excluded, "active", "self_excluded", "player", None, None, t0, None, t0.plusSeconds(1)))
    served = Vector(closed, excluded)
    kit.eventually("both statuses")(Option.when(
      kit.v1(s"/players/$closed", key).json.path("status").asText() == "CLOSED" &&
        kit.v1(s"/players/$excluded", key).json.path("status").asText() == "SELF_EXCLUDED")(()))
  }
  When("a caller lists players") { () =>
    pages = Iterator.iterate(0)(_ + 100).map(o => kit.v1(s"/players?offset=$o", key)).takeWhile(p => data(p).nonEmpty).toVector
  }
  Then("both are listed, each with their status") { () =>
    val rows = pages.flatMap(data).map(r => r.path("id").asText() -> r.path("status").asText()).toMap
    assertEquals((rows.get(served(0)), rows.get(served(1))), (Some("CLOSED"), Some("SELF_EXCLUDED")))
  }
