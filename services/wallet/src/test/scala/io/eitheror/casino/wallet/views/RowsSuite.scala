package io.eitheror.casino.wallet.views

import com.thinkmorestupidless.ankka.core.Codecs
import io.eitheror.casino.wallet.entity.{Outcome, Purpose}
import io.eitheror.casino.wallet.entity.WalletEvent.*

import java.time.Instant

/** Spec 022's rows (data-model.md, Views): the seven types, `reference`, and rows stored before it existed. */
class RowsSuite extends munit.FunSuite:

  private val t0 = Instant.parse("2026-10-25T10:00:00Z")
  private val ok = Outcome()

  test("a row stored before spec 022 reads back with no reference, so the view's version does not change") {
    val serializer = Codecs.serializer[TransactionRow]("wallet.transaction-row")
    val stored =
      """{"transactionId":"t","walletId":"p1/EUR","playerId":"p1","currency":"EUR","type":"deposit","walletType":"real",
        |"amount":100,"balanceBefore":0,"balanceAfter":100,"status":"completed","createdAt":"2026-10-09T10:00:00Z",
        |"createdAtMillis":1}""".stripMargin
    val row = serializer.fromBytes(stored.getBytes("UTF-8"))
    assertEquals((row.reference, row.amount, row.`type`), (None, 100L, "deposit"))
  }

  test("each ledger event writes one real row with its reference; a reservation leaves real balance where it was") {
    def one(e: io.eitheror.casino.wallet.entity.WalletEvent) = TransactionRows.of("p1/EUR", e).loneElement
    extension [A](v: Vector[A]) def loneElement: A = { assertEquals(v.size, 1, v.toString); v.head }

    val reserve = one(ReservationPlaced("o1", "d", "t-r", "o1", 600, 1000, 0, 600, t0, ok))
    assertEquals((reserve.`type`, reserve.balanceBefore, reserve.balanceAfter, reserve.reference), ("reservation", 1000L, 1000L, Some("o1")))
    assertEquals(one(ReservationReleased("o1/1", "d", "t", "o1", 200, 1000, 0, 400, t0, ok)).`type`, "reservation_release")
    val debit = one(SettlementDebited("t1", "d", "t", "t1", "o1", 600, 50, "bob", 400, 0, 0, t0, ok))
    assertEquals((debit.`type`, debit.balanceBefore, debit.balanceAfter, debit.reference), ("settlement_debit", 1000L, 400L, Some("t1")))
    assertEquals(one(SettlementCredited("t1", "d", "t", "t1", 600, "ada", 600, 0, 0, t0, ok)).`type`, "settlement_credit")
    val split = one(MovedToAccount("sp", "d", "t", "pool", "m1", Purpose.Split, "sp", 100, 900, 0, 0, t0, ok))
    assertEquals((split.`type`, split.reference), ("account_debit", Some("pool/m1/sp")))
    assertEquals(one(MovedFromAccount("m1/p1", "d", "t", "pool", "m1", Purpose.ResolutionPayment, "p1", 300, 1200, 0, 0, t0, ok)).`type`,
      "resolution_payment")
    assertEquals(one(MovedFromAccount("mg", "d", "t", "pool", "m1", Purpose.Merge, "mg", 100, 1000, 0, 0, t0, ok)).`type`, "account_credit")
    assertEquals(TransactionType.Ledger.size, 7)
  }
