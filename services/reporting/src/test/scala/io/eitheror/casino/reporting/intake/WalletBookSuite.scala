package io.eitheror.casino.reporting.intake

import com.thinkmorestupidless.ankka.core.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.testkit.{CommandResult, ConsumerTestKit, EventSourcedTestKit}
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.{Recorded, Settings}
import io.eitheror.casino.reporting.Facts.*

import java.time.{Instant, ZoneId}

/** T018: `WalletBook` over every player family, a repeat, a converted stake and a trade; `money-facts`. */
class WalletBookSuite extends munit.FunSuite:
  private val jhb  = ZoneId.of("Africa/Johannesburg")
  private val id   = WalletBook.id(1, "p-1", "EUR")
  private def kit  = EventSourcedTestKit.of(WalletBook, id)
  private def move(k: EventSourcedTestKit[WalletBook, BookState, BookEvent], fact: f.MoneyTransactionsFact, zone: ZoneId = jhb) =
    k.call(WalletBook.move)(MoveFact(FactId.of(f.MoneyTransactions, fact), zone.getId, fact))
  private def moved(r: CommandResult[BookState, BookEvent, ?]) = r.events.collect { case m: Moved => m }.head

  test("every player family is one Moved with its family, ledger type, amount, reference and casino flag") {
    val k = kit
    val cases: Vector[(f.MoneyTransactionsFact, String, String, Long, String, Boolean)] = Vector(
      (deposit("t1", 1000), "deposit_confirmed", "deposit", 1000L, "pay-t1", true),
      (bet("t2", "r-1", 200, 200), "bet_placed", "bet", 200L, "r-1", true),
      (win("t3", "r-1", 300, 300), "win_credited", "win", 300L, "r-1", true),
      (rollback("t4", "r-1", 50, 50), "bet_rolled_back", "rollback", 50L, "r-1", true),
      (loss("t5", "r-1"), "round_settled_as_loss", "loss", 0L, "r-1", true),
      (awarded("t6", "aw-1", 500), "restricted_funds_awarded", "bonus_credit", 500L, "aw-1", true),
      (converted("t7", "aw-1", 400), "restricted_funds_converted", "bonus_conversion", 400L, "aw-1", true),
      (forfeited("t8", "aw-2", 100), "restricted_funds_forfeited", "bonus_cancel", 100L, "aw-2", true),
      (closed("t9", "aw-1"), "restricted_funds_closed", "bonus_cap_void", 0L, "aw-1", true),
      (adjusted("t10", -70), "admin_adjusted", "admin_adjustment", -70L, "t10", true),
      (held("t11", "w-1", 250), "hold_placed", "withdrawal", 250L, "w-1", true),
      (released("t12", "w-1", 250), "hold_released", "withdrawal_return", 250L, "w-1", true),
      (reversed("t13", "w-2", 90), "withdrawal_reversed", "withdrawal_reversal", 90L, "w-2", true),
      (credited("t14", "fs-1", 60), "provider_credited", "game_provider_credit", 60L, "fs-1", true),
      (reserved("t15", "o-1", 100), "reservation_placed", "", 100L, "o-1", false),
      (unreserved("t16", "o-1", 40), "reservation_released", "", 40L, "o-1", false),
      (settledDebit("t17", "tr-1", 60), "settlement_debit", "", 60L, "tr-1", false),
      (settledCredit("t18", "tr-2", 60), "settlement_credit", "", 60L, "tr-2", false),
      (paid("t19", "pos-1", 100), "resolution_payment", "", 100L, "pos-1", false),
      (accountDebit("t20", "fee", 3), "account_debit", "", 3L, "trade-1", false),
      (accountCredit("t21", "fee", 2), "account_credit", "", 2L, "ref-1", false)
    )
    cases.foreach { (fact, family, kind, amount, reference, casino) =>
      val r = move(k, fact)
      assert(r.replyValue, family)
      val e = moved(r)
      assertEquals((e.family, e.kind, e.amount, e.reference, e.casino), (family, kind, amount, reference, casino), family)
      assertEquals((e.player, e.currency, e.transactionId, e.at, e.day), ("p-1", "EUR", fact.transactionId, at, "2026-11-08"), family)
    }
    assertEquals(moved(move(k, accountDebit("t22", "fee", 1))).accountKind, Some("fee"))
  }

  test("a repeated fact persists nothing and answers false") {
    val k = kit
    assert(move(k, deposit("t1", 1000)).replyValue)
    val again = move(k, deposit("t1", 1000))
    assert(!again.persisted)
    assert(!again.replyValue)
    assertEquals(k.currentState.deposits, 1000L)
  }

  test("lifetimes: deposits, stakes less rollbacks, wins and provider credits; spec 022's families move none") {
    val k = kit
    Seq(deposit("t1", 1000), bet("t2", "r-1", 300, 300), rollback("t3", "r-1", 100, 100), win("t4", "r-1", 50, 50),
      credited("t5", "fs-1", 20), settledDebit("t6", "tr-1", 500), paid("t7", "pos-1", 900)).foreach(move(k, _): Unit)
    val s = k.currentState
    assertEquals((s.deposits, s.wagered, s.won), (1000L, 200L, 70L))
    assertEquals((s.player, s.currency, s.real), ("p-1", "EUR", 1900L))
  }

  test("a converted stake carries its deposit part only; its win and rollback likewise") {
    val k = kit
    val stake = moved(move(k, bet("t1", "r-1", 1000, 600)))
    assertEquals((stake.amount, stake.depositPart), (1000L, 600L))
    assertEquals(moved(move(k, win("t2", "r-1", 400, 240))).depositPart, 240L)
  }

  test("a round's later legs count on its first stake's day; a leg with no stake seen on its own") {
    val k = kit
    // 23:30 in Johannesburg on the 8th; the win at 00:10 on the 9th
    val late = Instant.parse("2026-11-08T21:30:00Z")
    val s = moved(move(k, bet("t1", "r-1", 100, 100, late)))
    assertEquals((s.day, s.roundDay), ("2026-11-08", Some("2026-11-08")))
    val w = moved(move(k, win("t2", "r-1", 300, 300, late.plusSeconds(2400))))
    assertEquals((w.day, w.roundDay), ("2026-11-09", Some("2026-11-08")))
    val other = moved(move(k, win("t3", "r-9", 10, 10, late.plusSeconds(2400))))
    assertEquals(other.roundDay, Some("2026-11-09"))
    // a second stake does not move the round's day
    move(k, bet("t4", "r-1", 100, 100, late.plusSeconds(3000))): Unit
    assertEquals(k.currentState.roundDay("r-1"), Some("2026-11-08"))
  }

  test("only the last 500 rounds' days are kept") {
    val k = kit
    (1 to 501).foreach(i => move(k, bet(s"t$i", s"r-$i", 1, 1)): Unit)
    assertEquals(k.currentState.rounds.length, 500)
    assertEquals(k.currentState.roundDay("r-1"), None)
    assertEquals(k.currentState.roundDay("r-501"), Some("2026-11-08"))
  }

  test("records: upserted by award, a closed one dropped; the same list twice is the same records") {
    val k = kit
    val r = move(k, awarded("t1", "aw-1", 500))
    assertEquals(r.events.collect { case c: RecordsChanged => c.records }, Vector(Vector(OpenRecord("aw-1", "ACTIVE", 500, 0, 5000))))
    val list = Some(Vector(record("aw-1", balance = 300, progress = 1200), record("aw-2", balance = 100)))
    move(k, bet("t2", "r-1", 200, 0, records = list)): Unit
    move(k, bet("t3", "r-1", 0, 0, records = list)): Unit
    assertEquals(k.currentState.records, Map(
      "aw-1" -> OpenRecord("aw-1", "ACTIVE", 300, 1200, 5000), "aw-2" -> OpenRecord("aw-2", "ACTIVE", 100, 0, 5000)))
    move(k, converted("t4", "aw-1", 300)): Unit
    assertEquals(k.currentState.records("aw-1").status, "CONVERTED_PENDING_CAP")
    move(k, closed("t5", "aw-1")): Unit
    assertEquals(k.currentState.records.keySet, Set("aw-2"))
    // a movement with no records leaves them
    assert(!move(k, deposit("t6", 10)).events.exists(_.isInstanceOf[RecordsChanged]))
  }

  test("holds: placed, then released or reversed") {
    val k = kit
    move(k, held("t1", "w-1", 250)): Unit
    move(k, held("t2", "w-2", 100)): Unit
    assertEquals(k.currentState.holds, Vector(Hold("w-1", 250), Hold("w-2", 100)))
    move(k, released("t3", "w-1", 250)): Unit
    move(k, reversed("t4", "w-2", 100)): Unit
    assertEquals(k.currentState.holds, Vector.empty)
  }

  test("the seen window is 1,000 facts") {
    val k = kit
    (1 to 1001).foreach(i => move(k, deposit(s"t$i", 1)): Unit)
    assertEquals(k.currentState.seen.length, 1000)
    assertEquals(k.currentState.deposits, 1001L)
  }

  test("money-facts sends each wallet fact to its generation's WalletBook in the brand's zone") {
    val r        = Recorded()
    val settings = Settings("goldvibe", reportingZone = jhb, generation = 3)
    val kit      = ConsumerTestKit.of(MoneyFacts(settings), client = r.client)
    val fact     = bet("t1", "r-1", 200, 200)
    assertEquals(kit.onMessage(Some(fact)).effect, ConsumerEffect.Done)
    val call = r.calls.head
    assertEquals((call.component, call.entity, call.method), ("wallet-book", "g3/p-1/EUR", "move"))
    assertEquals(call.as[MoveFact](using WalletBook.given_Serializer_MoveFact), MoveFact(FactId.of(f.MoneyTransactions, fact), "Africa/Johannesburg", fact))
    assertEquals(MoneyFacts(settings).componentId.toString, "money-facts-g3")
  }

  test("money-facts ignores an account's adjustment and an unreadable fact") {
    val r   = Recorded()
    val kit = ConsumerTestKit.of(MoneyFacts(Settings("goldvibe")), client = r.client)
    val adj = f.AccountAdjusted("fee", "brand", eur, "t1", s(10), "correction", "staff-1", s(10), m(0), at)
    assertEquals(kit.onMessage(Some(adj)).effect, ConsumerEffect.Ignore)
    assertEquals(kit.onMessage(None).effect, ConsumerEffect.Ignore)
    assertEquals(r.calls, Vector.empty)
  }
