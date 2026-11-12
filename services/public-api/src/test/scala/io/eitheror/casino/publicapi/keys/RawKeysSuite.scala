package io.eitheror.casino.publicapi.keys

class RawKeysSuite extends munit.FunSuite:

  test("a key is ak_<16 base32>_<43 base64url>, and parses to its id") {
    val (id, key) = RawKeys.mint()
    assert("ak_[a-z2-7]{16}_[A-Za-z0-9_-]{43}".r.matches(key), key)
    assertEquals(RawKeys.parse(key), Some(Presented(id, key)))
    assertEquals(RawKeys.prefix(id), key.take(19))
  }

  test("every other shape is refused without a lookup") {
    val (id, key) = RawKeys.mint()
    Vector("", "ak_", key + "x", key.dropRight(1), key.replace("ak_", "mk_"), key.toUpperCase, s"ak_${id}x_${key.takeRight(43)}",
      s"ak_${id.take(15)}1_${key.takeRight(43)}", key.replace('_', '-'), s"Bearer $key", s" $key")
      .foreach(t => assertEquals(RawKeys.parse(t), None, t))
  }

  test("1,000 keys and their ids are distinct") {
    val keys = Vector.fill(1000)(RawKeys.mint())
    assertEquals(keys.map(_._1).distinct.size, 1000)
    assertEquals(keys.map(_._2).distinct.size, 1000)
  }

  test("the digest is hex SHA-256 of the whole key, and comparison is by value") {
    val (_, key) = RawKeys.mint()
    val d        = RawKeys.digest(key)
    assert("[0-9a-f]{64}".r.matches(d))
    assert(RawKeys.same(d, RawKeys.digest(key)))
    assert(!RawKeys.same(d, RawKeys.digest(key + "x")))
  }
