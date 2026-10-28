package io.eitheror.casino.ingest.shape

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.ingest.Json

/** Research R3 and R5: the key's digest as the original hashes it; a body's digest blind to key order only. */
class DigestsSuite extends munit.FunSuite with LogCapturing:

  test("a key's digest is lower-case hex SHA-256, as merchant and the original's validateKey make it") {
    assertEquals(Digests.ofKey("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    assert(Digests.ofKey("eo_live_feed9960b62a4677b2b8cc5f").matches("[0-9a-f]{64}"))
  }

  test("two bodies differing only in key order, at any depth, share a digest") {
    val a = Json.parse("""{"amountCents":5000,"currency":"EUR","x":{"b":1,"a":[{"d":2,"c":3}]}}""").get
    val b = Json.parse("""{"x":{"a":[{"c":3,"d":2}],"b":1},"currency":"EUR","amountCents":5000}""").get
    assertEquals(Digests.ofBody(a), Digests.ofBody(b))
  }

  test("a changed amount does not") {
    assertNotEquals(Digests.ofBody(Json.parse("""{"amountCents":5000}""").get), Digests.ofBody(Json.parse("""{"amountCents":6000}""").get))
  }

  test("same compares the digests' bytes") {
    assert(Digests.same(Digests.ofKey("k"), Digests.ofKey("k")))
    assert(!Digests.same(Digests.ofKey("k"), Digests.ofKey("l")))
  }
