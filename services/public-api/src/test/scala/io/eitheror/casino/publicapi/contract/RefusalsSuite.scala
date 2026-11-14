package io.eitheror.casino.publicapi.contract

import com.fasterxml.jackson.databind.ObjectMapper

import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

class RefusalsSuite extends munit.FunSuite:
  private val mapper = new ObjectMapper()

  private def parts(r: Refusal) =
    val resp = r.respond
    (resp.status, resp.body.contentType, mapper.readTree(String(resp.body.body, UTF_8)), resp.headers)

  test("the codes are exactly the served contract's Error enum") {
    val enumNode = Contract.file().path("components").path("schemas").path("Error").path("properties").path("error").path("enum")
    assertEquals(enumNode.elements().asScala.map(_.asText()).toVector, Refusals.Codes)
  }

  test("each refusal has the error table's status, as JSON") {
    val table = Vector(
      Refusals.invalidRequest("x") -> 400, Refusals.limitExceeded(100) -> 400, Refusals.keyMissing -> 401, Refusals.keyInvalid -> 401,
      Refusals.keyExpired -> 401, Refusals.keyDisabled -> 403, Refusals.notFound -> 404, Refusals.snapshotExpired -> 410,
      Refusals.quotaExceeded(3) -> 429, Refusals.unavailable -> 503)
    table.foreach { (r, status) =>
      val (s, ct, body, _) = parts(r)
      assertEquals(s, status, r.code)
      assertEquals(ct, "application/json")
      assertEquals(body.path("error").asText(), r.code)
    }
    assertEquals(table.map(_._1.code).distinct.toSet, Refusals.Codes.toSet)
  }

  test("a message is sent only where the original sets one, and never when it equals the code") {
    assert(!parts(Refusals.keyMissing)._3.has("message"))
    assertEquals(parts(Refusals.onlyGet)._3.path("message").asText(), "Only GET is supported")
    assertEquals(parts(Refusals.onlyGet)._1, 405)
    assert(!parts(Refusal(400, "invalid_request", Some("invalid_request")))._3.has("message"))
    assertEquals(parts(Refusals.notAnIdentifier("playerId"))._3.path("message").asText(), "playerId is not a valid identifier")
  }

  test("quota_exceeded carries Retry-After in whole seconds, at least one") {
    assertEquals(parts(Refusals.quotaExceeded(7))._4, Vector("Retry-After" -> "7"))
    assertEquals(parts(Refusals.quotaExceeded(0))._4, Vector("Retry-After" -> "1"))
  }
