package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

class EmailIndexSuite extends munit.FunSuite with LogCapturing:
  test("an email belongs to one account; another is refused; the holder may release it") {
    val k = KeyValueEntityTestKit.of(EmailIndex, "digest-1")
    assertEquals(k.call(EmailIndex.claim)("aff-1").replyValue.affiliate, Some("aff-1"))
    assert(!k.call(EmailIndex.claim)("aff-1").changed)
    assertEquals(k.call(EmailIndex.claim)("aff-2").errorMessage, "email_already_registered")
    k.call(EmailIndex.release)("aff-2"): Unit
    assertEquals(k.call(EmailIndex.get).replyValue.affiliate, Some("aff-1"))
    k.call(EmailIndex.release)("aff-1"): Unit
    assertEquals(k.call(EmailIndex.claim)("aff-2").replyValue.affiliate, Some("aff-2"))
  }
