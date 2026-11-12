package io.eitheror.casino.publicapi.it

import io.eitheror.casino.publicapi.api.{Pipeline, Served, V1Endpoint}
import io.eitheror.casino.publicapi.contract.{ContractSchemas, Refusals}
import FakeGameProvider.game

import scala.collection.mutable
import scala.concurrent.duration.*

/**
 * T087, port of the original's `test/contract/responses.test.ts`: every route at every status the contract documents
 * for it, produced by the running service and each body validated against its schema; a status produced but not
 * documented, or documented but never produced, fails. It runs on the kit (Docker), so it lives with the kit suites.
 */
class ContractResponsesSuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit = SharedKit.kit
  private val Missing  = "9f2c9a52-6f43-4a56-8f0c-0d6d4a0f1a99"

  private final case class Probe(template: String, ok: String, bad: Option[String] = None, missing: Option[String] = None, gone: Option[String] = None)

  private lazy val code = Games.code()
  private lazy val probes: Vector[Probe] =
    val s  = Seeded.set
    val p1 = s.players.head
    def under(t: String, ok: String) = Probe(t, ok, Some(ok.replace(p1, "not-a-uuid")), Some(ok.replace(p1, Missing)))
    def one(t: String, base: String, id: String) = Probe(t, s"$base/$id", Some(s"$base/not-a-uuid"), Some(s"$base/$Missing"))
    Vector(
      Probe("/players", "/players", Some("/players?limit=0")),
      one("/players/{playerId}", "/players", p1),
      under("/players/{playerId}/sessions", s"/players/$p1/sessions"),
      under("/players/{playerId}/rounds", s"/players/$p1/rounds"),
      under("/players/{playerId}/bets", s"/players/$p1/bets"),
      under("/players/{playerId}/transactions", s"/players/$p1/transactions"),
      under("/players/{playerId}/bonus-awards", s"/players/$p1/bonus-awards"),
      one("/sessions/{sessionId}", "/sessions", s.sessions(0)),
      Probe("/sessions/{sessionId}/rounds", s"/sessions/${s.sessions(0)}/rounds", Some("/sessions/not-a-uuid/rounds"), Some(s"/sessions/$Missing/rounds")),
      one("/rounds/{roundId}", "/rounds", s.rounds(0)),
      Probe("/rounds/{roundId}/bets", s"/rounds/${s.rounds(0)}/bets", Some("/rounds/not-a-uuid/bets"), Some(s"/rounds/$Missing/bets")),
      one("/bets/{betId}", "/bets", s.bets(0)),
      one("/transactions/{transactionId}", "/transactions", s.transactions(0)),
      one("/bonus-awards/{bonusAwardId}", "/bonus-awards", s.bonuses(0)),
      Probe("/games", "/games", Some("/games?limit=0")),
      Probe("/games/{gameId}", s"/games/$code", Some("/games/bad%20code"), Some("/games/g-none")),
      Probe("/games/{gameId}/stats", s"/games/$code/stats", Some("/games/bad%20code/stats"), Some("/games/g-none/stats")),
      Probe("/game-stats", "/game-stats?period=30d&sort=rounds", Some("/game-stats?period=30d")),
      Probe("/player-stats", "/player-stats?period=30d&sort=rounds", Some("/player-stats?period=30d"),
        gone = Some(s"/player-stats?period=30d&sort=rounds&snapshot=$Missing")),
      under("/players/{playerId}/stats", s"/players/$p1/stats"),
      Probe("/contract", "/contract"),
      Probe("/openapi.json", "/openapi.json"))

  private val Public   = Set("/openapi.json")
  private val produced = mutable.Map.empty[String, Set[Int]].withDefaultValue(Set.empty)

  private def probe(template: String, path: String, expected: Int, key: Option[String], label: String, at: String = ""): Reply =
    val r = kit.v1(path, key, at = at)
    assertEquals(r.status, expected, s"$label: GET $path -> ${r.text.take(300)}")
    assert(ContractSchemas.statuses(template)(r.status), s"$label: GET $template produced undocumented ${r.status}")
    assertEquals(ContractSchemas.problems(template, r.status, r.json), Vector.empty, s"$label: GET $path ${r.text.take(300)}")
    produced(template) = produced(template) + r.status
    r

  private lazy val key = Keys.noFields(kit)

  override def beforeAll(): Unit =
    Seeded.set: Unit
    kit.provider.add(game(code))
    kit.moveClock(6.minutes)
    kit.v1("/games", Some(key.secret)): Unit
    kit.eventually("the catalogue reloaded")(Option.when(kit.v1(s"/games/$code", Some(key.secret)).status == 200)(()))
    Games.seen(kit, Vector(Games.round(kit, code, FactSet.id(), kit.now.minusSeconds(3600), 500, 100)))
    Games.compute(kit): Unit

  test("200: each route answers its schema, every collection holding rows") {
    probes.foreach { p =>
      val r = probe(p.template, p.ok, 200, Some(key.secret), "200")
      if r.json.has("data") && p.template != "/game-stats" then assert(r.json.path("data").size > 0, s"GET ${p.template}: an empty page")
    }
  }

  test("400: a malformed identifier or parameter") {
    probes.foreach(p => p.bad.foreach(probe(p.template, _, 400, Some(key.secret), "400")))
  }

  test("401: no key, and a key that is not one") {
    probes.filterNot(p => Public(p.template)).foreach { p =>
      probe(p.template, p.ok, 401, None, "401 no key")
      probe(p.template, p.ok, 401, Some("ak_garbage.value"), "401 bad key")
    }
  }

  test("403: a disabled key") {
    val off = Keys.disabled(kit)
    probes.filterNot(p => Public(p.template)).foreach(p => probe(p.template, p.ok, 403, Some(off.secret), "403"))
  }

  test("404: a record that does not exist") {
    probes.foreach(p => p.missing.foreach(probe(p.template, _, 404, Some(key.secret), "404")))
  }

  test("410: a snapshot no slot holds") {
    probes.foreach(p => p.gone.foreach(probe(p.template, _, 410, Some(key.secret), "410")))
  }

  test("429: the key's minute spent, with Retry-After") {
    probes.filterNot(p => Public(p.template)).foreach { p =>
      val k = Keys.create(kit, "one-a-minute", rate = Some(1))
      kit.v1(p.ok, Some(k.secret)): Unit
      val r = probe(p.template, p.ok, 429, Some(k.secret), "429")
      assert(r.header("Retry-After").exists(_.toInt > 0), s"Retry-After on ${p.template}")
    }
  }

  test("503: every read held past the request budget") {
    val peered = SharedKit.peered
    val held = peered.contract.operations.map(o => o.operationId -> ((_: Served) => { Thread.sleep(800); Refusals.json(200, "{}") })).toMap
    val (peer, at) = peered.peer(Seq(clients =>
      V1Endpoint(peered.contract, Pipeline(peered.settings.copy(budget = java.time.Duration.ofMillis(300)), peered.contract, clients, () => peered.now,
        held)(_, _), _ => Refusals.notFound.respond)))
    try
      val k = Keys.noFields(peered)
      probes.filter(p => ContractSchemas.statuses(p.template)(503)).foreach { p =>
        val r = peered.v1(p.ok, Some(k.secret), at = at)
        assertEquals(r.status, 503, s"GET ${p.ok}: ${r.text}")
        assertEquals(ContractSchemas.problems(p.template, 503, r.json), Vector.empty)
        produced(p.template) = produced(p.template) + 503
      }
    finally Kit.stopPeer(peer)
  }

  test("every documented status of every route was produced, and nothing else") {
    probes.foreach(p => assertEquals(produced(p.template).toVector.sorted, ContractSchemas.statuses(p.template).toVector.sorted, p.template))
  }

  test("resource schemas forbid additional properties") {
    Vector("Player", "Session", "Round", "Bet", "Transaction", "BonusAward", "Game", "GameStats", "GameStatsRow", "PlayerStats",
      "PlayerRankingEntry", "PlayerRankingPage", "PlayerAmounts", "PlayerWindow", "Snapshot", "CountAmount").foreach { n =>
      assertEquals(ContractSchemas.document.path("components").path("schemas").path(n).path("additionalProperties").asBoolean(true), false, n)
    }
  }
