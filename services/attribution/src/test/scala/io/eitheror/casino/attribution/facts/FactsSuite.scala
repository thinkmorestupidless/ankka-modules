package io.eitheror.casino.attribution.facts

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.money.Tally
import io.eitheror.casino.attribution.record.Record
import io.eitheror.casino.attribution.{Binding, MoneyEvent, Named}
import io.eitheror.casino.contract.facts.*

import java.time.Instant

/** One test per row of contracts/facts.md's table that emits; the same event twice is the same fact. */
class FactsSuite extends munit.FunSuite with LogCapturing:
  private val boundAt = Instant.parse("2026-10-25T10:00:00Z")
  private val later   = boundAt.plusSeconds(60)
  private val b       = Binding("programme", "aff-1", "lnk-1", Some("summer"), Vector("s1"), Some("c-1"), "PSEUDO", boundAt, Binding.Tag)

  private def one(facts: Vector[AttributionFact]): AttributionFact =
    assertEquals(facts.size, 1, facts.mkString("\n"))
    facts.head

  private def common(f: AttributionFact): Unit =
    assertEquals(f.protocolVersion, 1)
    assertEquals(f.brand, "goldvibe")
    assertEquals((f.network, f.affiliate, f.link, f.campaign, f.subIds, f.clickId),
      ("programme", "aff-1", "lnk-1", Some("summer"), Vector("s1"), Some("c-1")))

  private def money(kind: String, reference: String, amount: Long = 5000, at: Instant = later) =
    MoneyEvent(kind, "ZAR", amount, reference, at, s"ce-$reference")

  test("a click: the link's key, click/<clickId>, the country and the device type") {
    val f = Facts.click("goldvibe", "c-1", Named("programme", "aff-1", "lnk-1", Some("summer"), Vector("s1")), boundAt, Some("ZA"), Some("mobile"))
    common(f)
    assertEquals((f.factId, f.key, f.country, f.deviceType), ("click/c-1", "lnk-1", Some("ZA"), Some("mobile")))
  }

  test("a binding: registration/<pseudonym>, keyed by the pseudonym, with the country, at the binding") {
    val f = one(Facts.fromRecord("goldvibe", Record.Bound(b, Some("ZA"), Vector.empty, None)))
    common(f)
    f match
      case r: Registration => assertEquals((r.factId, r.key, r.country, r.occurredAt), ("registration/PSEUDO", "PSEUDO", Some("ZA"), boundAt))
      case other           => fail(other.toString)
  }

  test("a first captured deposit: first-deposit and deposit; a later one: deposit only") {
    val first = Facts.fromRecord("goldvibe", Record.DepositRecorded(money(MoneyEvent.Deposit, "pay-1"), first = true, b))
    assertEquals(first.map(_.factId), Vector("first-deposit/PSEUDO", "deposit/PSEUDO/pay-1"))
    first.foreach(common)
    first.foreach {
      case d: Deposit      => assertEquals((d.amount.value, d.currency.code, d.reference), (5000L, "ZAR", "pay-1"))
      case d: FirstDeposit => assertEquals((d.amount.value, d.reference), (5000L, "pay-1"))
      case other           => fail(other.toString)
    }
    val next = Facts.fromRecord("goldvibe", Record.DepositRecorded(money(MoneyEvent.Deposit, "pay-2"), first = false, b))
    assertEquals(next.map(_.factId), Vector("deposit/PSEUDO/pay-2"))
  }

  test("a dispute and a refund: chargeback with the payment id, and with /refund") {
    val dispute = one(Facts.fromRecord("goldvibe", Record.ChargebackRecorded(money(MoneyEvent.Chargeback, "pay-1"), b)))
    assertEquals(dispute.factId, "chargeback/PSEUDO/pay-1")
    val refund = one(Facts.fromRecord("goldvibe", Record.ChargebackRecorded(money(MoneyEvent.Chargeback, "pay-1/refund", 2000), b)))
    refund match
      case c: Chargeback => assertEquals((c.factId, c.amount.value, c.reference), ("chargeback/PSEUDO/pay-1/refund", 2000L, "pay-1/refund"))
      case other         => fail(other.toString)
  }

  test("the four withdrawal kinds, each with the payout id") {
    val kinds = Vector(MoneyEvent.WithdrawalRequested, MoneyEvent.WithdrawalCompleted, MoneyEvent.WithdrawalFailed, MoneyEvent.WithdrawalReversed)
    val facts = kinds.map(k => one(Facts.fromRecord("goldvibe", Record.WithdrawalRecorded(money(k, "po-1"), b))))
    assertEquals(facts.map(_.factId), kinds.map(k => s"$k/PSEUDO/po-1"))
    assertEquals(facts.map(_.getClass.getSimpleName), Vector("AttributedWithdrawalRequested", "AttributedWithdrawalCompleted",
      "AttributedWithdrawalFailed", "AttributedWithdrawalReversed"))
  }

  test("bonus cost: the award's amount, positive, the award id or the provider's reference") {
    val award = one(Facts.fromRecord("goldvibe", Record.BonusCostRecorded(money(MoneyEvent.BonusCost, "aw-1", 2000), b)))
    award match
      case c: BonusCost => assertEquals((c.factId, c.amount.value, c.reference), ("bonus-cost/PSEUDO/aw-1", 2000L, "aw-1"))
      case other        => fail(other.toString)
    assertEquals(one(Facts.fromRecord("goldvibe", Record.BonusCostRecorded(money(MoneyEvent.BonusCost, "prov-2", 900), b))).factId,
      "bonus-cost/PSEUDO/prov-2")
  }

  test("player-closed, once per category, keyed by the pseudonym; nothing for an organic player") {
    val f = one(Facts.fromRecord("goldvibe", Record.Closed("self-excluded", later, Some(b))))
    f match
      case c: PlayerClosedAttributed => assertEquals((c.factId, c.category), ("player-closed/PSEUDO", "self-excluded"))
      case other                     => fail(other.toString)
    assertEquals(Facts.fromRecord("goldvibe", Record.Closed("closed", later, None)), Vector.empty)
  }

  test("nothing for an unbound record's events, nor for money before the binding") {
    assertEquals(Facts.fromRecord("goldvibe", Record.PendingRecorded(money(MoneyEvent.Deposit, "pay-1"), first = true)), Vector.empty)
    assertEquals(Facts.fromRecord("goldvibe", Record.Registered(boundAt, None)), Vector.empty)
    assertEquals(Facts.fromRecord("goldvibe", Record.TagRefused("t", "stale", boundAt)), Vector.empty)
    val before = money(MoneyEvent.Deposit, "pay-0", at = boundAt.minusSeconds(1))
    assertEquals(Facts.fromRecord("goldvibe", Record.DepositRecorded(before, first = true, b)), Vector.empty)
  }

  test("a binding with pending money emits each at or after the binding, the first deposit marked") {
    val pending = Vector(money(MoneyEvent.Deposit, "pay-1"), money(MoneyEvent.WithdrawalRequested, "po-1"))
    val facts   = Facts.fromRecord("goldvibe", Record.Bound(b, None, pending, Some("ce-pay-1")))
    assertEquals(facts.map(_.factId), Vector("registration/PSEUDO", "first-deposit/PSEUDO", "deposit/PSEUDO/pay-1", "withdrawal-requested/PSEUDO/po-1"))
  }

  test("the same event twice gives equal facts and ids") {
    val e = Record.DepositRecorded(money(MoneyEvent.Deposit, "pay-1"), first = true, b)
    assertEquals(Facts.fromRecord("goldvibe", e), Facts.fromRecord("goldvibe", e))
  }

  test("a closed day with movement: ggr-daily, ggr = stake − win, keyed by the pseudonym; a still day: nothing") {
    val f = one(Facts.fromTally("goldvibe", b, Tally.Closed("p", "ZAR", "2026-10-25", later, 1500, 2000), roundStream = false))
    f match
      case g: GgrDaily =>
        assertEquals((g.factId, g.stake.value, g.win.value, g.ggr.value, g.reportingDay, g.reference, g.beforeBinding),
          ("ggr-daily/PSEUDO/ZAR/2026-10-25", 1500L, 2000L, -500L, "2026-10-25", "ZAR/2026-10-25", None))
      case other => fail(other.toString)
    assertEquals(Facts.fromTally("goldvibe", b, Tally.Closed("p", "ZAR", "2026-10-25", later, 0, 0), roundStream = false), Vector.empty)
  }

  test("a leg on a closed day: ggr-adjustment with its delta and the dedupe id") {
    val f = one(Facts.fromTally("goldvibe", b, Tally.Adjusted("p", "ZAR", "2026-10-25", "win", "r1", "ce-9", -300, later), roundStream = false))
    f match
      case a: GgrAdjustment =>
        assertEquals((a.factId, a.ggr.value, a.reference), ("ggr-adjustment/PSEUDO/ZAR/2026-10-25/ce-9", -300L, "ZAR/2026-10-25/ce-9"))
      case other => fail(other.toString)
  }

  test("a settling leg: ggr-round only with the stream on, the deltas since the round's last fact") {
    val first = Tally.LegRecorded("p", "ZAR", "2026-10-25", "win", "r1", "ce-2", 300, later, Some(Tally.RoundDelta(1000, 300, first = true)))
    assertEquals(Facts.fromTally("goldvibe", b, first, roundStream = false), Vector.empty)
    one(Facts.fromTally("goldvibe", b, first, roundStream = true)) match
      case r: GgrRound => assertEquals((r.factId, r.stake.value, r.win.value, r.ggr.value, r.reference), ("ggr-round/PSEUDO/r1/ce-2", 1000L, 300L, 700L, "r1"))
      case other       => fail(other.toString)
    val second = first.copy(dedupeId = "ce-3", round = Some(Tally.RoundDelta(0, 200, first = false)))
    assertEquals(one(Facts.fromTally("goldvibe", b, second, roundStream = true)).asInstanceOf[GgrRound].reference, "r1/ce-3")
    val stake = first.copy(kind = "stake", round = None)
    assertEquals(Facts.fromTally("goldvibe", b, stake, roundStream = true), Vector.empty)
  }

  test("days closed before a later code's binding are marked beforeBinding") {
    val days = Vector(Facts.ClosedDay("ZAR", "2026-10-20", 1000, 0, boundAt.minusSeconds(86400)), Facts.ClosedDay("ZAR", "2026-10-21", 0, 0, boundAt))
    val f    = one(Facts.beforeBinding("goldvibe", b, days)).asInstanceOf[GgrDaily]
    assertEquals((f.factId, f.beforeBinding), ("ggr-daily/PSEUDO/ZAR/2026-10-20", Some(true)))
  }
