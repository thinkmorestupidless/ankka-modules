package io.eitheror.casino.programme.comms

import java.time.Instant
import scala.concurrent.duration.*

class EmailRulesSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-01T10:00:00Z")

  test("three attempts over five minutes, then failed") {
    val (s1, n1) = EmailRules.attempted(EmailState(status = EmailRules.Sending), at, Right(500))
    assertEquals(n1, EmailRules.Next.Retry(1.minute))
    val (s2, n2) = EmailRules.attempted(s1, at, Left("timeout"))
    assertEquals(n2, EmailRules.Next.Retry(4.minutes))
    val (s3, n3) = EmailRules.attempted(s2, at, Right(503))
    assertEquals((n3, s3.status, s3.attempts, s3.lastStatus), (EmailRules.Next.Done, EmailRules.Failed, 3, Some(503)))
  }

  test("a 2xx is sent") {
    val (s, n) = EmailRules.attempted(EmailState(status = EmailRules.Sending), at, Right(202))
    assertEquals((n, s.status, s.finishedAt), (EmailRules.Next.Done, EmailRules.Sent, Some(at)))
  }
