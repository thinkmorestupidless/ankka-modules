package io.eitheror.casino.players.reads

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, KeyedViewTestKit, LogCapturing}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.player.*
import io.eitheror.casino.players.sessions.*

import java.time.Instant

/** The keyed views' folds and the players view's, on the kit (T016); a row per view over Postgres is T027's. */
class ViewsSuite extends munit.FunSuite with LogCapturing with io.eitheror.casino.players.it.Held:

  private val at  = Instant.parse("2026-10-25T10:00:00Z")
  private val eur = Currency.unsafe("EUR")

  test("a player session and its game session are one row each; an end records the reason and the minutes") {
    val kit = KeyedViewTestKit(SessionsView)
    kit.change(SessionsView.playerSessions, "ps-1", PlayerSessionEvent.Opened("p-1", "rs-1", Personal(""), Personal(""), Personal(""), at)): Unit
    kit.change(SessionsView.gameSessions, "gs-1", GameSessionEvent.Opened("p-1", "ps-1", "g-1", "EUR", false, at)): Unit
    kit.change(SessionsView.gameSessions, "gs-1", GameSessionEvent.Ended("limit", at.plusSeconds(125))): Unit
    kit.change(SessionsView.playerSessions, "ps-1", PlayerSessionEvent.Ended(SessionStatus.Forced, at.plusSeconds(130))): Unit
    val game = kit.row("game/gs-1").get
    assertEquals((game.parent, game.durationMinutes, game.reason, game.open), ("ps-1", Some(2L), Some("limit"), false))
    assertEquals(kit.row("player/ps-1").map(r => (r.parent, r.status)), Some(("rs-1", SessionStatus.Forced)))
  }

  test("recently played keeps the latest time per player and game; the plain view is checked by SmokeSuite") {
    val kit  = KeyValueEntityTestKit.of(RecentlyPlayed, RecentlyPlayed.key("p-1", "g-1"))
    val late = RecentlyPlayedRow("p-1", "g-1", at.plusSeconds(60).toEpochMilli)
    assertEquals(kit.call(RecentlyPlayed.touchGame)(late).replyValue, Done(changed = true))
    assertEquals(kit.call(RecentlyPlayed.touchGame)(RecentlyPlayedRow("p-1", "g-1", at.toEpochMilli)).replyValue, Done.unchanged)
    assertEquals(kit.currentState, late)
  }

  test("activity: one line per fact in the original's terms; notes and messages never appear; a fact recorded twice is one line") {
    val lines = Vector(
      ActivityLines.money(DepositConfirmed("p-1", eur, "t-1", MinorUnits.unsafe(5000), "ref", 1, SignedMinorUnits(5000), MinorUnits.unsafe(0), at)),
      ActivityLines.player(PlayerSuspended("p-1", "active", "suspended", "operator", Some("op-1"), None, at)),
      ActivityLines.player(PlayerMessageCreated("p-1", "m-1", "operator", at)),
      ActivityLines.player(RgLimitApplied("p-1", "SESSION_MINUTES", None, Some(60), "player", at))).flatten.sortBy(_.eventType)
    assertEquals(lines.map(r => (r.category, r.eventType, r.actor)), Vector(
      (ActivityCategory.Account, "account_status_changed", "operator"),
      (ActivityCategory.Transactions, "deposit", "player"),
      (ActivityCategory.Rg, "rg_limit_applied", "operator")))
    assertEquals(lines.find(_.eventType == "deposit").map(r => (r.amountMinorUnits, r.currency)), Some((Some(5000L), Some("EUR"))))
    val id    = ActivityEntry.key(MoneyTransactions.name, "p-1/EUR", "a deposit")
    assertNotEquals(id, ActivityEntry.key(MoneyTransactions.name, "p-1/EUR", "another deposit"))
    val entry = KeyValueEntityTestKit.of(ActivityEntry, id)
    val line  = lines.find(_.eventType == "deposit").get.copy(factId = id)
    assertEquals(entry.call(ActivityEntry.record)(line).replyValue, Done(changed = true))
    assertEquals(entry.call(ActivityEntry.record)(line.copy(at = 0L)).replyValue, Done.unchanged)
    assertEquals(entry.currentState, line)
    assertEquals(ActivityCategory.all.map(ActivityCategory.wire), Vector("Transactions", "Games", "Bonus", "Account", "RG"))
  }

  test("activity: an opened round is no line; its settlement is") {
    assertEquals(ActivityLines.round(RoundOpened("p-1", eur, "st8/r-1", "st8", "r-1", "g-1", "s-1", None, at)), None)
  }

  test("activity: an operator's reinstatement is a line, a lifted self-exclusion none, as before") {
    def reinstated(reason: Option[String]) =
      ActivityLines.player(PlayerReinstated("p-1", "self_excluded", "active", "system", None, reason, at))
    assertEquals(reinstated(None).map(_.description), Some("Account reinstated"))
    assertEquals(reinstated(Some("exclusion_lifted")), None)
  }

  test("the players view folds status, tier, flags and blanks the personal columns on erasure") {
    import PlayerEvent.*
    val start = PlayerRow("p-1", Status.Pending, displayName = Personal("Ada Lovelace"), primaryEmail = Personal("ada@example.test"))
    val events = Vector[PlayerEvent](EmailVerified(at), VipTierChanged(None, "gold", None, at), DuplicateFlagged("c-1", Set("p-2"), Set("identity"), at),
      Closed(Status.Active, Actor.Player, None, "x", at), ErasureFiled("corr", at, at), Erased(at))
    val end = events.foldLeft(start)((r, e) => PlayersView.fold(r, e).getOrElse(r))
    assertEquals((end.status, end.vipTier, end.flagged), (Status.Erased, Some("gold"), true))
    assertEquals((end.displayName.reveal, end.primaryEmail.reveal), ("", ""))
    assertEquals(PlayersView.fold(start, FavouriteAdded("g-1", at)), None)
    assertEquals(PlayerRow.displayName(Profile(Map("firstName" -> Personal("Ada"), "lastName" -> Personal("L")))), "Ada L")
  }

  test("the players view keeps what the list filters read: identity, birthday and open sessions (spec 030 P2)") {
    import PlayerEvent.*
    val start = PlayerRow("p-1", Status.Active, birthday = PlayerRow.birthday(Some("1990-08-15")))
    def after(events: PlayerEvent*) = events.foldLeft(start)((r, e) => PlayersView.fold(r, e).getOrElse(r))
    assertEquals(start.birthday.reveal, "08-15")
    assertEquals(after(KycVerified(at)).kycVerified, true)
    assertEquals(after(KycVerified(at), KycWithdrawn(at)).kycVerified, false)
    assertEquals(after(SessionOpened("s-1", at), SessionOpened("s-2", at), SessionEnded("s-1", at)).openSessions, Vector("s-2"))
    assertEquals(after(SessionOpened("s-1", at)).online, true)
    assertEquals(after(DateOfBirthCorrected(Personal("1991-02-03"), "op-1", "typo", at)).birthday.reveal, "02-03")
    assertEquals(after(ProfileEdited(Profile(Map("dateOfBirth" -> Personal("1980-12-31"))), Set("dateOfBirth"), at)).birthday.reveal, "12-31")
    val erased = after(SessionOpened("s-1", at), Erased(at))
    assertEquals((erased.birthday.reveal, erased.online), ("", false))
    assertEquals(PlayerRow.birthday(Some("15/08/1990")).reveal, "")
  }

  test("the birthday window runs over the year's end, and a 29 February birthday falls on 28 February in a common year") {
    val f = PlayersView.Filter(birthdayWithin = Some(3), today = java.time.LocalDate.parse("2026-12-30"))
    assertEquals(f.birthdays, Vector("12-30", "12-31", "01-01", "01-02"))
    assertEquals(PlayersView.Filter(birthdayWithin = Some(0), today = java.time.LocalDate.parse("2027-02-28")).birthdays, Vector("02-28", "02-29"))
    assertEquals(PlayersView.Filter(birthdayWithin = Some(0), today = java.time.LocalDate.parse("2028-02-28")).birthdays, Vector("02-28"))
  }
