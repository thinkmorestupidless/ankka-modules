package io.eitheror.casino.programme.postbacks

import java.time.Instant

class PostbackRulesSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-01T10:00:00Z")
  private val s  = PostbackState("aff-1", "goldvibe", "ftd", "https://example.com/pb?clickid=c-1", status = PostbackRules.Sending)

  test("a postback keeps its address while it retries, and drops it, with the click id in it, once sent or given up") {
    val (retrying, next) = PostbackRules.attempted(s, at, Right(500))
    assertEquals((retrying.url, next.isInstanceOf[PostbackRules.Next.Retry]), (s.url, true))
    val (sent, done) = PostbackRules.attempted(retrying, at.plusSeconds(60), Right(200))
    assertEquals((sent.status, sent.url, done), (PostbackRules.Sent, "", PostbackRules.Next.Done))
    val (lapsed, _) = PostbackRules.attempted(retrying, at.plusSeconds(25 * 3600), Left("timeout"))
    assertEquals((lapsed.status, lapsed.url), (PostbackRules.Undelivered, ""))
  }
