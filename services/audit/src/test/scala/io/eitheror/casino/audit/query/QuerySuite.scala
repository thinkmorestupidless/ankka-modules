package io.eitheror.casino.audit.query

import java.time.Instant

class QuerySuite extends munit.FunSuite:

  private val at = Instant.parse("2026-11-09T10:00:00Z")

  test("a query is recorded unless it asks after the audit's own records alone") {
    import io.eitheror.casino.audit.api.RecordsRoute
    assert(RecordsRoute.recorded(Filters()))
    assert(RecordsRoute.recorded(Filters(service = Some("wallet"))))
    assert(!RecordsRoute.recorded(Filters(service = Some("audit"))))
    assert(!RecordsRoute.recorded(Filters(brands = Some(Set("goldvibe")), service = Some("audit"))))
  }

  test("every filter of contracts/routes.md is read") {
    val asked = Query.parse(Map(
      "brand" -> "goldvibe", "subject" -> "ps-1", "actor" -> "ps-2", "service" -> "wallet", "action" -> "wallet.adjust",
      "outcome" -> "done", "reasonCode" -> "manual", "category" -> "money", "visibility" -> "brand", "amountMin" -> "100",
      "amountMax" -> "5000", "currency" -> "EUR", "correlation" -> "c-1", "from" -> "2026-01-01T00:00:00Z",
      "to" -> "2026-04-01T00:00:00Z", "limit" -> "50"
    ), pageLimit = 1000).toOption.get
    assertEquals((asked.brand, asked.correlation, asked.limit, asked.after), (Some("goldvibe"), Some("c-1"), 50, None))
    assertEquals(asked.filters, Filters(subject = Some("ps-1"), actor = Some("ps-2"), service = Some("wallet"),
      action = Some("wallet.adjust"), outcome = Some("done"), reasonCode = Some("manual"), category = Some("money"),
      visibility = Some("brand"), amountMin = Some(100), amountMax = Some(5000), currency = Some("EUR"),
      from = Some(Instant.parse("2026-01-01T00:00:00Z")), to = Some(Instant.parse("2026-04-01T00:00:00Z"))))
  }

  test("a page of 100 by default, at most the installation's limit") {
    assertEquals(Query.parse(Map.empty, 1000).map(_.limit), Right(100))
    assert(Query.parse(Map("limit" -> "1001"), 1000).left.exists(_.startsWith("bad_filter: limit")))
    assert(Query.parse(Map("limit" -> "0"), 1000).left.exists(_.startsWith("bad_filter: limit")))
  }

  test("an unreadable or unknown filter is bad_filter, naming it; a cursor not given here is bad_cursor") {
    Vector(Map("amountMin" -> "ten"), Map("from" -> "monday"), Map("visibility" -> "public"), Map("colour" -> "red"),
      Map("amountMin" -> "10", "amountMax" -> "5"), Map("from" -> "2026-02-01T00:00:00Z", "to" -> "2026-01-01T00:00:00Z"))
      .foreach(p => assert(Query.parse(p, 1000).left.exists(_.startsWith("bad_filter:")), p))
    assert(Query.parse(Map("after" -> "not-a-cursor"), 1000).left.exists(_.startsWith("bad_cursor:")))
  }

  test("the cursor round-trips and is opaque") {
    val c = Cursor(at, "brand:goldvibe/d-0123")
    assertEquals(Cursor.decode(c.encode), Some(c))
    assert(!c.encode.contains("goldvibe"))
    assertEquals(Cursor.decode(java.util.Base64.getUrlEncoder.encodeToString("x|".getBytes)), None)
  }

  test("the condition: each filter a parameter, brands as a set, amounts as numbers, the keyset after the cursor") {
    val sql = Query.condition(Filters(brands = Some(Set("spinvibe", "goldvibe")), service = Some("wallet"), amountMin = Some(100),
      to = Some(at)), Some(Cursor(at, "k")))
    val text = sql.render
    assert(text.contains("payload::jsonb->>'brand'") && text.contains(" IN ("), text)
    assert(text.contains("(payload::jsonb->>'amount')::bigint >= "), text)
    assert(text.contains("(payload::jsonb->>'occurredAt')::timestamptz < "), text)
    assert(text.contains(" OR ("), text)
    assert(!text.contains("goldvibe") && !text.contains("wallet"), s"values are parameters, never inlined: $text")
    assertEquals(sql.params.size, 2 + 1 + 1 + 1 + 3)
  }

  test("no brand in scope finds nothing; no filter finds everything") {
    assert(Query.condition(Filters(brands = Some(Set.empty))).render.contains("1 = 0"))
    assertEquals(Query.condition(Filters()).render, "1 = 1")
  }
