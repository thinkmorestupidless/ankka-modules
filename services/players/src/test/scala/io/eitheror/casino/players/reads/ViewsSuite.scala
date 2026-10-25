package io.eitheror.casino.players.reads

import com.thinkmorestupidless.ankka.testkit.KeyedViewTestKit
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.player.*
import io.eitheror.casino.players.sessions.*

import java.time.Instant

/** The keyed views' folds and the players view's, on the kit (T016); a row per view over Postgres is T027's. */
class ViewsSuite extends munit.FunSuite:

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

  test("recently played keeps the latest time per player and game, and skips an unreadable fact") {
    val kit = KeyedViewTestKit(RecentlyPlayedView)
    def round(game: String, when: Instant) = Some(RoundSettled("p-1", eur, "r", "st8", "pr", game, "gs", MinorUnits.unsafe(100),
      MinorUnits.unsafe(0), SignedMinorUnits(-100), "loss", Vector.empty, Vector.empty, Vector.empty, None, when))
    kit.change(RecentlyPlayedView.rounds, "p-1/EUR", round("g-1", at.plusSeconds(60))): Unit
    kit.change(RecentlyPlayedView.rounds, "p-1/EUR", round("g-1", at)): Unit
    kit.change(RecentlyPlayedView.rounds, "p-1/EUR", Option.empty[CasinoRoundsFact]): Unit
    assertEquals(kit.rows.values.toVector, Vector(RecentlyPlayedRow("p-1", "g-1", at.plusSeconds(60).toEpochMilli)))
  }

  test("activity: one row per fact in the original's terms; notes and messages never appear") {
    val kit = KeyedViewTestKit(ActivityView)
    kit.change(ActivityView.money, "p-1/EUR", Some(DepositConfirmed("p-1", eur, "t-1", MinorUnits.unsafe(5000), "ref", 1,
      SignedMinorUnits(5000), MinorUnits.unsafe(0), at)): Option[MoneyTransactionsFact]): Unit
    kit.change(ActivityView.players, "p-1", Some(PlayerSuspended("p-1", "active", "suspended", "operator", Some("op-1"), None, at)): Option[CasinoPlayersFact]): Unit
    kit.change(ActivityView.players, "p-1", Some(PlayerMessageCreated("p-1", "m-1", "operator", at)): Option[CasinoPlayersFact]): Unit
    kit.change(ActivityView.players, "p-1", Some(RgLimitApplied("p-1", "SESSION_MINUTES", None, Some(60), "player", at)): Option[CasinoPlayersFact]): Unit
    val rows = kit.rows.values.toVector.sortBy(_.eventType)
    assertEquals(rows.map(r => (r.category, r.eventType, r.actor)), Vector(
      (ActivityCategory.Account, "account_status_changed", "operator"),
      (ActivityCategory.Transactions, "deposit", "player"),
      (ActivityCategory.Rg, "rg_limit_applied", "operator")))
    assertEquals(rows.find(_.eventType == "deposit").map(r => (r.amountMinorUnits, r.currency)), Some((Some(5000L), Some("EUR"))))
    assert(rows.forall(r => kit.row(r.factId).contains(r)))
    assertEquals(ActivityCategory.all.map(ActivityCategory.wire), Vector("Transactions", "Games", "Bonus", "Account", "RG"))
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
