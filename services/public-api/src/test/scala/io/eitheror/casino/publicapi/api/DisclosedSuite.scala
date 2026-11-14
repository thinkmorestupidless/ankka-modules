package io.eitheror.casino.publicapi.api

import io.eitheror.casino.publicapi.contract.Refusals

/** Review should-fix 2: only an answer the handler finished reports the players it disclosed to the audit (FR-036). */
class DisclosedSuite extends munit.FunSuite:
  private def served =
    val s = Served(Call(None, Vector.empty, Vector.empty, None, "/v1/players"), Admitted("k", Set("email"), 60), Map.empty)
    s.disclosed += ("p-1" -> Vector("email"))
    Some(s)

  test("a 200 reports the players its handler disclosed") {
    assertEquals(Pipeline.disclosed(Refusals.json(200, "{}"), served), Vector("p-1" -> Vector("email")))
  }

  test("a budget's 503, or any refusal, reports none, whatever the handler had added") {
    assertEquals(Pipeline.disclosed(Refusals.unavailable.respond, served), Vector.empty)
    assertEquals(Pipeline.disclosed(Refusals.notFound.respond, served), Vector.empty)
    assertEquals(Pipeline.disclosed(Refusals.json(200, "{}"), None), Vector.empty)
  }
