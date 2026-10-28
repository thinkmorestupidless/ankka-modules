package io.eitheror.casino.attribution.carriers

import com.fasterxml.jackson.databind.node.ArrayNode
import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.tag.Signing

class PagesSuite extends munit.FunSuite:
  private val (x, priv) = Signing.generate()
  private val facts = Vector(
    Json.obj("type" -> "registration", "factId" -> "registration/P", "network" -> "programme"),
    Json.obj("type" -> "deposit", "factId" -> "deposit/P/pay-1", "amount" -> 5000L))

  test("a page verifies under its key, names its cursor and kid") {
    val page = Pages.assemble(facts, "programme", 2, "b-2026-10", priv)
    assert(Pages.verify(page, x))
    assertEquals(page.get("kid").asText, "b-2026-10")
    assertEquals(Cursor.decode("programme", Some(page.get("cursor").asText)), Right(2L))
  }

  test("a reordered fact, or another key, fails") {
    val page = Pages.assemble(facts, "programme", 2, "b", priv)
    val arr  = page.get("facts").asInstanceOf[ArrayNode]
    val first = arr.remove(0)
    arr.add(first)
    assert(!Pages.verify(page, x))
    val (other, _) = Signing.generate()
    assert(!Pages.verify(Pages.assemble(facts, "programme", 2, "b", priv), other))
  }

  test("a cursor round-trips; another network's is refused; the empty cursor is the start") {
    assertEquals(Cursor.decode("programme", Some(Cursor.encode("programme", 42))), Right(42L))
    assert(Cursor.decode("programme", Some(Cursor.encode("other", 42))).left.exists(_.contains("another network")))
    assert(Cursor.decode("programme", Some("!!")).isLeft)
    assertEquals(Cursor.decode("programme", None), Right(0L))
    assertEquals(Cursor.decode("programme", Some("")), Right(0L))
  }

  test("the empty page returns the given cursor") {
    val page = Pages.assemble(Vector.empty, "programme", 7, "b", priv)
    assertEquals(page.get("facts").size, 0)
    assertEquals(page.get("cursor").asText, Cursor.encode("programme", 7))
    assert(Pages.verify(page, x))
  }

  test("a network id with a colon keeps its own cursor") {
    assertEquals(Cursor.decode("a:b", Some(Cursor.encode("a:b", 3))), Right(3L))
  }
