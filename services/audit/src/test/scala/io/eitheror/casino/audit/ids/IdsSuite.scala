package io.eitheror.casino.audit.ids

class IdsSuite extends munit.FunSuite:

  private val key   = RegistryKeyBytes(Array.tabulate[Byte](32)(_.toByte))
  private val other = RegistryKeyBytes(Array.fill[Byte](32)(7))

  test("a digest is d- and 32 hex, stable under one key, different under another and at another brand") {
    val d = Ids.digest(key, "goldvibe", "wallet/p-1/EUR/k-1")
    assert(d.matches("d-[0-9a-f]{32}"), d)
    assertEquals(Ids.digest(key, "goldvibe", "wallet/p-1/EUR/k-1"), d)
    assertNotEquals(Ids.digest(other, "goldvibe", "wallet/p-1/EUR/k-1"), d)
    assertNotEquals(Ids.digest(key, "spinvibe", "wallet/p-1/EUR/k-1"), d)
    assert(!d.contains("p-1"))
  }

  test("a registry id is the whole HMAC in hex and names no real id") {
    val id = Ids.registryId(key, "goldvibe", "player", "p-1")
    assert(id.matches("[0-9a-f]{64}"), id)
    assertNotEquals(Ids.registryId(key, "goldvibe", "operator", "p-1"), id)
    assertNotEquals(Ids.registryId(key, "spinvibe", "player", "p-1"), id)
  }

  test("the person kinds: player, operator, affiliate and agent") {
    assertEquals(Vector("player", "operator", "affiliate", "agent", "service", "system", "market").filter(Ids.isPerson),
      Vector("player", "operator", "affiliate", "agent"))
  }

  test("contains finds a real id inside a built id, and an empty id never matches") {
    assert(Ids.contains("wallet/p-1/EUR/k-1", Vector("p-1")))
    assert(!Ids.contains("market/m-9", Vector("p-1")))
    assert(!Ids.contains("market/m-9", Vector("")))
  }

  test("the key is never printed") {
    assertEquals(key.toString, "RegistryKeyBytes(…)")
  }
