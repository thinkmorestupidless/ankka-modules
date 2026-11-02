package io.eitheror.casino.programme.settlement

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.programme.ledger.*

import java.time.{Instant, YearMonth, ZoneOffset}
import scala.collection.mutable

class CloserSuite extends munit.FunSuite with LogCapturing:
  private val at        = Instant.parse("2026-10-15T10:00:00Z")
  private val periodEnd = Instant.parse("2026-11-01T00:00:00Z")

  private final class Calls(minimum: Long = 0) extends CloseCalls:
    val account = EventSourcedTestKit.of(CommissionAccount, "aff-1/goldvibe/EUR")
    val payouts = mutable.Map.empty[String, EventSourcedTestKit[Payout, PayoutState, Payout.Event]]
    def kitOf(id: EntityId) = payouts.getOrElseUpdate(id.toString, EventSourcedTestKit.of(Payout, id.toString))
    def payout(id: EntityId): PayoutState                  = kitOf(id).call(Payout.get).replyValue
    def threshold(affiliate: String, currency: String): Long = minimum
    def close(a: EntityId, c: CloseAccount): Closing        = account.call(CommissionAccount.close)(c).replyValue
    def open(id: EntityId, c: OpenPayout): Unit             = kitOf(id).call(Payout.open)(c): Unit

  private val row = UnsettledRow("aff-1", "goldvibe", "EUR", Map("2026-10" -> 2), Some("2026-10"))
  private val id  = Payout.entityId("aff-1", "goldvibe", "EUR", "2026-10")

  private def cpa(c: Calls, factId: String, amount: Long, when: Instant = at) =
    c.account.call(CommissionAccount.recordCpa)(RecordCpa(factId, amount, when, "UTC", when)): Unit

  test("an account's unclaimed payable facts up to the period's end open its pending payout") {
    val c = Calls()
    cpa(c, "cpa/1", 5_000)
    cpa(c, "cpa/2", 2_000, Instant.parse("2026-11-02T10:00:00Z"))
    val closed = Closer(c, () => periodEnd).closeOne(row, "2026-10", periodEnd)
    assertEquals(closed, Some(ClosedPayout("aff-1", "aff-1:EUR:2026-10", "goldvibe", "EUR", 5_000L)))
    val p = c.payout(id)
    assertEquals((p.status, p.total), (Some(Payout.Pending), 5_000L))
  }

  test("below the threshold nothing is claimed and no payout is written") {
    val c = Calls(minimum = 10_000)
    cpa(c, "cpa/1", 5_000)
    assertEquals(Closer(c, () => periodEnd).closeOne(row, "2026-10", periodEnd), None)
    assertEquals(c.payout(id).status, None)
  }

  test("a paid payout is left as it is; its late fact waits for the next close") {
    val c = Calls()
    cpa(c, "cpa/1", 5_000)
    Closer(c, () => periodEnd).closeOne(row, "2026-10", periodEnd): Unit
    c.kitOf(id).call(Payout.approve)(ApprovePayout("m", at)): Unit
    c.kitOf(id).call(Payout.pay)(PayPayout("m", at)): Unit
    c.account.call(CommissionAccount.markPaid)("aff-1/goldvibe/EUR/2026-10"): Unit
    cpa(c, "cpa/late", 1_000)
    assertEquals(Closer(c, () => periodEnd).closeOne(row, "2026-10", periodEnd), None)
    assertEquals(c.payout(id).total, 5_000L)
    val next = Closer(c, () => periodEnd).closeOne(row, "2026-11", Instant.parse("2026-12-01T00:00:00Z"))
    assertEquals(next.map(_.totalPayable), Some(1_000L))
  }

  test("a late fact swept into an approved payout reverts it to pending, the approval superseded") {
    val c = Calls()
    cpa(c, "cpa/1", 5_000)
    Closer(c, () => periodEnd).closeOne(row, "2026-10", periodEnd): Unit
    c.kitOf(id).call(Payout.approve)(ApprovePayout("m", at)): Unit
    cpa(c, "cpa/late", 1_000)
    Closer(c, () => periodEnd).closeOne(row, "2026-10", periodEnd): Unit
    val p = c.payout(id)
    assertEquals((p.status, p.total, p.supersededApprovals.map(_.total)), (Some(Payout.Pending), 6_000L, Vector(5_000L)))
  }

  test("a thousand closes with late facts between them: no fact claimed twice, none older than the last close left unclaimed (SC-006)") {
    val c      = Calls()
    val random = scala.util.Random(13)
    val closer = Closer(c, () => at)
    var month  = YearMonth.of(2020, 1)
    var facts  = 0
    for n <- 1 to 1_000 do
      if n % 10 == 0 then month = month.plusMonths(1)
      for _ <- 0 until random.nextInt(3) do
        // Up to two months before the period being closed: a late fact for a payout already written.
        val day = month.minusMonths(random.nextInt(3).toLong).atDay(1 + random.nextInt(28)).atStartOfDay().toInstant(ZoneOffset.UTC)
        facts += 1
        cpa(c, s"cpa/$facts", 100 + random.nextInt(900), day)
      val period = month.toString
      val end    = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1)
      val row    = UnsettledRow("aff-1", "goldvibe", "EUR", Map.empty, None)
      closer.closeOne(row, period, end): Unit
      val payout = Payout.entityId("aff-1", "goldvibe", "EUR", period)
      if random.nextInt(4) == 0 && c.payout(payout).status.contains(Payout.Pending) then
        c.kitOf(payout).call(Payout.approve)(ApprovePayout("m", at)): Unit
        if random.nextBoolean() then
          c.kitOf(payout).call(Payout.pay)(PayPayout("m", at)): Unit
          c.account.call(CommissionAccount.markPaid)(Payout.idOf("aff-1", "goldvibe", "EUR", period)): Unit
      val s = c.account.call(CommissionAccount.get).replyValue
      val claimed = s.claims.values.toVector.flatten
      assertEquals(claimed.size, claimed.distinct.size, s"close $n claimed a fact twice")
      val paidHere = c.payout(payout).status.contains(Payout.Paid)
      val left     = s.unsettled.filter(f => f.settledBy.isEmpty && !f.occurredAt.isAfter(end))
      if !paidHere then assertEquals(left.map(_.id), Vector.empty, s"close $n of $period left facts unclaimed")
    // Every fact written is paid or claimed by a payout not yet paid: nothing was lost.
    val s = c.account.call(CommissionAccount.get).replyValue
    val paidTotal = c.payouts.values.map(_.call(Payout.get).replyValue).filter(_.status.contains(Payout.Paid)).map(_.total).sum
    assertEquals(paidTotal + s.unsettled.map(_.amount).sum, c.account.call(CommissionAccount.get).replyValue.sums.amount(
      io.eitheror.casino.programme.commission.FactType.Cpa))
  }
