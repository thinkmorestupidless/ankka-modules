package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}

import java.nio.charset.StandardCharsets.UTF_8

class WireSuite extends munit.FunSuite:
  private def status(code: ErrorCode, message: String = "x") = Wire.refused(CommandError(message, code)).status
  private def body(code: ErrorCode, message: String) = String(Wire.refused(CommandError(message, code)).body.body, UTF_8)

  test("a refusal keeps its status and code; a fault of the service's is 5xx, never the caller's 400") {
    assertEquals(Seq(ErrorCode.Conflict, ErrorCode.NotFound, ErrorCode.Forbidden, ErrorCode.BadRequest, ErrorCode.Unauthorized).map(status(_)),
      Seq(409, 404, 403, 400, 401))
    assertEquals(Seq(ErrorCode.Timeout, ErrorCode.Unavailable, ErrorCode.Internal).map(status(_)), Seq(503, 503, 500))
    assert(body(ErrorCode.Conflict, "email_already_registered: taken").contains(""""error":"email_already_registered""""))
  }

  test("O7's page and size (spec 030 L): 1 and 100 unless asked, a size from 1 to 200, anything else named") {
    def paging(q: (String, String)*) = Backoffice.paging(q.toMap.get)
    assertEquals(paging(), Right((1, 100)))
    assertEquals(paging("page" -> "3", "pageSize" -> "200"), Right((3, 200)))
    assertEquals(paging("pageSize" -> "201"), Left("pageSize"))
    assertEquals(paging("pageSize" -> "0"), Left("pageSize"))
    assertEquals(paging("page" -> "0"), Left("page"))
    assertEquals(paging("page" -> "two"), Left("page"))
  }
