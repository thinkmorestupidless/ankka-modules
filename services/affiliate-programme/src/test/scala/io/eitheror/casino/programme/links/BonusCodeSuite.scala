package io.eitheror.casino.programme.links

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.Instant

class BonusCodeSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-11-01T10:15:00Z")
  private def claim(aff: String, text: String = "spin10") = ClaimCode(text, "goldvibe", aff, "lnk-1", Some("offer-3"), "tag", at)

  test("the text in upper case is the id: a live code refuses a second claim whatever its case") {
    assertEquals(BonusCode.entityId("goldvibe", "spin10").toString, "goldvibe/SPIN10")
    val k = KeyValueEntityTestKit.of(BonusCode, "goldvibe/SPIN10")
    assertEquals(k.call(BonusCode.claim)(claim("aff-1")).replyValue.code, "SPIN10")
    assertEquals(k.call(BonusCode.claim)(claim("aff-2", "SPIN10")).errorMessage, "code_already_exists")
    assertEquals(k.call(BonusCode.get).replyValue.affiliate, "aff-1")
  }

  test("revoking frees the text; every change counts, so the document's version rises") {
    val k = KeyValueEntityTestKit.of(BonusCode, "goldvibe/SPIN10")
    k.call(BonusCode.claim)(claim("aff-1")): Unit
    assertEquals(k.call(BonusCode.revoke)(RevokeCode(Some("aff-2"), at)).errorMessage, "forbidden")
    assertEquals(k.call(BonusCode.revoke)(RevokeCode(Some("aff-1"), at)).replyValue.status, Some(BonusCode.Revoked))
    assert(!k.call(BonusCode.revoke)(RevokeCode(None, at)).changed)
    val again = k.call(BonusCode.claim)(claim("aff-2")).replyValue
    assertEquals((again.affiliate, again.live, again.changes), ("aff-2", true, 3))
  }

  test("a code never claimed is not found") {
    assertEquals(KeyValueEntityTestKit.of(BonusCode, "goldvibe/NONE").call(BonusCode.revoke)(RevokeCode(None, at)).errorMessage, "not_found")
  }
