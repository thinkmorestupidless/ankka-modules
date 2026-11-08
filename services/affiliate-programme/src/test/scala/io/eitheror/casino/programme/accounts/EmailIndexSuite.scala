package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.Instant

class EmailIndexSuite extends munit.FunSuite with LogCapturing:
  test("an email belongs to one account; another is refused; the holder may release it") {
    val k = KeyValueEntityTestKit.of(EmailIndex, "digest-1")
    assertEquals(k.call(EmailIndex.claim)(ClaimEmail("aff-1", at)).replyValue.affiliate, Some("aff-1"))
    assert(!k.call(EmailIndex.claim)(ClaimEmail("aff-1", at)).changed)
    assertEquals(k.call(EmailIndex.claim)(ClaimEmail("aff-2", at)).errorMessage, "email_already_registered")
    k.call(EmailIndex.release)("aff-2"): Unit
    assertEquals(k.call(EmailIndex.get).replyValue.affiliate, Some("aff-1"))
    k.call(EmailIndex.release)("aff-1"): Unit
    assertEquals(k.call(EmailIndex.claim)(ClaimEmail("aff-2", at)).replyValue.affiliate, Some("aff-2"))
  }

  test("a holder with no account is taken over only once its claim is older than an enrolment takes") {
    val k = KeyValueEntityTestKit.of(EmailIndex, "digest-2")
    k.call(EmailIndex.claim)(ClaimEmail("aff-1", at)): Unit
    // Still enrolling: a second application in the same minute is refused, not given the address.
    assertEquals(k.call(EmailIndex.claim)(ClaimEmail("aff-2", at.plusSeconds(30), orphan = Some("aff-1"))).errorMessage, "email_already_registered")
    // A holder the caller did not find orphaned is never taken over, however old.
    assertEquals(k.call(EmailIndex.claim)(ClaimEmail("aff-2", at.plusSeconds(3600))).errorMessage, "email_already_registered")
    assertEquals(k.call(EmailIndex.claim)(ClaimEmail("aff-2", at.plusSeconds(301), orphan = Some("aff-1"))).replyValue.affiliate, Some("aff-2"))
  }

  private val at = Instant.parse("2026-11-08T10:00:00Z")
