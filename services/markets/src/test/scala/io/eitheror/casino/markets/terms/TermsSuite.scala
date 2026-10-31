package io.eitheror.casino.markets.terms

import com.thinkmorestupidless.ankka.core.Serializer

/** A market's own limits (T010): what a book opens with, and the stored shape. */
class TermsSuite extends munit.FunSuite:
  test("a book opens with the market's limits, else the brand's") {
    assertEquals(Terms().orDefaults(100000, 5000), (100000L, 5000L))
    assertEquals(Terms(maxPosition = Some(500)).orDefaults(100000, 5000), (100000L, 500L))
    assertEquals(Terms(Some(2000), Some(50)).orDefaults(100000, 5000), (2000L, 50L))
  }

  test("terms survive the store") {
    val s = summon[Serializer[Terms]]
    val t = Terms(Some(2000), None)
    assertEquals(s.fromBytes(s.toBytes(t)), t)
  }
