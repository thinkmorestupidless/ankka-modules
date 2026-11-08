package io.eitheror.casino.programme.players

import java.time.Instant

class PlayersViewSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-01T10:00:00Z")
  private def fact(kind: String, id: String, amount: Long = 0) =
    PlayerFact(kind, id, at, "goldvibe", "ps-1", "aff-1", "lnk-1", Some("summer"), country = Some("MT"), currency = Some("EUR"),
      amount = amount, category = Some("self-exclusion"))
  private val binding = Some(Binding("aff-1", "lnk-1", Some("summer"), Vector.empty, None, at))

  test("a row lists the binding, the first deposit and the totals, and forgets the country on closing") {
    val events = Vector(
      PlayerAttribution.Applied(fact(PlayerFact.Registration, "r"), binding, Outcome.Bound),
      PlayerAttribution.Applied(fact(PlayerFact.FirstDeposit, "fd1", 2_000), binding, Outcome.FirstDeposit(true)),
      PlayerAttribution.Applied(fact(PlayerFact.Deposit, "d1", 2_000), binding, Outcome.Deposit),
      PlayerAttribution.Applied(fact(PlayerFact.Deposit, "d2", 500), binding, Outcome.Deposit)
    )
    val row = events.foldLeft(PlayerRow())(PlayerRow.next)
    assertEquals((row.pseudonym, row.affiliate, row.link, row.country, row.firstDepositAt), ("ps-1", Some("aff-1"), Some("lnk-1"), Some("MT"), Some(at)))
    assertEquals((row.deposits("EUR").count, row.deposits("EUR").sum), (2, 2_500L))
    val closed = PlayerRow.next(row, PlayerAttribution.Applied(fact(PlayerFact.PlayerClosed, "c"), binding, Outcome.Closed))
    assertEquals((closed.country, closed.closed, closed.deposits("EUR").sum), (None, Some("self-exclusion"), 2_500L))
  }
