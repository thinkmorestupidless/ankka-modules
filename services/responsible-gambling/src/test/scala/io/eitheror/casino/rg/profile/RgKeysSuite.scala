package io.eitheror.casino.rg.profile

/** `RgKeys`' rule (spec 017 amendments R2–R4): a repeat answers the first, another body is reused, an evicted key expired. */
class RgKeysSuite extends munit.FunSuite:
  private val done = KeptReply(200, """{"status":"ok"}""")

  test("a new key is fresh; under the same body it repeats the first answer; under another body it is reused"):
    val s = RgKeysState()
    assertEquals(s.check("k1", "h1"), KeyCheck.Fresh)
    val kept = s.remember("k1", "h1", done)
    assertEquals(kept.check("k1", "h1"), KeyCheck.Repeat(done))
    assertEquals(kept.check("k1", "h2"), KeyCheck.Reused)

  test("the first answer under a key is kept, a second is not"):
    val s = RgKeysState().remember("k1", "h1", done).remember("k1", "h1", KeptReply(409, "{}"))
    assertEquals(s.check("k1", "h1"), KeyCheck.Repeat(done))
    assertEquals(s.recent.size, 1)

  test("past the last 64, the oldest key is expired, never fresh, and the newest are kept"):
    val s = (1 to RgKeysState.Recent + 2).foldLeft(RgKeysState())((s, i) => s.remember(s"k$i", "h", done))
    assertEquals(s.recent.size, RgKeysState.Recent)
    assertEquals(s.check("k1", "h"), KeyCheck.Expired)
    assertEquals(s.check("k2", "h"), KeyCheck.Expired)
    assertEquals(s.check("k3", "h"), KeyCheck.Repeat(done))
    assertEquals(s.check("k-new", "h"), KeyCheck.Fresh)

  test("the state survives its codec"):
    val s = RgKeysState().remember("k1", "h1", done).copy(evicted = Set(RgKeysState.digest("k0")))
    val ser = RgKeys.stateSerializer
    assertEquals(ser.fromBytes(ser.toBytes(s)), s)
    import RgKeys.given
    val check = summon[com.thinkmorestupidless.ankka.core.Serializer[KeyCheck]]
    Vector(KeyCheck.Fresh, KeyCheck.Repeat(done), KeyCheck.Reused, KeyCheck.Expired).foreach(c => assertEquals(check.fromBytes(check.toBytes(c)), c))
