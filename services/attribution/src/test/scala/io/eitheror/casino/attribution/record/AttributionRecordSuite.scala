package io.eitheror.casino.attribution.record

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.attribution.{Binding, MoneyEvent, Reasons, TagOutcome, Touch}
import io.eitheror.casino.attribution.record.Record.*

import java.time.Instant

/** Every row of data-model.md's `AttributionRecord` command table. */
class AttributionRecordSuite extends munit.FunSuite:
  private val t0 = Instant.parse("2026-10-25T10:00:00Z")
  private def at(s: Long) = t0.plusSeconds(s)
  private def binding(boundAt: Instant = t0, touch: String = Binding.Tag) =
    Binding("programme", "aff-1", "lnk-1", None, Vector.empty, Some("c-1"), "pseud", boundAt, touch)
  private def deposit(id: String, when: Instant, amount: Long = 5000) =
    MoneyEvent(MoneyEvent.Deposit, "EUR", amount, s"pay-$id", when, s"d-$id")
  private def kit = EventSourcedTestKit.of(AttributionRecord, "player-1")

  test("registered with a valid tag: Registered and Bound by the tag") {
    val r = kit.call(AttributionRecord.registered)(Register(t0, Some("GB"), Some(binding()), None))
    assertEquals(r.events, Vector(Registered(t0, Some("GB")), Bound(binding(), Some("GB"), Vector.empty, None)))
    assertEquals(r.replyValue.binding.map(_.touch), Some(Binding.Tag))
  }

  test("registered with a refused tag: TagRefused, the player organic") {
    val r = kit.call(AttributionRecord.registered)(Register(t0, None, None, Some(Refusal("tag-x", Reasons.Stale, t0))))
    assertEquals(r.events, Vector(Registered(t0, None), TagRefused("tag-x", Reasons.Stale, t0)))
    assert(!r.replyValue.bound)
    assertEquals(r.replyValue.refused.map(_.reason), Some(Reasons.Stale))
  }

  test("registered with a tag and a code: Bound by the tag, the code a touch") {
    val code = Touches.ofCode("SUMMER50", Touches.Later, t0)
    val r = kit.call(AttributionRecord.registered)(Register(t0, None, Some(binding()), None, Vector(code)))
    assertEquals(r.events.last, TouchRecorded(Touch(Touch.Code, "summer50", t0, Touches.Later)))
    assertEquals(r.replyValue.binding.map(_.touch), Some(Binding.Tag))
  }

  test("a second registered binds nothing: a new tag is a later touch, a redelivery records nothing") {
    val k = kit
    k.call(AttributionRecord.registered)(Register(t0, None, Some(binding()), None)): Unit
    assert(!k.call(AttributionRecord.registered)(Register(t0, None, Some(binding()), None)).persisted)
    val other = binding(at(10)).copy(affiliate = "aff-2", link = "lnk-2")
    val again = k.call(AttributionRecord.registered)(Register(at(10), None, Some(other), None))
    assertEquals(again.events, Vector(TouchRecorded(Touch(Touch.Tag, "lnk-2", at(10), Touches.Later))))
    assertEquals(again.replyValue.binding, Some(binding()))
    assert(!k.call(AttributionRecord.registered)(Register(at(10), None, Some(other), None)).persisted)
    val organic = EventSourcedTestKit.of(AttributionRecord, "player-2")
    organic.call(AttributionRecord.registered)(Register(t0, None, None, None)): Unit
    val refused = organic.call(AttributionRecord.registered)(Register(at(5), None, None, Some(Refusal("tag-y", Reasons.Stale, at(5)))))
    assertEquals(refused.events, Vector(TouchRecorded(Touch(Touch.Tag, "tag-y", at(5), Reasons.Stale))))
    assert(!refused.replyValue.bound)
  }

  test("bind on a bound record is refused already_bound") {
    val k = kit
    k.call(AttributionRecord.registered)(Register(t0, None, None, None)): Unit
    assertEquals(k.call(AttributionRecord.bind)(binding(at(5), Binding.LaterCode)).events.size, 1)
    val again = k.call(AttributionRecord.bind)(binding(at(6), Binding.LaterCode))
    assertEquals(again.errorMessage, Record.AlreadyBound)
    assertEquals(Touches.ofBinding(binding(at(6), Binding.LaterCode)).outcome, Record.AlreadyBound)
  }

  test("touch keeps the last 100") {
    val k = kit
    (1 to 105).foreach(i => k.call(AttributionRecord.touch)(Touch(Touch.Link, s"l-$i", at(i), Touches.Later)): Unit)
    val ts = k.currentState.touches
    assertEquals((ts.size, ts.head.value, ts.last.value), (100, "l-6", "l-105"))
    assertEquals(Touches.ofTag("t", TagOutcome.Refused(Reasons.BadSignature), t0).outcome, Reasons.BadSignature)
  }

  test("a deposit while unbound is pending and sets the first deposit") {
    val k = kit
    k.call(AttributionRecord.registered)(Register(t0, None, None, None)): Unit
    val r = k.call(AttributionRecord.money)(deposit("1", at(10)))
    assertEquals(r.events, Vector(PendingRecorded(deposit("1", at(10)), first = true)))
    assertEquals(r.replyValue.firstDepositAt, Some(at(10)))
    assertEquals(k.call(AttributionRecord.money)(deposit("2", at(20))).events, Vector(PendingRecorded(deposit("2", at(20)), first = false)))
  }

  test("deposits when bound: first = true, then false; a seen dedupe id is a no-op") {
    val k = kit
    k.call(AttributionRecord.registered)(Register(t0, None, Some(binding()), None)): Unit
    assertEquals(k.call(AttributionRecord.money)(deposit("1", at(10))).events, Vector(DepositRecorded(deposit("1", at(10)), true, binding())))
    assertEquals(k.call(AttributionRecord.money)(deposit("2", at(20))).events, Vector(DepositRecorded(deposit("2", at(20)), false, binding())))
    assert(!k.call(AttributionRecord.money)(deposit("2", at(20))).persisted)
    assertEquals(k.currentState.firstDepositAt, Some(at(10)))
  }

  test("Bound after pending deposits carries those at or after boundAt only, and the first deposit among them") {
    val k = kit
    k.call(AttributionRecord.registered)(Register(t0, None, None, None)): Unit
    k.call(AttributionRecord.money)(deposit("1", at(10))): Unit
    k.call(AttributionRecord.money)(deposit("2", at(30))): Unit
    val r = k.call(AttributionRecord.bind)(binding(at(20), Binding.LaterCode))
    assertEquals(r.events, Vector(Bound(binding(at(20), Binding.LaterCode), None, Vector(deposit("2", at(30))), None)))
    assert(r.replyValue.pending.isEmpty)
    assert(!k.call(AttributionRecord.money)(deposit("2", at(30))).persisted)
    assertEquals(k.currentState.firstDepositAt, Some(at(10)))
  }

  test("a pending first deposit at or after boundAt is named in Bound") {
    val k = kit
    k.call(AttributionRecord.money)(deposit("1", at(10))): Unit
    val r = k.call(AttributionRecord.bind)(binding(at(5), Binding.LaterCode))
    assertEquals(r.eventOfType[Bound].firstDeposit, Some("d-1"))
  }

  test("pending is pruned past 200 and past 48 hours") {
    val k = kit
    (1 to 205).foreach(i => k.call(AttributionRecord.money)(deposit(s"$i", at(i))): Unit)
    assertEquals(k.currentState.pending.size, 200)
    k.call(AttributionRecord.money)(deposit("late", at(49L * 3600))): Unit
    assertEquals(k.currentState.pending.map(_.dedupeId), Vector("d-late"))
  }

  test("a chargeback for a payment already disputed under the same reference is a no-op") {
    val k = kit
    k.call(AttributionRecord.registered)(Register(t0, None, Some(binding()), None)): Unit
    val cb = MoneyEvent(MoneyEvent.Chargeback, "EUR", 5000, "pay-1", at(10), "cb-1")
    assertEquals(k.call(AttributionRecord.money)(cb).events, Vector(ChargebackRecorded(cb, binding())))
    assert(!k.call(AttributionRecord.money)(cb.copy(dedupeId = "cb-2")).persisted)
    val w = MoneyEvent(MoneyEvent.WithdrawalRequested, "EUR", 100, "w-1", at(11), "w-1")
    assertEquals(k.call(AttributionRecord.money)(w).events, Vector(WithdrawalRecorded(w, binding())))
    val bc = MoneyEvent(MoneyEvent.BonusCost, "EUR", 100, "b-1", at(12), "b-1")
    assertEquals(k.call(AttributionRecord.money)(bc).events, Vector(BonusCostRecorded(bc, binding())))
    assert(k.call(AttributionRecord.money)(bc.copy(kind = "other", dedupeId = "x")).isError)
  }

  test("close once") {
    val k = kit
    k.call(AttributionRecord.registered)(Register(t0, None, Some(binding()), None)): Unit
    assertEquals(k.call(AttributionRecord.close)(CloseRecord(Category.SelfExcluded, at(5))).events,
      Vector(Closed(Category.SelfExcluded, at(5), Some(binding()))))
    assert(!k.call(AttributionRecord.close)(CloseRecord(Category.Closed, at(6))).persisted)
    assert(k.call(AttributionRecord.close)(CloseRecord("gone", at(6))).isError)
  }

  test("erase records Erased and Closed(closed) when none was, once; the binding is kept") {
    val k = kit
    k.call(AttributionRecord.registered)(Register(t0, None, Some(binding()), None)): Unit
    val r = k.call(AttributionRecord.erase)(at(9))
    assertEquals(r.events, Vector(Erased(at(9)), Closed(Category.Closed, at(9), Some(binding()))))
    assert(!k.call(AttributionRecord.erase)(at(10)).persisted)
    assert(k.call(AttributionRecord.get).replyValue.bound)
  }

  test("erase after a close records only Erased") {
    val k = kit
    k.call(AttributionRecord.close)(CloseRecord(Category.Closed, at(1))): Unit
    assertEquals(k.call(AttributionRecord.erase)(at(2)).events, Vector(Erased(at(2))))
  }
