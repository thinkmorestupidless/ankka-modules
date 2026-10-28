package io.eitheror.casino.attribution.carriers

import io.eitheror.casino.attribution.carriers.DeliveryRules.Next

import java.time.{Duration, Instant}
import scala.concurrent.duration.*

/**
 * What a delivery decides and what it posts (research R15; contracts/protocol.md *A webhook post*). The
 * workflow over these runs in the integration suite: ankka 0.13 has no Scala workflow kit.
 */
class DeliverySuite extends munit.FunSuite:
  private val t0    = Instant.parse("2026-10-25T10:00:00Z")
  private val fact  = FeedFact("deposit/pseud/pay-1", "deposit", """{"type":"deposit","amount":5000}""")
  private val begun = DeliveryState("sub-1", "programme", Some(fact), status = DeliveryRules.Delivering)

  test("the post carries the four headers and the body's HMAC under the secret") {
    val h = WebhookHttp.headers(fact, "s3cret")
    assertEquals(h("X-Attribution-Protocol"), "1")
    assertEquals(h("X-Attribution-Fact-Id"), "deposit/pseud/pay-1")
    assertEquals(h("X-Attribution-Kind"), "deposit")
    assertEquals(h("Content-Type"), "application/json")
    // RFC 4231 test case 2's key and data, for the HMAC itself.
    assertEquals(WebhookHttp.signature("what do ya want for nothing?", "Jefe"),
      "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843")
    assertEquals(h("X-Attribution-Signature"), s"sha256=${WebhookHttp.signature(fact.json, "s3cret")}")
  }

  test("a 200 ends it delivered") {
    val (s, next) = DeliveryRules.attempted(begun, t0, Right(200))
    assertEquals((s.status, s.attempts, s.lastStatus, s.firstAttemptAt, next), (DeliveryRules.Delivered, 1, Some(200), Some(t0), Next.Done))
  }

  test("a 503 pauses 1 minute, then 5, 15, 60, 60") {
    val delays = Iterator.iterate((begun, Option.empty[FiniteDuration])) { (s, _) =>
      val (n, next) = DeliveryRules.attempted(s, t0.plusSeconds(s.attempts * 60L), Right(503))
      (n, next match { case Next.Retry(d) => Some(d); case _ => None })
    }.drop(1).take(5).map(_._2.get).toVector
    assertEquals(delays, Vector(1.minute, 5.minutes, 15.minutes, 60.minutes, 60.minutes))
  }

  test("after 24 hours from the first attempt it ends undelivered with the last status") {
    val (once, _) = DeliveryRules.attempted(begun, t0, Right(503))
    val (s, next) = DeliveryRules.attempted(once, t0.plus(Duration.ofHours(24)), Right(502))
    assertEquals((s.status, s.lastStatus, next), (DeliveryRules.Undelivered, Some(502), Next.GiveUp))
    assertEquals(DeliveryRules.attempted(once, t0.plus(Duration.ofHours(23)), Right(502))._2, Next.Retry(5.minutes))
  }

  test("an error is an attempt, keeping the last status and naming the error") {
    val (once, _) = DeliveryRules.attempted(begun, t0, Right(503))
    val (s, next) = DeliveryRules.attempted(once, t0.plusSeconds(60), Left("connection refused"))
    assertEquals((s.attempts, s.lastStatus, s.lastError, next), (2, Some(503), Some("connection refused"), Next.Retry(5.minutes)))
  }

  test("resend starts the attempts again") {
    val gaveUp = DeliveryRules.attempted(DeliveryRules.attempted(begun, t0, Right(500))._1, t0.plus(Duration.ofHours(25)), Right(500))._1
    val r = DeliveryRules.resent(gaveUp)
    assertEquals((r.status, r.attempts, r.firstAttemptAt, r.fact), (DeliveryRules.Delivering, 0, None, Some(fact)))
    assertEquals(DeliveryRules.attempted(r, t0.plus(Duration.ofHours(30)), Right(204))._1.status, DeliveryRules.Delivered)
  }
