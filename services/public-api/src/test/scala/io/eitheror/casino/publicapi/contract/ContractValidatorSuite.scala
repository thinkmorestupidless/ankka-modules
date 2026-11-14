package io.eitheror.casino.publicapi.contract

import com.fasterxml.jackson.databind.ObjectMapper

/** T087, port of the original's `test/contract/validator.test.ts`: the validator itself refuses what it must. */
class ContractValidatorSuite extends munit.FunSuite:
  private val mapper = ObjectMapper()
  private val Id     = "3f2c9a52-6f43-4a56-8f0c-0d6d4a0f1a11"
  private def j(s: String) = mapper.readTree(s)
  private def ok(t: String, st: Int, s: String) = assertEquals(ContractSchemas.problems(t, st, j(s)), Vector.empty, s)
  private def bad(t: String, st: Int, s: String) = assert(ContractSchemas.problems(t, st, j(s)).nonEmpty, s"accepted: $s")

  test("every component schema compiles") {
    import scala.jdk.CollectionConverters.*
    ContractSchemas.document.path("components").path("schemas").fieldNames().asScala.foreach(n => ContractSchemas.schemaAt(s"#/components/schemas/$n"))
  }

  test("a conforming player page validates") {
    ok("/players", 200, s"""{"data":[{"id":"$Id","brandId":"spinvibe","status":"ACTIVE","kycStatus":null,"registeredAt":"2026-10-21T10:00:00Z"}],
      "page":{"limit":100,"offset":0,"total":1,"hasMore":false}}""")
  }

  test("an extra property on a resource is refused (additionalProperties: false)") {
    bad("/players/{playerId}", 200, s"""{"id":"$Id","brandId":"spinvibe","status":"ACTIVE","kycStatus":"VERIFIED","registeredAt":"2026-10-21T10:00:00Z","twinId":"x"}""")
  }

  test("a malformed amount, a bad time and an unmapped enum are refused") {
    val round = s""""id":"$Id","brandId":"b","playerId":"$Id","gameId":"g","startedAt":"2026-10-21T10:00:00Z","stakeTotal":"1.00","winTotal":"0.00","currency":"EUR""""
    ok("/rounds/{roundId}", 200, s"{$round}")
    bad("/rounds/{roundId}", 200, s"{${round.replace("\"1.00\"", "\"1.5\"")}}")
    bad("/rounds/{roundId}", 200, s"{${round.replace("2026-10-21T10:00:00Z", "yesterday")}}")
    bad("/rounds/{roundId}", 200, s"""{$round,"status":"weird"}""")
  }

  test("error bodies validate against the Error schema; an unknown code does not") {
    ok("/players/{playerId}", 404, """{"error":"not_found"}""")
    ok("/players", 429, """{"error":"quota_exceeded"}""")
    ok("/players", 401, """{"error":"key_invalid"}""")
    bad("/players", 400, """{"error":"nope"}""")
    bad("/players", 401, """{"error":"jwt_missing"}""")
  }
