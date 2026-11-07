package io.eitheror.casino.programme.ledger

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, KeyedViewTestKit, LogCapturing}
import io.eitheror.casino.programme.commission.*

import java.time.Instant

class LedgerViewSuite extends munit.FunSuite with LogCapturing:
  private val account = "aff-1/goldvibe/EUR"
  private val oct     = Instant.parse("2026-10-15T10:00:00Z")
  private val nov     = Instant.parse("2026-11-15T10:00:00Z")
  private val now     = Instant.parse("2026-11-20T10:00:00Z")
  private val octEnd  = Instant.parse("2026-11-01T00:00:00Z")

  /** An account and the two views fed every event it persists, as the runtime would. */
  private final class Fixture:
    val entity    = EventSourcedTestKit.of(CommissionAccount, account)
    val ledger    = KeyedViewTestKit(Ledger)
    var unsettled = UnsettledRow("aff-1", "goldvibe", "EUR")
    ledger.answering(Ledger.ofPayout)(v => ledger.rows.values.filter(_.settledBy == v.get("payout")).toVector)

    def run[R](r: com.thinkmorestupidless.ankka.testkit.CommandResult[AccountState, CommissionAccount.Event, R]): R =
      r.events.foreach { e =>
        ledger.change(Ledger.accounts, account, e): Unit
        unsettled = UnsettledRow.next(unsettled, e)
      }
      r.replyValue

    def facts: Vector[LedgerFact] = entity.call(CommissionAccount.get).replyValue.unsettled

  private def fixture =
    val f = Fixture()
    f.run(f.entity.call(CommissionAccount.recordNgr)(RecordNgr("g1", "ggr", 10_000, oct, Rate(2500, Window.Period, true),
      FeeRates(admin = 1_000), "UTC", now))): Unit
    f.run(f.entity.call(CommissionAccount.recordNgr)(RecordNgr("b1", "bonus-cost", 1_000, oct, Rate(2500, Window.Period, true),
      FeeRates(), "UTC", now))): Unit
    f.run(f.entity.call(CommissionAccount.recordCpa)(RecordCpa("cpa/goldvibe/ps-1", 5_000, oct, "UTC", now))): Unit
    f.run(f.entity.call(CommissionAccount.recordCpa)(RecordCpa("cpa/goldvibe/ps-2", 5_000, nov, "UTC", now))): Unit
    f

  test("one row per fact, and the sums by type equal the facts' sums") {
    val f    = fixture
    val sums = f.entity.call(CommissionAccount.get).replyValue.sums
    assertEquals(f.ledger.rows.size, 7)
    val totals = LedgerFold.totals(f.ledger.rows.values).head
    FactType.values.foreach(t => assertEquals(totals.amount(t), sums.amount(t), t.wire))
    assertEquals((totals.ngr, totals.deductions), (8_000L, 2_000L))
    assertEquals(LedgerFold.byPeriod(f.ledger.rows.values)("2026-11"), Map(FactType.Cpa -> 5_000L))
  }

  test("settled facts carry their payout, a release unstamps them, payment marks them paid") {
    val f = fixture
    f.run(f.entity.call(CommissionAccount.close)(CloseAccount("aff-1/goldvibe/EUR/2026-10", octEnd, 0))): Unit
    val stamped = f.ledger.rows.values.filter(_.settledBy.contains("aff-1/goldvibe/EUR/2026-10")).map(_.factId).toSet
    assertEquals(stamped, Set("revshare/g1", "revshare/b1", "cpa/goldvibe/ps-1"))
    assertEquals(f.unsettled.open, Map("2026-11" -> 1))
    assertEquals(f.unsettled.earliest, Some("2026-11"))
    f.run(f.entity.call(CommissionAccount.markPaid)("aff-1/goldvibe/EUR/2026-10")): Unit
    assert(f.ledger.rows.values.filter(_.settledBy.isDefined).forall(_.paid))
  }

  test("a close below the threshold after a claim leaves the stamps and the counts") {
    val f = fixture
    f.run(f.entity.call(CommissionAccount.close)(CloseAccount("p", octEnd, 0))): Unit
    val stamped = f.ledger.rows.values.filter(_.settledBy.contains("p")).map(_.factId).toSet
    val open    = f.unsettled.open
    f.run(f.entity.call(CommissionAccount.close)(CloseAccount("p", octEnd, 1_000_000))): Unit
    assert(stamped.nonEmpty)
    assertEquals(f.ledger.rows.values.filter(_.settledBy.contains("p")).map(_.factId).toSet, stamped)
    assertEquals(f.unsettled.open, open)
  }

  test("the declared queries pass ankka's checks") {
    assertEquals(KeyedViewTestKit(Ledger).rows, Map.empty[String, LedgerRow])
  }
