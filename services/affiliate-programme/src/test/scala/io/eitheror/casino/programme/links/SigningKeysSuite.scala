package io.eitheror.casino.programme.links

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.Instant

class SigningKeysSuite extends munit.FunSuite with LogCapturing:
  private val at  = Instant.parse("2026-11-01T10:00:00Z")
  private def kit = KeyValueEntityTestKit.of(SigningKeys, SigningKeys.Id)

  test("the newest live key signs; the last live key is never retired") {
    val k = kit
    k.call(SigningKeys.add)(AddKey("k1", "x1", at)): Unit
    assertEquals(k.call(SigningKeys.retire)(RetireKey("k1", at)).errorMessage, "the last live signing key cannot be retired")
    val s = k.call(SigningKeys.add)(AddKey("k2", "x2", at.plusSeconds(1))).replyValue
    assertEquals(s.signing.map(_.kid), Some("k2"))
    assert(k.call(SigningKeys.add)(AddKey("k2", "x", at)).isError)
    val r = k.call(SigningKeys.retire)(RetireKey("k1", at.plusSeconds(2))).replyValue
    assertEquals((r.live.map(_.kid), r.keys.size), (Vector("k2"), 2))
    assert(!k.call(SigningKeys.retire)(RetireKey("k1", at.plusSeconds(3))).changed)
  }

  test("the legacy document is withdrawn once") {
    val k = kit
    assertEquals(k.call(SigningKeys.withdrawLegacy)(WithdrawLegacy("op-1", at)).replyValue.legacyWithdrawnAt, Some(at))
    assert(!k.call(SigningKeys.withdrawLegacy)(WithdrawLegacy("op-2", at.plusSeconds(1))).changed)
  }
