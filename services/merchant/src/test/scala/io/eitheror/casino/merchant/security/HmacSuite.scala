package io.eitheror.casino.merchant.security

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.merchant.security.Hmac.Outcome

import java.time.Instant

/** FR-018, case for case of the original's `CashierCaptureAuthSpec` HMAC cases. */
class HmacSuite extends munit.FunSuite with LogCapturing:

  private val now    = Instant.parse("2026-10-12T10:00:00Z")
  private val secret = Some("whsec-test")
  private val body   = """{"outcome":"success"}"""
  private def ts(age: Long = 0) = Some((now.getEpochSecond - age).toString)
  private def sig(s: String = "whsec-test", b: String = body) = Some(Hmac.sign(s, b))

  test("return 401 when X-PSP-Signature header is absent and PSP has a webhookSecret configured") {
    assertEquals(Hmac.verify(body, None, None, secret, now), Outcome.Refused(401, "missing_token", "PSP signature header required"))
  }

  test("proceed without HMAC when no webhookSecret is configured (unsigned callback — SIM PSP path)") {
    assertEquals(Hmac.verify(body, None, None, None, now), Outcome.Unsigned)
  }

  test("return 401 when one header is present without the other") {
    assert(Hmac.verify(body, sig(), None, secret, now).isInstanceOf[Outcome.Refused])
    assertEquals(Hmac.verify(body, None, ts(), secret, now).asInstanceOf[Outcome.Refused].status, 401)
    assertEquals(Hmac.verify(body, sig(), None, None, now).asInstanceOf[Outcome.Refused].status, 401)
  }

  test("return 401 when the timestamp is not a number") {
    assertEquals(Hmac.verify(body, sig(), Some("yesterday"), secret, now).asInstanceOf[Outcome.Refused].status, 401)
  }

  test("return 403 when signature is invalid (wrong secret) and timestamp is valid") {
    assertEquals(Hmac.verify(body, sig("other"), ts(), secret, now), Outcome.Refused(403, "invalid_token", "PSP signature verification failed"))
  }

  test("return 403 when the signature is over another body") {
    assertEquals(Hmac.verify(body, sig(b = body + " "), ts(), secret, now).asInstanceOf[Outcome.Refused].status, 403)
  }

  test("return 403 when timestamp is outside the 300s replay window (even with valid HMAC)") {
    assertEquals(Hmac.verify(body, sig(), ts(301), secret, now), Outcome.Refused(403, "invalid_token", "Request timestamp outside replay window"))
    assertEquals(Hmac.verify(body, sig(), ts(-301), secret, now).asInstanceOf[Outcome.Refused].status, 403)
    assertEquals(Hmac.verify(body, sig(), ts(300), secret, now), Outcome.Verified, "the window's edge is inside")
  }

  test("proceed past auth when HMAC and timestamp are both valid; the hex is compared ignoring case") {
    assertEquals(Hmac.verify(body, sig(), ts(), secret, now), Outcome.Verified)
    assertEquals(Hmac.verify(body, sig().map(_.toUpperCase), ts(), secret, now), Outcome.Verified)
  }

  test("a signed callback for a provider with no secret is answered unavailable, not accepted") {
    assert(Hmac.verify(body, sig(), ts(), None, now).isInstanceOf[Outcome.Unavailable])
  }

  test("the signature is hex HMAC-SHA256 of the UTF-8 body") {
    assertEquals(Hmac.sign("key", "The quick brown fox jumps over the lazy dog"), "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8")
  }
