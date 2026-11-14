package io.eitheror.casino.support.domain

import io.eitheror.casino.support.Json

/** FR-020, FR-028: the arguments a trail keeps. */
class RedactionSuite extends munit.FunSuite:
  test("the five tools' arguments are kept as given"):
    val a = Redaction.arguments(Json.parse("""{"amount":5000,"currency":"GBP","period":"weekly","cool_off_until":"2026-12-01T00:00:00Z","game_session_id":"gs-1"}""").get)
    assertEquals(a, Map("amount" -> "5000", "currency" -> "GBP", "period" -> "weekly", "cool_off_until" -> "2026-12-01T00:00:00Z",
      "game_session_id" -> "gs-1"))

  test("any other argument is kept by its length only"):
    val a = Redaction.arguments(Json.parse("""{"note":"my email is ada@example.com","nested":{"x":1}}""").get)
    assertEquals(a("note"), "<27 chars>")
    assert(!a.values.exists(_.contains("ada")), a.toString)
    assert(a("nested").startsWith("<"))

  test("no arguments, or not an object, is none"):
    assertEquals(Redaction.arguments(Json.parse("{}").get), Map.empty)
    assertEquals(Redaction.arguments(Json.parse("[1]").get), Map.empty)
    assertEquals(Redaction.arguments(null), Map.empty)
