package io.eitheror.casino.audit.registry

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

class RegistrySuite extends munit.FunSuite with LogCapturing:

  test("the first issue draws a UUIDv4; every later one answers it (FR-009)") {
    val k     = KeyValueEntityTestKit.of(Pseudonym, "a1b2")
    val first = k.call(Pseudonym.issue).replyValue
    assert(first.issued)
    assertEquals(java.util.UUID.fromString(first.pseudonym).version(), 4)
    val again = k.call(Pseudonym.issue)
    assertEquals(again.replyValue, Issued(first.pseudonym, issued = false))
    assert(!again.changed)
  }

  test("two people have two pseudonyms") {
    val a = KeyValueEntityTestKit.of(Pseudonym, "a").call(Pseudonym.issue).replyValue.pseudonym
    val b = KeyValueEntityTestKit.of(Pseudonym, "b").call(Pseudonym.issue).replyValue.pseudonym
    assertNotEquals(a, b)
  }

  test("an identity is bound once; the same binding again changes nothing; another is a conflict") {
    val k = KeyValueEntityTestKit.of(Identity, "ps-1")
    assertEquals(k.call(Identity.bind)(Bind("goldvibe", "player", "p-1")).replyValue.outcome, "bound")
    val again = k.call(Identity.bind)(Bind("goldvibe", "player", "p-1"))
    assertEquals(again.replyValue.outcome, "bound")
    assert(!again.changed)
    assertEquals(k.call(Identity.bind)(Bind("goldvibe", "player", "p-2")).replyValue.outcome, "conflict")
    assertEquals(k.call(Identity.get).replyValue, IdentityState("goldvibe", "player", "p-1"))
  }
