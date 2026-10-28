package io.eitheror.casino.attribution

import com.thinkmorestupidless.ankka.core.Serializer
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.AttributionCodecs.given

import java.time.Instant

/** Every shared stored type round-trips; the entities' own types are checked in their suites. */
class AttributionCodecsSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-10-25T10:15:30.123Z")

  private def roundTrip[A](a: A)(using s: Serializer[A]): Unit = assertEquals(s.fromBytes(s.toBytes(a)), a)

  test("a binding with no campaign and five sub-ids survives") {
    roundTrip(Binding("programme", "aff-1", "lnk-1", None, Vector("a", "b", "c", "d", "e"), Some("c-1"), "pseudo", at, Binding.Tag))
    roundTrip(Binding("n", "a", "l", Some("summer"), Vector.empty, None, "p", at, Binding.LaterCode))
  }

  test("a tag's outcome, valid or refused, round-trips") {
    roundTrip[TagOutcome](TagOutcome.Valid(Named("n", "a", "l", Some("c"), Vector("s1")), "k-1", Some(at)))
    roundTrip[TagOutcome](TagOutcome.Valid(Named("programme", "a", "l", None, Vector.empty), "legacy", None))
    Reasons.all.foreach(r => roundTrip[TagOutcome](TagOutcome.Refused(r)))
  }

  test("a touch and a money event round-trip, the amount a JSON number and the time ISO-8601") {
    roundTrip(Touch(Touch.Code, "SUMMER50", at, "later"))
    val e = MoneyEvent(MoneyEvent.Deposit, "ZAR", Long.MaxValue, "pay-1", at, "ce-1")
    roundTrip(e)
    val text = String(summon[Serializer[MoneyEvent]].toBytes(e), "UTF-8")
    assert(text.contains(s"\"amount\":${Long.MaxValue}"), text)
    assert(text.contains("\"at\":\"2026-10-25T10:15:30.123Z\""), text)
  }
