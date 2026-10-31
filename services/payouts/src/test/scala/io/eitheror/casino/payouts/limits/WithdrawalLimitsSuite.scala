package io.eitheror.casino.payouts.limits

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.payouts.limits.LimitsEvent.LimitsSet

import java.time.Instant

/** data-model.md's `WithdrawalLimits`: Set, its refusals, and before and after on the event. */
class WithdrawalLimitsSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-10-15T12:00:00Z")
  private def fresh = EventSourcedTestKit.of(WithdrawalLimits, "goldvibe")
  private def set(kit: EventSourcedTestKit[WithdrawalLimits, LimitsState, LimitsEvent], limits: Map[String, Pair], by: String = "op-1") =
    kit.call(WithdrawalLimits.set)(SetLimits(limits, by, at))

  test("a set records the limits, with before and after and the operator"):
    val kit = fresh
    set(kit, Map("EUR" -> Pair(Some(100000L), None))): Unit
    val r = set(kit, Map("eur" -> Pair(Some(50000L), Some(200000L)), "CAD" -> Pair(None, Some(1L))), "op-2")
    assertEquals(r.events.toVector, Vector[LimitsEvent](LimitsSet(Map("EUR" -> Pair(Some(100000L), None)),
      Map("EUR" -> Pair(Some(50000L), Some(200000L)), "CAD" -> Pair(None, Some(1L))), "op-2", at)))
    assertEquals(kit.currentState.of("EUR"), Pair(Some(50000L), Some(200000L)))
    assertEquals(kit.currentState.of("GBP"), Pair())

  test("a set that changes nothing writes nothing; a currency with neither limit is not kept"):
    val kit = fresh
    set(kit, Map("EUR" -> Pair(Some(1L)))): Unit
    assertEquals(set(kit, Map("EUR" -> Pair(Some(1L)), "GBP" -> Pair())).events.toVector, Vector.empty)

  test("a negative amount, or an unknown currency, is refused naming the field, and nothing is written"):
    val kit = fresh
    val negative = set(kit, Map("EUR" -> Pair(Some(1L), Some(-1L))))
    assertEquals((negative.replyValue.refusal, negative.events.toVector), (Some("limits.EUR.monthly"), Vector.empty))
    assertEquals(set(kit, Map("EUR" -> Pair(Some(-5L)))).replyValue.refusal, Some("limits.EUR.weekly"))
    assertEquals(set(kit, Map("euro" -> Pair(Some(5L)))).replyValue.refusal, Some("limits.euro"))

  test("zero is kept as set and checked as no limit"):
    val kit = fresh
    set(kit, Map("EUR" -> Pair(Some(0L)))): Unit
    assertEquals(kit.currentState.of("EUR").normalised, Pair())
