package io.eitheror.casino.reporting.day

import io.eitheror.casino.reporting.intake.{ProviderFigures, WalletFigures}
import io.eitheror.casino.reporting.reads.{RoundRow, WithdrawalRow}

import java.time.Instant

/** T060: what a day's close is given from the views: the games, the differences, the pending holds, the opening corrections. */
class DayEndInputsSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-08T10:00:00Z")
  private def p(stake: Long, win: Long, outcome: String = "win") =
    Some(ProviderFigures("st8", "pr", "g-1", Some("Book of Gold"), Some("slots"), "gs", stake, win, outcome, at))
  private def w(stake: Long, win: Long, rollback: Long = 0) = Some(WalletFigures(stake, win, rollback, Some(at)))
  private def round(id: String, provider: Option[ProviderFigures], wallet: Option[WalletFigures], status: String) =
    RoundRow(1, "p-1", "EUR", id, provider, wallet, "2026-11-08", status, provider.map(_.game), provider.map(_.session))

  private val rounds = Vector(
    round("r1", p(1000, 400), w(1000, 400), "matched"),
    round("r2", p(500, 0), w(600, 0), "differs"),
    round("r3", p(300, 0, "cancelled"), w(300, 0, 300), "matched"),
    round("r4", None, w(200, 500), "wallet_only"),
    round("r5", p(300, 100), None, "provider_only"))

  test("a game's provider figures beside the wallet's; a cancelled round nothing; the wallet's own under unknown") {
    val g = DayEndInputs.games(rounds)("EUR")
    assertEquals(g("st8/g-1"), GameFigures("st8", "g-1", Some("Book of Gold"), 1000 + 500 + 300, 400 + 100, 1000 + 600, 400))
    assertEquals(g("unknown/unknown"), GameFigures("unknown", "unknown", None, 0, 0, 200, 500))
  }

  test("every round that differs or one side lacks is listed with both sides, in order") {
    assertEquals(DayEndInputs.differences(rounds).map(d => (d.roundId, d.status, d.provider)),
      Vector(("r2", "differs", "st8"), ("r4", "wallet_only", ""), ("r5", "provider_only", "st8")))
    assertEquals(DayEndInputs.differences(rounds).head, Difference("p-1", "EUR", "r2", "differs", 500, 0, 600, 0, 0, "st8"))
  }

  test("the pending holds per currency, and the corrections that arrived during the day") {
    val ws = Vector(WithdrawalRow(1, "w1", amount = 700, currency = "EUR"), WithdrawalRow(1, "w2", amount = 300, currency = "EUR"),
      WithdrawalRow(1, "w3", amount = 50, currency = "USD"))
    assertEquals(DayEndInputs.pendingHolds(ws), Map("EUR" -> 1000L, "USD" -> 50L))
    val delta = Delta("EUR", 3, Money(stakes = 125))
    val rows = Vector(DayRow(1, "2026-11-01", corrections = Vector(Correction("a", at, delta), Correction("b", at.plusSeconds(86_400), delta))))
    assertEquals(DayEndInputs.opening(rows, at.minusSeconds(60), at.plusSeconds(60)), Vector(OpeningCorrection("2026-11-01", at, delta)))
  }
