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

  test("a grant's row follows the entity: its offer, name and type from the grant, its status from the outcome") {
    import GrantEvent.*
    val events = Vector[GrantEvent](
      Granted("f1", "b-1", "p-1", "o-1", "Welcome", "deposit_match", 1_000, Some("EUR"), at),
      Converted("f2", "b-1", "p-1", at.plusSeconds(60)))
    val row = events.foldLeft(GrantRow(1))((r, e) => GrantRow.of(1, r, e))
    assertEquals((row.bonusId, row.offerId, row.name, row.offerType, row.currency, row.amount, row.grantedAt),
      ("b-1", "o-1", "Welcome", "deposit_match", Some("EUR"), 1_000L, Some(at)))
    assert(row.converted)
    val spins = GrantRow.of(1, GrantRow(1), Granted("f3", "b-2", "p-1", "o-2", "Spins", "free_spins", 0, None, at, 10))
    assertEquals((spins.currency, spins.status, spins.converted), (None, "active", false))
  }

  test("a personal field of a player already erased is kept as erased, so a fact read again after the erasure folds") {
    io.eitheror.casino.reporting.TestKeyring.around { keyring =>
      val marked = io.eitheror.casino.contract.personal.Personal("ada@example.com")
      assertEquals(Personals.shown(Personals.of("p-1", marked)), "ada@example.com")
      keyring.erase(Personals.subject("p-1"))
      assertEquals(Personals.of("p-1", marked), Some(com.thinkmorestupidless.ankka.core.Personal.Erased(Personals.subject("p-1"))))
      assertEquals(Personals.shown(Personals.of("p-2", marked)), "ada@example.com", "another player is untouched")
    }
  }

  test("T062: a case's opening and closing meet in one row; limits, self-exclusions and KYC decisions rows of their own") {
    import PlayerLogEvent.*
    def fold(es: PlayerLogEvent*): Map[String, ComplianceRow] = es.foldLeft(Map.empty[String, ComplianceRow]) { (m, e) =>
      ComplianceRows.of(1, e).fold(m)((k, next) => m.updated(k, next(m.get(k))))
    }
    val until = at.plusSeconds(86_400)
    val rows = fold(
      RgCaseOpened("f1", "p-1", "c-1", "cooling_off", "player_request", None, false, at),
      RgCaseClosed("f2", "p-1", "c-1", "cooling_off", "expired", at.plusSeconds(60)),
      RgLimitApplied("f3", "p-1", "deposit", Some("daily"), Some(5_000), "player", Some("EUR"), at),
      StatusChanged("f4", "p-2", "player_self_excluded", "active", "self_excluded", "player", Some("player_request"), at, Some(until)),
      StatusChanged("f5", "p-2", "player_suspended", "active", "suspended", "operator", None, at),
      KycDocumentDecided("f6", "p-3", "d-1", "identity", Some(at), at.plusSeconds(30), "rejected", Some("illegible")),
      KycChanged("f7", "p-3", "player_kyc_rejected", "rejected", at.plusSeconds(40), Some("documents"), Some(at)),
      KycChanged("f8", "p-3", "player_kyc_pending", "pending", at.plusSeconds(50)))
    assertEquals(rows("rg/c-1"), ComplianceRow(1, "rg", "p-1", "cooling_off", Some(at), Some(at.plusSeconds(60)), "expired", "player_request"))
    assertEquals(rows("rg/limit/f3"), ComplianceRow(1, "rg", "p-1", "deposit", Some(at), None, "applied", "player"))
    assertEquals(rows("rg/self-exclusion/f4"), ComplianceRow(1, "rg", "p-2", "self_exclusion", Some(at), Some(until), "self_excluded",
      "player_request"))
    assertEquals(rows("kyc/d-1"), ComplianceRow(1, "kyc", "p-3", "identity", Some(at), Some(at.plusSeconds(30)), "rejected", "illegible"))
    assertEquals(rows(s"kyc/p-3/${at.plusSeconds(40).toEpochMilli}"),
      ComplianceRow(1, "kyc", "p-3", "status", Some(at), Some(at.plusSeconds(40)), "rejected", "documents"))
    assertEquals(rows.size, 5, "a suspension and a pending KYC status are no compliance row")
  }
