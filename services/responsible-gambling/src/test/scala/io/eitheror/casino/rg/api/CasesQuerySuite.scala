package io.eitheror.casino.rg.api

/** The brand's cases' query (spec 030 G2): every status by default, page 1 of 50, each value in range. */
class CasesQuerySuite extends munit.FunSuite:
  private def query(q: (String, String)*) = OperatorEndpoint.casesQuery(q.toMap.get)

  test("none, or all, is every status; open and closed in any case"):
    assertEquals(query(), Right((None, 1, 50)))
    assertEquals(query("status" -> "ALL"), Right((None, 1, 50)))
    assertEquals(query("status" -> "OPEN"), Right((Some("open"), 1, 50)))
    assertEquals(query("status" -> "closed", "page" -> "2", "pageSize" -> "200"), Right((Some("closed"), 2, 200)))

  test("a value out of range, or one the list does not know, names its parameter"):
    assertEquals(query("status" -> "pending"), Left("status"))
    assertEquals(query("page" -> "0"), Left("page"))
    assertEquals(query("pageSize" -> "201"), Left("pageSize"))
    assertEquals(query("pageSize" -> "x"), Left("pageSize"))
