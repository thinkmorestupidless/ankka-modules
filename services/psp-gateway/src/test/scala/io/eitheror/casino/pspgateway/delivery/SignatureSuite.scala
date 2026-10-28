package io.eitheror.casino.pspgateway.delivery

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

/**
 * Research R13: what the gateway signs, `merchant` verifies. `verify` is 004's `security/Hmac.verify`,
 * copied (the two services share no code but the contract), and the cases are `HmacSuite`'s.
 */
class SignatureSuite extends munit.FunSuite with LogCapturing:

  private val now    = Instant.parse("2026-10-12T10:00:00Z")
  private val secret = "whsec-test"
  private val body   = """{"outcome":"success","psp_transaction_id":"p1"}"""

  /** 004's `Hmac.verify` for a configured secret: `None` is verified, `Some(status)` the refusal. */
  private def verify(body: String, headers: Vector[(String, String)], secret: String, at: Instant): Option[Int] =
    val sig = headers.collectFirst { case (Signature.SignatureHeader, v) => v }
    val ts  = headers.collectFirst { case (Signature.TimestampHeader, v) => v }
    (sig, ts) match
      case (Some(s), Some(t)) =>
        Try(t.toLong).toOption match
          case None                                              => Some(401)
          case Some(stamp) if math.abs(at.getEpochSecond - stamp) > 300 => Some(403)
          case Some(_) =>
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
            val want = mac.doFinal(body.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString
            if MessageDigest.isEqual(want.getBytes(UTF_8), s.trim.toLowerCase.getBytes(UTF_8)) then None else Some(403)
      case _ => Some(401)

  test("a signed body verifies under 004's Hmac.verify") {
    assertEquals(verify(body, Signature.sign(body, secret, now), secret, now), None)
  }

  test("it carries both headers, the timestamp in Unix seconds") {
    val h = Signature.sign(body, secret, now).toMap
    assertEquals(h.keySet, Set("X-PSP-Signature", "X-PSP-Timestamp"))
    assertEquals(h("X-PSP-Timestamp"), now.getEpochSecond.toString)
    assert(h("X-PSP-Signature").matches("[0-9a-f]{64}"))
  }

  test("return 403 when signature is invalid (wrong secret)") {
    assertEquals(verify(body, Signature.sign(body, "other", now), secret, now), Some(403))
  }

  test("return 403 when the signature is over another body") {
    assertEquals(verify(body + " ", Signature.sign(body, secret, now), secret, now), Some(403))
  }

  test("return 403 when timestamp is outside the 300s replay window; the edge is inside") {
    assertEquals(verify(body, Signature.sign(body, secret, now.minusSeconds(301)), secret, now), Some(403))
    assertEquals(verify(body, Signature.sign(body, secret, now.minusSeconds(300)), secret, now), None)
  }

  test("the signature is hex HMAC-SHA256 of the UTF-8 body") {
    val h = Signature.sign("The quick brown fox jumps over the lazy dog", "key", now).toMap
    assertEquals(h("X-PSP-Signature"), "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8")
  }

  test("a body outside ASCII is signed over its UTF-8 bytes") {
    val b = """{"failureReason":"Paiement refusé"}"""
    assertEquals(verify(b, Signature.sign(b, secret, now), secret, now), None)
  }
