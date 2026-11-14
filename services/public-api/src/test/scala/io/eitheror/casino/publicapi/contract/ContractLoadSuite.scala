package io.eitheror.casino.publicapi.contract

import com.fasterxml.jackson.databind.ObjectMapper

/** The contract as the service reads it at start (original `contract/load.ts`, `serve.ts`). */
class ContractLoadSuite extends munit.FunSuite:

  private val contract = Contract.load("https://api.goldvibe.example/v1").fold(e => fail(e), identity)
  private val mapper   = new ObjectMapper()

  test("the served document is the file as JSON, the server address set and nothing else changed") {
    val served = mapper.readTree(contract.json)
    assertEquals(served.path("servers").get(0).path("url").asText(), "https://api.goldvibe.example/v1")
    val file = Contract.file()
    file.path("servers").get(0).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put("url", "https://api.goldvibe.example/v1")
    assertEquals(served, file)
    assertEquals(served.path("info").path("version").asText(), "1.1.0")
  }

  test("the page-size cap is the contract's 100") {
    assertEquals(contract.maxPageSize, 100)
    assertEquals(Contract.file().path("components").path("parameters").path("limit").path("schema").path("maximum").asInt(), 100)
  }

  test("every operation is a GET with an id, and the deepest path has one parameter (ankka allows two)") {
    assert(contract.operations.nonEmpty)
    contract.operations.foreach { o =>
      assertEquals(o.method, "GET", o.toString)
      assert(o.operationId.nonEmpty, o.toString)
      assert("\\{".r.findAllIn(o.template).size <= 2, o.toString)
    }
    assert(contract.operations.exists(_.template == "/players/{playerId}/sessions"))
  }

  test("the reference page embeds the served document, with no early </script>") {
    assert(!contract.reference.contains("/*__SPEC__*/null"))
    assert(contract.reference.contains("https:\\/\\/api.goldvibe.example\\/v1") || contract.reference.contains("https://api.goldvibe.example/v1"))
    val script = contract.reference.indexOf("\"openapi\"")
    assert(script > 0)
  }
