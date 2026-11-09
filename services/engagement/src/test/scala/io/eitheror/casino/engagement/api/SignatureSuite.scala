package io.eitheror.casino.engagement.api

import io.eitheror.casino.engagement.api.Signature.Refusal

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** FR-017's verification, in the original's order; the first four are `CustomerIOWebhookRoutesSpec`'s cases. */
class SignatureSuite extends munit.FunSuite:
  private val key  = "whsec-test"
  private val now  = Instant.parse("2025-11-06T10:00:00Z")
  private val ts   = now.getEpochSecond.toString
  private val body = """{"action":"grant_bonus","player_id":"p-1"}""".getBytes(UTF_8)

  private def verify(
      configured: Boolean = true,
      signingKey: Option[String] = Some(key),
      timestamp: Option[String] = Some(ts),
      signature: Option[String] = Some(Signature.sign(key, ts, body)),
      raw: Array[Byte] = body
  ) = Signature.verify(configured, signingKey, timestamp, signature, raw, now)

  test("the signature is lowercase hex HMAC-SHA256 of v0:<ts>:<body> under the signing key"):
    assertEquals(Signature.sign(key, "1762423200", body), "52028f2a84116a94788e2c4a1020ebc1ddd20a0cdf69734ff9a4f837f6f61ba3")

  // CustomerIOWebhookRoutesSpec
  test("return 200 with bonusAmount when HMAC is valid and offer is active: a valid signature passes"):
    assertEquals(verify(), Right(()))

  test("return 401 when X-CIO-Signature header is missing"):
    assertEquals(verify(signature = None), Left(Refusal.InvalidSignature))

  test("return 401 when HMAC signature is wrong"):
    assertEquals(verify(signature = Some("deadbeefdeadbeef")), Left(Refusal.InvalidSignature))

  test("return 401 when timestamp is stale (> 300s old)"):
    val stale = (now.getEpochSecond - 400).toString
    assertEquals(verify(timestamp = Some(stale), signature = Some(Signature.sign(key, stale, body))), Left(Refusal.TimestampInvalid))

  // The route's own order (CustomerIOWebhookRoutes.route)
  test("no configuration is brand_not_configured, before anything else"):
    assertEquals(verify(configured = false, signingKey = None, timestamp = None, signature = None), Left(Refusal.BrandNotConfigured))

  test("no signing key, or an empty one, is webhook_not_configured, before the timestamp"):
    assertEquals(verify(signingKey = None, timestamp = None), Left(Refusal.WebhookNotConfigured))
    assertEquals(verify(signingKey = Some(""), timestamp = None), Left(Refusal.WebhookNotConfigured))

  test("a missing or non-numeric timestamp is timestamp_invalid, before the signature"):
    assertEquals(verify(timestamp = None, signature = None), Left(Refusal.TimestampInvalid))
    assertEquals(verify(timestamp = Some(""), signature = None), Left(Refusal.TimestampInvalid))
    assertEquals(verify(timestamp = Some("yesterday"), signature = None), Left(Refusal.TimestampInvalid))
    assertEquals(verify(timestamp = Some("1.5e9"), signature = None), Left(Refusal.TimestampInvalid))

  test("the window is abs(now − ts) < 300 s, either side; 300 is outside"):
    def at(offset: Long) =
      val t = (now.getEpochSecond + offset).toString
      verify(timestamp = Some(t), signature = Some(Signature.sign(key, t, body)))
    assertEquals(Vector(-299L, 299L).map(at), Vector(Right(()), Right(())))
    assertEquals(Vector(-300L, 300L).map(at), Vector(Left(Refusal.TimestampInvalid), Left(Refusal.TimestampInvalid)))

  test("a timestamp far out of range does not wrap into the window"):
    assertEquals(verify(timestamp = Some(Long.MinValue.toString), signature = None), Left(Refusal.TimestampInvalid))
    assertEquals(verify(timestamp = Some(Long.MaxValue.toString), signature = None), Left(Refusal.TimestampInvalid))

  test("the signature covers the body and the timestamp, under this key, in lowercase hex"):
    assertEquals(verify(raw = """{"action":"grant_bonus","player_id":"p-2"}""".getBytes(UTF_8)), Left(Refusal.InvalidSignature))
    val other = (now.getEpochSecond - 1).toString
    assertEquals(verify(signature = Some(Signature.sign(key, other, body))), Left(Refusal.InvalidSignature))
    assertEquals(verify(signature = Some(Signature.sign("another-key", ts, body))), Left(Refusal.InvalidSignature))
    assertEquals(verify(signature = Some(Signature.sign(key, ts, body).toUpperCase)), Left(Refusal.InvalidSignature))
    assertEquals(verify(signature = Some("")), Left(Refusal.InvalidSignature))

  test("each refusal is 401 under its code"):
    assertEquals(
      Refusal.values.toVector.map(r => (r.status, r.code)),
      Vector((401, "brand_not_configured"), (401, "webhook_not_configured"), (401, "timestamp_invalid"), (401, "invalid_signature"))
    )
