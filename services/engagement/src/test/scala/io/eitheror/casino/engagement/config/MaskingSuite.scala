package io.eitheror.casino.engagement.config

/** contracts/routes.md, `ConfigEndpoint`: the original's `maskSecret` and `isMasked` (`CustomerIOConfigRoutes`). */
class MaskingSuite extends munit.FunSuite:

  test("a secret is its first three characters and len − 3 asterisks"):
    assertEquals(Masking.mask(Some("sk-abc123")), "sk-******")
    assertEquals(Masking.mask(Some("abcd")), "abc*")

  test("none, or an empty secret, is the empty string"):
    assertEquals(Masking.mask(None), "")
    assertEquals(Masking.mask(Some("")), "")

  test("a secret of three characters or fewer is shown whole, as the original"):
    assertEquals(Vector("a", "ab", "abc").map(s => Masking.mask(Some(s))), Vector("a", "ab", "abc"))

  test("masked: non-empty and all * after the third character"):
    assertEquals(Vector("sk-******", "abc*", "abc", "a", "***", "******").map(Masking.isMasked), Vector.fill(6)(true))
    assertEquals(Vector("", "sk-abc123", "sk-***x*", "abc*d").map(Masking.isMasked), Vector.fill(4)(false))

  test("a masked value sent back keeps the stored secret"):
    assertEquals(Masking.merge(Masking.mask(Some("sk-abc123")), Some("sk-abc123")), Some("sk-abc123"))
    assertEquals(Masking.merge("sk-***", Some("sk-abc123")), Some("sk-abc123"))

  test("a masked value with nothing stored sets nothing"):
    assertEquals(Masking.merge("sk-******", None), None)
    assertEquals(Masking.merge("sk-******", Some("")), None)

  test("a new value replaces the stored one; empty clears it"):
    assertEquals(Masking.merge("new-key-1", Some("sk-abc123")), Some("new-key-1"))
    assertEquals(Masking.merge("new-key-1", None), Some("new-key-1"))
    assertEquals(Masking.merge("", Some("sk-abc123")), None)

  test("a secret of three characters or fewer reads as masked, so sending it back keeps the stored one"):
    assertEquals(Masking.merge("xyz", Some("sk-abc123")), Some("sk-abc123"))
