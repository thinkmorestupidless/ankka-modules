package io.eitheror.casino.publicapi.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.publicapi.api.V1Endpoint

import scala.jdk.CollectionConverters.*

/**
 * T085, port of the original's `test/contract/snapshot.test.ts`: the served document is the committed file, the
 * endpoint answers exactly its 22 operations, and the page-size cap is the file's.
 */
class ContractSnapshotSuite extends munit.FunSuite:
  private val url      = "https://api.goldvibe.example/v1"
  private val contract = Contract.load(url).fold(e => fail(e), identity)

  val Expected: Vector[String] = Vector("/players", "/players/{playerId}", "/players/{playerId}/sessions", "/players/{playerId}/rounds",
    "/players/{playerId}/bets", "/players/{playerId}/transactions", "/players/{playerId}/bonus-awards", "/sessions/{sessionId}",
    "/sessions/{sessionId}/rounds", "/rounds/{roundId}", "/rounds/{roundId}/bets", "/bets/{betId}", "/transactions/{transactionId}",
    "/bonus-awards/{bonusAwardId}", "/games", "/games/{gameId}", "/games/{gameId}/stats", "/game-stats", "/player-stats",
    "/players/{playerId}/stats", "/contract", "/openapi.json")

  test("the served document deep-equals the committed YAML, with only the server address set") {
    val file = Contract.file().deepCopy[ObjectNode]()
    file.path("servers").get(0).asInstanceOf[ObjectNode].put("url", url)
    assertEquals(ObjectMapper().readTree(contract.json), file)
  }

  test("the contract declares exactly the 22 operations") {
    assertEquals(Contract.file().path("paths").fieldNames().asScala.toVector.sorted, Expected.sorted)
    assertEquals(contract.operations.map(_.template).sorted, Expected.sorted)
  }

  test("the /v1 endpoint answers GET on each of them, and on /docs; no other GET route is a contract path") {
    val endpoint = V1Endpoint(contract, (_, _) => Refusals.notFound.respond, _ => Refusals.notFound.respond)
    val gets = com.thinkmorestupidless.ankka.http.RoutesOf(endpoint).filter(_.startsWith("GET ")).map(_.stripPrefix("GET ")).toSet
    Expected.foreach(t => assert(gets(t), s"no GET $t"))
    assert(gets("/docs"), gets.toString)
    val catchAll = gets -- Expected - "/docs"
    assert(catchAll.forall(_.split('/').filter(_.nonEmpty).forall(s => s.startsWith("{") || Expected.exists(_.startsWith(s"/$s")))), catchAll.toString)
  }

  test("the limit parameter's maximum is the paging cap") {
    assertEquals(Contract.file().path("components").path("parameters").path("limit").path("schema").path("maximum").asInt(), contract.maxPageSize)
  }
