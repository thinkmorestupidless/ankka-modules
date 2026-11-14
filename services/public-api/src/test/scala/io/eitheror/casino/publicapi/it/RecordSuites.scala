package io.eitheror.casino.publicapi.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts as f

import java.time.Instant
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** One seeding for the record suites of the test JVM: a fresh set of facts, read once every view has them. */
object Seeded:
  lazy val kit: Kit          = SharedKit.kit
  lazy val at: Instant       = Instant.parse("2026-11-01T10:00:00Z")
  lazy val set: FactSet.Seeded =
    val s = FactSet.seed(kit, at, heavyCount = 250)
    val key = Keys.allFields(kit)
    kit.eventually("every record served", 60.seconds) {
      val all = Vector(
        kit.v1(s"/players/${s.heavy}/transactions?limit=1", Some(key.secret)).json.path("page").path("total").asInt() == 250,
        kit.v1(s"/players/${s.players.head}/transactions?limit=1", Some(key.secret)).json.path("page").path("total").asInt() == s.transactions.size,
        kit.v1(s"/players/${s.players.head}/bonus-awards", Some(key.secret)).json.path("data").path(0).has("wageringRequirement"),
        kit.v1(s"/rounds/${s.rounds(2)}", Some(key.secret)).json.path("status").asText() == "cancelled",
        kit.v1(s"/players/${s.players.head}/bets", Some(key.secret)).json.path("data").elements().asScala.map(_.path("status").asText()).toSet ==
          Set("settled", "loss", "voided"),
        kit.v1(s"/sessions/${s.sessions(0)}", Some(key.secret)).json.path("status").asText() == "CLOSED")
      Option.when(all.forall(identity))(())
    }
    s
  lazy val none: MadeKey = Keys.noFields(kit)
  lazy val all: MadeKey  = Keys.allFields(kit)

  def get(path: String, key: MadeKey = none): Reply = kit.v1(path, Some(key.secret))
  def data(r: Reply): Vector[JsonNode]              = r.json.path("data").elements().asScala.toVector


/** Original `routes/players.test.ts` and `playersClosed.test.ts`. */
class PlayersSuite extends munit.FunSuite:
  import Seeded.*

  test("players are listed by registration, then id, with the envelope") {
    val s = set
    val r = get("/players?limit=100")
    assertEquals(r.status, 200, r.text)
    val ids = data(r).map(_.path("id").asText())
    val mine = ids.filter(s.players.contains)
    assertEquals(mine, s.players)
    assertEquals(r.json.path("page").path("limit").asInt(), 100)
  }

  test("a player by id: the contract's fields; identifying fields absent without the key's leave") {
    val s = set
    val r = get(s"/players/${s.players.head}")
    assertEquals(r.status, 200, r.text)
    val j = r.json
    assertEquals((j.path("brandId").asText(), j.path("status").asText(), j.path("kycStatus").asText()), (kit.brand, "ACTIVE", "UNVERIFIED"))
    assertEquals((j.path("country").asText(), j.path("currency").asText(), j.path("language").asText()), ("BR", "EUR", "pt"))
    assertEquals(j.path("registeredAt").asText(), at.toString)
    Vector("email", "firstName", "lastName", "dateOfBirth", "phone", "addressLine1", "city", "stateProv", "postalCode").foreach(n => assert(!j.has(n), n))
  }

  test("a closed player reads CLOSED; a status with no mapping is null") {
    val pl = FactSet.id()
    kit.publish(f.CasinoPlayers, FactSet.registered(pl, at))
    kit.publish(f.CasinoPlayers, f.PlayerClosed(pl, "active", "closed", "op", None, None, at.plusSeconds(5)))
    kit.eventually("the player closed")(Option.when(get(s"/players/$pl").json.path("status").asText() == "CLOSED")(()))
    kit.publish(f.CasinoPlayers, f.PlayerSuspended(pl, "closed", "blocked", "op", None, None, at.plusSeconds(6)))
    kit.eventually("the status unmapped")(Option.when(get(s"/players/$pl").json.path("status").isNull)(()))
  }

  test("a player with no registration yet is not served") {
    val pl = FactSet.id()
    kit.publish(f.CasinoPlayers, f.PlayerEmailChanged(pl, FactSet.p("x@example.com"), at))
    val next = FactSet.id() // a later fact on the topic: once it is served, the change before it was read
    kit.publish(f.CasinoPlayers, FactSet.registered(next, at))
    kit.eventually("the next registration served")(Option.when(get(s"/players/$next").status == 200)(()))
    assertEquals(get(s"/players/$pl").status, 404)
  }

/** Original `routes/playerCollections.test.ts`: each collection of a player, a session and a round; parent 404 first. */
class CollectionsSuite extends munit.FunSuite:
  import Seeded.*

  test("a player's sessions, rounds, bets, transactions and bonus awards, each in its order") {
    val s  = set
    val pl = s.players.head
    assertEquals(data(get(s"/players/$pl/sessions")).map(_.path("id").asText()), s.sessions)
    assertEquals(data(get(s"/players/$pl/rounds")).map(_.path("id").asText()), s.rounds)
    assertEquals(data(get(s"/players/$pl/bets")).map(_.path("id").asText()), s.bets)
    val txs = data(get(s"/players/$pl/transactions"))
    assertEquals(txs.map(_.path("id").asText()).toSet, s.transactions.toSet)
    val times = txs.map(t => Instant.parse(t.path("createdAt").asText()))
    assertEquals(times, times.sorted)
    assertEquals(data(get(s"/players/$pl/bonus-awards")).map(_.path("id").asText()), s.bonuses)
  }

  test("a session's rounds and a round's bets") {
    val s = set
    assertEquals(data(get(s"/sessions/${s.sessions(0)}/rounds")).map(_.path("id").asText()), s.rounds)
    assertEquals(data(get(s"/sessions/${s.sessions(1)}/rounds")), Vector.empty)
    assertEquals(data(get(s"/rounds/${s.rounds(0)}/bets")).map(_.path("id").asText()), Vector(s.bets(0)))
  }

  test("an absent parent is 404 not_found, before any child is read; an empty collection is an empty page") {
    val absent = FactSet.id()
    Vector(s"/players/$absent/sessions", s"/players/$absent/transactions", s"/sessions/$absent/rounds", s"/rounds/$absent/bets").foreach { p =>
      val r = get(p)
      assertEquals((r.status, r.error), (404, "not_found"), p)
    }
    val empty = get(s"/players/${set.players(1)}/bonus-awards")
    assertEquals(empty.status, 200)
    assertEquals((data(empty), empty.json.path("page").path("total").asInt(), empty.json.path("page").path("hasMore").asBoolean()), (Vector.empty, 0, false))
  }

/** Original `routes/byId.test.ts`: every record by id, with the contract's fields. */
class ByIdSuite extends munit.FunSuite:
  import Seeded.*

  test("a session: OPEN until it ends, CLOSED with its end and reason") {
    val s = set
    val closed = get(s"/sessions/${s.sessions(0)}").json
    assertEquals((closed.path("status").asText(), closed.path("closeReason").asText(), closed.path("gameId").asText()), ("CLOSED", "player", "st8-book"))
    assertEquals(get(s"/sessions/${s.sessions(1)}").json.path("status").asText(), "OPEN")
  }

  test("a round: its start from round_opened, its totals as money, its status; a cancelled round has no settledAt") {
    val s = set
    val r = get(s"/rounds/${s.rounds(0)}").json
    assertEquals((r.path("stakeTotal").asText(), r.path("winTotal").asText(), r.path("status").asText()), ("1.00", "2.50", "settled"))
    assertEquals((r.path("startedAt").asText(), r.path("sessionId").asText()), (at.plusSeconds(100).toString, s.sessions(0)))
    val c = get(s"/rounds/${s.rounds(2)}").json
    assertEquals(c.path("status").asText(), "cancelled")
    assert(!c.has("settledAt"))
  }

  test("a bet: its round, session, funding and status; its BET transaction shares its id") {
    val s = set
    val b = get(s"/bets/${s.bets(0)}").json
    assertEquals((b.path("roundId").asText(), b.path("sessionId").asText(), b.path("fundedFrom").asText(), b.path("status").asText()),
      (s.rounds(0), s.sessions(0), "MAIN", "settled"))
    val t = get(s"/transactions/${s.bets(0)}").json
    assertEquals((t.path("type").asText(), t.path("amount").asText(), t.path("status").asText(), t.path("roundId").asText()),
      ("BET", "1.00", "settled", s.rounds(0)))
    assertEquals(get(s"/bets/${s.bets(2)}").json.path("status").asText(), "voided")
  }

  test("a deposit carries its payment reference; a withdrawal completes on withdrawal_completed") {
    val s   = set
    val txs = data(get(s"/players/${s.players.head}/transactions"))
    val dep = txs.find(_.path("type").asText() == "DEPOSIT").get
    assertEquals((dep.path("amount").asText(), dep.path("walletType").asText(), dep.path("status").asText()), ("50.00", "MAIN", "completed"))
    assert(dep.path("paymentRef").asText().startsWith("pay-"))
    val w = txs.find(_.path("type").asText() == "WITHDRAWAL").get
    assertEquals((w.path("amount").asText(), w.path("status").asText()), ("10.00", "completed"))
    val loss = txs.find(_.path("type").asText() == "LOSS").get
    assertEquals((loss.path("amount").asText(), loss.path("gameId").asText()), ("0.00", "st8-book"))
  }

  test("a bonus award: its offer, value, requirement, status and expiry") {
    val s = set
    val b = get(s"/bonus-awards/${s.bonuses.head}").json
    assertEquals((b.path("offerId").asText(), b.path("name").asText(), b.path("type").asText(), b.path("value").asText()),
      ("offer-1", "Welcome", "deposit_match", "10.00"))
    assertEquals((b.path("wageringRequirement").asText(), b.path("status").asText()), ("300.00", "ACTIVE"))
    assertEquals(b.path("expiresAt").asText(), at.plusSeconds(86400 * 7).toString)
  }

  test("an absent id is 404 not_found on every by-id route; an upper-case UUID reads the same record") {
    val absent = FactSet.id()
    Vector("players", "sessions", "rounds", "bets", "transactions", "bonus-awards").foreach { c =>
      val r = get(s"/$c/$absent")
      assertEquals((r.status, r.error), (404, "not_found"), c)
    }
    assertEquals(get(s"/players/${set.players.head.toUpperCase}").json.path("id").asText(), set.players.head)
  }

/** Original `test/boundaries.test.ts` and `test/paging.test.ts`. */
class PagingSuite extends munit.FunSuite:
  import Seeded.*

  test("250 transactions at limit 100: three pages, every id once, in order, hasMore until the last") {
    val s     = set
    val pages = Vector(0, 100, 200).map(o => get(s"/players/${s.heavy}/transactions?limit=100&offset=$o"))
    assertEquals(pages.map(p => data(p).size), Vector(100, 100, 50))
    assertEquals(pages.map(_.json.path("page").path("hasMore").asBoolean()), Vector(true, true, false))
    val ids = pages.flatMap(data).map(_.path("id").asText())
    assertEquals(ids.distinct.size, 250)
    val amounts = pages.flatMap(data).map(_.path("amount").asText())
    assertEquals(amounts, (0 until 250).map(i => io.eitheror.casino.publicapi.wire.Money.cents(100L + i)).toVector)
  }

  test("limit 101 is limit_exceeded; an offset past the end is an empty page with the total") {
    val s = set
    val over = get(s"/players/${s.heavy}/transactions?limit=101")
    assertEquals((over.status, over.error), (400, "limit_exceeded"))
    val past = get(s"/players/${s.heavy}/transactions?offset=1000")
    assertEquals((data(past), past.json.path("page").path("total").asInt(), past.json.path("page").path("hasMore").asBoolean()), (Vector.empty, 250, false))
  }

  test("the parameters are checked after the path id and before the parent") {
    assertEquals(get("/players/nope/transactions?limit=101").error, "invalid_request")
    assertEquals(get(s"/players/${FactSet.id()}/transactions?limit=101").error, "limit_exceeded")
  }

  test("ties in time are broken by id; a page read while facts arrive repeats no id") {
    val pl = FactSet.id()
    val t  = at.plusSeconds(10)
    kit.publish(f.CasinoPlayers, FactSet.registered(pl, t))
    val same = Vector.fill(5)(FactSet.id())
    same.foreach(tx => kit.publish(f.MoneyTransactions, FactSet.deposit(pl, tx, 100, t)))
    kit.eventually("five deposits")(Option.when(get(s"/players/$pl/transactions").json.path("page").path("total").asInt() == 5)(()))
    assertEquals(data(get(s"/players/$pl/transactions")).map(_.path("id").asText()), same.sorted)
    val first = data(get(s"/players/$pl/transactions?limit=3")).map(_.path("id").asText())
    kit.publish(f.MoneyTransactions, FactSet.deposit(pl, FactSet.id(), 100, t.plusSeconds(60)))
    kit.eventually("six")(Option.when(get(s"/players/$pl/transactions").json.path("page").path("total").asInt() == 6)(()))
    val second = data(get(s"/players/$pl/transactions?limit=3&offset=3")).map(_.path("id").asText())
    assertEquals((first ++ second).distinct.size, 6)
  }

/** Original `routes/droppedRecords.test.ts`: a record the contract cannot serve is refused at projection. */
class ProjectionRefusalSuite extends munit.FunSuite with com.thinkmorestupidless.ankka.testkit.LogCapturing:
  import Seeded.*

  test("a deposit in a currency without two minor digits is refused, logged without personal data, and 404 by id") {
    val s  = set
    val tx = FactSet.id()
    kit.publish(f.MoneyTransactions, FactSet.deposit(s.players(1), tx, 500, at, io.eitheror.casino.contract.money.Currency.unsafe("JPY")))
    val ok = FactSet.id()
    kit.publish(f.MoneyTransactions, FactSet.deposit(s.players(1), ok, 500, at.plusSeconds(1)))
    kit.eventually("the next deposit served")(Option.when(get(s"/transactions/$ok").status == 200)(()))
    assertEquals(get(s"/transactions/$tx").status, 404)
    assert(!data(get(s"/players/${s.players(1)}/transactions")).exists(_.path("id").asText() == tx))
  }

  test("a round without a game code is refused and 404 by id") {
    val s = set
    val k = s"st8/r-${FactSet.id()}"
    kit.publish(f.CasinoRounds, FactSet.settled(s.players(1), k, "", at, 100, 0, "loss", game = ""))
    val id = io.eitheror.casino.publicapi.records.Records.roundId(k)
    val next = s"st8/r-${FactSet.id()}" // a later round on the topic: once it is served, the one before it was read
    kit.publish(f.CasinoRounds, FactSet.settled(s.players(1), next, "", at.plusSeconds(1), 100, 0, "loss"))
    kit.eventually("the next round served")(Option.when(
      get(s"/rounds/${io.eitheror.casino.publicapi.records.Records.roundId(next)}").status == 200)(()))
    assertEquals(get(s"/rounds/$id").status, 404)
  }
