package io.eitheror.casino.reporting.reads

import com.thinkmorestupidless.ankka.testkit.KeyedViewTestKit
import io.eitheror.casino.reporting.Facts
import io.eitheror.casino.reporting.day.{Day, DayState, Delta, Flow, Fold, Money}
import io.eitheror.casino.reporting.intake.*

import java.time.{Instant, ZoneId}

/** T040–T041: the dashboard's reads as their folds decide them: wallets, players, a player's days, the activity feed, the hours. */
class ReadsSuite extends munit.FunSuite:
  private val zone = ZoneId.of("Africa/Johannesburg")
  private val at   = Instant.parse("2026-11-08T10:00:00Z")

  /** A wallet's events as `WalletBook` records them, fact after fact. */
  private def book(facts: io.eitheror.casino.contract.facts.MoneyTransactionsFact*): Vector[BookEvent] =
    facts.zipWithIndex.foldLeft((BookState(), Vector.empty[BookEvent])) { case ((s, acc), (fact, i)) =>
      val es = Book.move(s, s"f-$i", fact, zone)
      (es.foldLeft(s)(Book.apply), acc ++ es)
    }._2

  test("a wallet's row keeps the lifetimes the entity keeps and its open records, closed ones dropped") {
    val events = book(Facts.deposit("t1", 10_000), Facts.bet("t2", "r1", 1_000, 1_000), Facts.win("t3", "r1", 400, 400),
      Facts.rollback("t4", "r2", 100, 100), Facts.awarded("t5", "a-1", 500), Facts.awarded("t6", "a-2", 300),
      Facts.forfeited("t7", "a-2", 300))
    val row = events.foldLeft(WalletRow(1))(WalletRow.next)
    val entity = events.foldLeft(BookState())(Book.apply)
    assertEquals((row.deposits, row.wagered, row.won), (entity.deposits, entity.wagered, entity.won))
    assertEquals((row.deposits, row.wagered, row.won, row.ggr), (10_000L, 900L, 400L, 500L))
    assertEquals(row.records.keySet, Set("a-1"))
    assert(row.openRecords)
    assertEquals(row.records, entity.records)
  }

  test("a player's row follows the entity's status and KYC folds, keeps the test flag and the email as personal") {
    import PlayerLogEvent.*
    val events = Vector[PlayerLogEvent](
      Registered("f1", "p-1", at, Some("ZA"), Some("EUR"), Personals.of("p-1", io.eitheror.casino.contract.personal.Personal("ada@example.com")),
        testAccount = true, at),
      StatusChanged("f2", "p-1", "player_suspended", "active", "suspended", "op", None, at.plusSeconds(60)),
      StatusChanged("f3", "p-1", "player_reinstated", "suspended", "active", "op", None, at.plusSeconds(30)),
      KycChanged("f4", "p-1", "player_kyc_verified", "verified", at.plusSeconds(90)),
      LoggedIn("f5", "p-1", "s-1", at.plusSeconds(120)))
    val row = events.foldLeft(PlayerRow(1))((r, e) => PlayerRow.of(1, r, e))
    assertEquals((row.player, row.country, row.status, row.kycStatus, row.testAccount), ("p-1", Some("ZA"), "suspended", "verified", true))
    assertEquals(row.lastLoginAt, Some(at.plusSeconds(120)))
    assertEquals((row.shownEmail, row.truncatedEmail(5)), ("ada@example.com", "ada@e…"))
    assertEquals(PlayerRow(1, email = Some(com.thinkmorestupidless.ankka.core.Personal.Erased(Personals.subject("p-1")))).truncatedEmail(5),
      "[erased]")
  }

  test("a player's days: stakes, wins and rollbacks on their own day, the pool on its round's; a change handled again folds nothing") {
    val kit = KeyedViewTestKit(PlayerDays)
    // 23:30 in the zone on the 8th, then the round's win at 00:10 on the 9th.
    val late   = Instant.parse("2026-11-08T21:30:00Z")
    val events = book(Facts.bet("t1", "r1", 500, 500, late), Facts.win("t2", "r1", 800, 800, Instant.parse("2026-11-08T22:10:00Z")),
      Facts.deposit("t3", 10_000))
    events.foreach(e => kit.change(PlayerDays.wallets, "g1/p-1/EUR", e): Unit)
    val d8 = kit.row("g1/p-1/EUR/2026-11-08").get
    val d9 = kit.row("g1/p-1/EUR/2026-11-09").get
    assertEquals((d8.stakes, d8.wins, d8.poolGgr, d8.ggr, d8.wagered), (500L, 0L, -300L, 500L, 500L))
    assertEquals((d9.stakes, d9.wins, d9.poolGgr, d9.ggr), (0L, 800L, 0L, -800L))
    assertEquals(kit.rows.size, 2, "a deposit writes no player day")
    // The kit numbers its changes itself, so a change handled again is handed to the view directly, at its own sequence.
    val held = kit.rows
    def changeAt(seq: Long) = new com.thinkmorestupidless.ankka.sdk.KeyedChange[PlayerDayRow]:
      def subject        = "g1/p-1/EUR"
      def sequenceNumber = seq
      def rows = new com.thinkmorestupidless.ankka.sdk.ViewRows[PlayerDayRow]:
        def get(key: String) = held.get(key)
        def ask(query: com.thinkmorestupidless.ankka.sdk.DeclaredQuery, values: (String, String)*) = Vector.empty
    assert(PlayerDaysView().onWallet(events.head, changeAt(1)).isEmpty, "the first event again")
    assert(!PlayerDaysView().onWallet(events.head, changeAt(4)).isEmpty, "a later event")
    assert(kit.change(PlayerDays.wallets, "p-1/EUR", events.head).isEmpty, "an id with no generation is passed over")
  }

  test("the activity feed keys each entry by the original's event id, a later self-exclusion replacing the earlier") {
    import PlayerLogEvent.*
    val kit = KeyedViewTestKit(Activity)
    kit.change(Activity.players, "g1/p-1", Registered("f1", "p-1", at, None, None, None, false, at): PlayerLogEvent): Unit
    kit.change(Activity.players, "g1/p-1", StatusChanged("f2", "p-1", "player_self_excluded", "active", "self_excluded", "p-1", None,
      at.plusSeconds(10)): PlayerLogEvent): Unit
    kit.change(Activity.players, "g1/p-1", StatusChanged("f3", "p-1", "player_self_excluded", "active", "self_excluded", "p-1", None,
      at.plusSeconds(20)): PlayerLogEvent): Unit
    kit.change(Activity.players, "g1/p-1", StatusChanged("f4", "p-1", "player_closed", "active", "closed", "op", None, at): PlayerLogEvent): Unit
    kit.change(Activity.grants, "g1/b-1", GrantEvent.Granted("f5", "b-1", "p-1", "o-1", "Welcome", "deposit_match", 500, Some("EUR"),
      at.plusSeconds(30)): GrantEvent): Unit
    assertEquals(kit.rows.keySet, Set("g1/reg-p-1", "g1/selfex-p-1", "g1/bonus-b-1"))
    assertEquals(kit.row("g1/selfex-p-1").map(r => (r.eventType, r.occurredAt)), Some(("self_exclusion", at.plusSeconds(20))))
    assertEquals(kit.row("g1/bonus-b-1").map(r => (r.eventType, r.player)), Some(("bonus_grant", "p-1")))
  }

  test("a day keeps deposits, withdrawals and registrations per hour, so yesterday is read to the same hour") {
    val folds = Vector(
      Delta("EUR", 9, Money(deposits = 1_000, depositCount = 1)),
      Delta("EUR", 15, Money(deposits = 2_000, depositCount = 1)),
      Delta("EUR", 10, Money(withdrawals = 500, withdrawalCount = 1)),
      Delta(hour = 8, registrations = 1),
      Delta(hour = 20, registrations = 1, test = true))
    val s = folds.zipWithIndex.foldLeft(DayState()) { case (s, (d, i)) =>
      Day.fold(s, Fold(s"src-$i", 1, d, at)).fold(s)(Day.apply(s, _))
    }
    val eur = s.currencies("EUR").total
    assertEquals(eur.hourDeposits.take(10).foldLeft(Flow())(_ + _), Flow(1_000, 1))
    assertEquals(eur.hourDeposits.foldLeft(Flow())(_ + _), Flow(3_000, 2))
    assertEquals(eur.hourWithdrawals(10), Flow(500, 1))
    val row = io.eitheror.casino.reporting.day.DayRow.of(1, "2026-11-08", s)
    assertEquals((row.registrationsTo(10, false), row.registrationsTo(23, false), row.registrationsTo(23, true)), (1L, 2L, 1L))
  }
