package io.eitheror.casino.contract.port

import io.eitheror.casino.contract.port.DescriptionCheck.{Difference, OriginalRoute}

import java.nio.file.Path

/** The description check over a small description and one recording of every case (spec 031 T033). */
class DescriptionCheckSuite extends munit.FunSuite:

  private def resource(p: String) = Path.of(getClass.getResource(s"/description-check/$p").toURI)

  private val description = resource("openapi.player.json")
  private val recordings  = Vector(resource("recordings/TestBalance.json"))
  private val balance     = "get /v1/casino/players/{brand}/wallets/{currency}/balance"
  private val routes      = Vector(OriginalRoute("GET", "/v1/casino/players/[^/]+/wallets/[^/]+/balance".r, Some("wallet"), balance))

  private val result = DescriptionCheck.run(description, recordings, routes, (_, step) => Option.when(step.name != "skipped")(step))

  test("an allowed answer, a named refusal and a platform status are no difference; an unnamed status and a refused field are") {
    assertEquals(result.differences.map(_.step), Vector("unnamed status", "field outside the schema"))
    assertEquals(result.differences.head, Difference("TestBalance.json", "unnamed status", balance, "status 409 is not named (200, 404)"))
    assert(result.differences(1).what.startsWith("status 200: "), result.differences(1).what)
    assert(result.differences(1).what.contains("realBalance"), result.differences(1).what)
  }

  test("a step the reading skips, and a step under another host, are not matched") {
    assertEquals(result.matched, 5)
  }

  test("an operation no step matches is unrecorded, by method and path") {
    assertEquals(result.unrecorded, Vector("GET /v1/casino/players/{brand}/wallets/{currency}/transactions"))
  }

  test("a route table naming an operation the description lacks is a difference") {
    val r = DescriptionCheck.run(description, recordings, routes :+ OriginalRoute("GET", "/nowhere".r, None, "get /nowhere"), (_, s) => Some(s))
    assert(r.differences.exists(d => d.operationId == "get /nowhere" && d.what.contains("does not have")), r.differences.toString)
  }
