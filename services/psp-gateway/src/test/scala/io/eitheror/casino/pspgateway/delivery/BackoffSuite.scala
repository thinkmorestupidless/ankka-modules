package io.eitheror.casino.pspgateway.delivery

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.pspgateway.delivery.Backoff.Reading

import java.time.Duration

/** FR-015: the original's `handler/outcome_retry_internal_test.go`, its schedule and classification. */
class BackoffSuite extends munit.FunSuite with LogCapturing:

  test("the schedule is the original's: 4 attempts, 250 ms doubling, a 15 s budget (webhook.go:576-578)") {
    assertEquals(Backoff.Attempts, 4)
    assertEquals((1 to 4).map(Backoff.delayBefore).toVector,
      Vector(Duration.ZERO, Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1)))
    assertEquals(Backoff.Budget, Duration.ofSeconds(15))
    assertEquals(Backoff.PerCall, Duration.ofSeconds(10))
  }

  test("TestOutcomeGivesUpAfterExhaustingAttempts: no fifth attempt") {
    assert(Backoff.another(3, Duration.ofSeconds(1)))
    assert(!Backoff.another(4, Duration.ZERO))
  }

  test("an exhausted budget stops retrying regardless of status") {
    assert(!Backoff.another(1, Duration.ofMillis(14_900)))
    assert(Backoff.another(1, Duration.ofSeconds(10)))
  }

  test("TestOutcomeRetryableClassification") {
    assert(Backoff.retryable(None), "no response at all is retryable")
    assert(Backoff.retryable(Some(503)))
    assert(Backoff.retryable(Some(500)))
    assert(Backoff.retryable(Some(429)))
    assert(!Backoff.retryable(Some(401)))
    assert(!Backoff.retryable(Some(400)))
  }

  test("TestOutcomeDoesNotRetryClientErrors: any other 4xx is a refusal, recorded") {
    Vector(400, 401, 403, 404, 409, 422).foreach(s => assertEquals(Backoff.read(Some(s)), Reading.Refused, s))
  }

  test("2xx is delivered, whatever it acknowledges") {
    Vector(200, 201, 202, 204).foreach(s => assertEquals(Backoff.read(Some(s)), Reading.Delivered, s))
  }
