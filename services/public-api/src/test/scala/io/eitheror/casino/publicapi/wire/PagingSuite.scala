package io.eitheror.casino.publicapi.wire

import io.eitheror.casino.publicapi.api.Call

/** Original `test/paging.test.ts`: the limits, the defaults and the envelope. */
class PagingSuite extends munit.FunSuite:
  private def call(q: (String, String)*) = Call(None, Vector.empty, q.toVector, None, "/v1/players")
  private def code(q: (String, String)*) = Paging.parse(call(q*), 100).left.map(r => (r.status, r.code, r.message))

  test("the defaults are the cap and offset 0") {
    assertEquals(Paging.parse(call(), 100), Right(Page(100, 0)))
  }
  test("limit 1 to 100 is taken; 101 is limit_exceeded; 0 is invalid_request") {
    assertEquals(Paging.parse(call("limit" -> "1"), 100), Right(Page(1, 0)))
    assertEquals(Paging.parse(call("limit" -> "100", "offset" -> "250"), 100), Right(Page(100, 250)))
    assertEquals(code("limit" -> "101"), Left((400, "limit_exceeded", Some("limit may not exceed 100"))))
    assertEquals(code("limit" -> "0"), Left((400, "invalid_request", Some("limit must be at least 1"))))
  }
  test("a limit or offset that is not a whole number is invalid_request") {
    Vector("-1", "1.5", "abc", "", " 1").foreach { v =>
      assertEquals(code("limit" -> v).left.map(_._2), Left("invalid_request"), v)
      assertEquals(code("offset" -> v).left.map(_._2), Left("invalid_request"), v)
    }
    assertEquals(code("offset" -> "99999999999999999999"), Left((400, "invalid_request", Some("offset is out of range"))))
  }
  test("hasMore is offset + limit < total") {
    assert(Paging.envelope(Nil, Page(100, 0), 250).endsWith(""""total":250,"hasMore":true}}"""))
    assert(Paging.envelope(Nil, Page(100, 200), 250).endsWith(""""hasMore":false}}"""))
    assert(Paging.envelope(Nil, Page(100, 300), 250).startsWith("""{"data":[],"""))
  }
