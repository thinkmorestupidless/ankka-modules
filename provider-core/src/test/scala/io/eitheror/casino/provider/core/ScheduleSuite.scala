package io.eitheror.casino.provider.core

import scala.concurrent.duration.*

/** The house provider's retry schedule as `PROVIDER_RETRY_SCHEDULE` writes it (spec 026 R10). */
class ScheduleSuite extends munit.FunSuite:

  private val retryOn = Set("busy")

  private def parse(text: String) = Schedule.fromJson(text, 5.seconds, retryOn)

  test("the setting's default is the provider's default: 30 s for ten minutes, then ten minutes, to eight hours") {
    val parsed = parse("""{"intervals":[{"every":"30s","for":"10m"},{"every":"10m"}],"cap":"8h"}""")
    assertEquals(parsed, Right(Schedule.providerDefault(5.seconds, retryOn)))
  }

  test("the default retries 20 times in the first ten minutes, then every ten minutes until eight hours") {
    val s = parse("""{"intervals":[{"every":"30s","for":"10m"},{"every":"10m"}],"cap":"8h"}""").toOption.get
    assertEquals(s.nextInterval(0.seconds), Some(30.seconds))
    assertEquals(s.nextInterval(9.minutes + 59.seconds), Some(30.seconds))
    assertEquals(s.nextInterval(10.minutes), Some(10.minutes))
    assertEquals(s.nextInterval(7.hours + 50.minutes), Some(10.minutes))
    assertEquals(s.nextInterval(7.hours + 51.minutes), None)
    assertEquals(s.nextInterval(8.hours), None)
  }

  test("a wait and the words retried may be given; otherwise the brand's wait and the protocol's words") {
    val written = parse("""{"wait":"2s","intervals":[],"cap":"0s","retryOn":["error","busy"]}""").toOption.get
    assertEquals(written.answerWait, 2.seconds)
    assertEquals(written.retryOn, Set("error", "busy"))
    assert(!written.retries)
    val left = parse("""{"intervals":[{"every":"1m"}],"cap":"1h"}""").toOption.get
    assertEquals(left.answerWait, 5.seconds)
    assertEquals(left.retryOn, retryOn)
  }

  test("a malformed schedule is refused naming what to fix") {
    val cases = Vector(
      "not json"                                                         -> "not a JSON object",
      """{"intervals":[],"cap":"8h","extra":1}"""                        -> "no field 'extra'",
      """{"cap":"8h"}"""                                                 -> "schedule.intervals is required",
      """{"intervals":[{"every":"soon"}],"cap":"8h"}"""                  -> "schedule.intervals[0].every",
      """{"intervals":[{"every":"0s"}],"cap":"8h"}"""                    -> "longer than nothing",
      """{"intervals":[{"every":"1m"},{"every":"2m"}],"cap":"8h"}"""     -> "only the last",
      """{"intervals":[]}"""                                             -> "schedule.cap is required",
      """{"intervals":[],"cap":"8h","retryOn":"error"}"""                -> "schedule.retryOn"
    )
    cases.foreach { (text, expected) =>
      val refusal = parse(text).left.toOption.getOrElse(fail(s"accepted: $text"))
      assert(refusal.contains(expected), s"$text: $refusal")
    }
  }
