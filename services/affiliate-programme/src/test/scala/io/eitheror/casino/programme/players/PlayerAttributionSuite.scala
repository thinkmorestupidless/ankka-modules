package io.eitheror.casino.programme.players

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.contract.facts as contract
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}

import java.time.Instant

class PlayerAttributionSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-11-01T10:00:00Z")
  private var n  = 0
  private def fact(kind: String, amount: Long = 0, ref: Option[String] = None, currency: String = "EUR", affiliate: String = "aff-1",
      before: Boolean = false) =
    n += 1
    PlayerFact(kind, s"f-$n", at.plusSeconds(n.toLong), "goldvibe", "ps-1", affiliate, "lnk-1", Some("summer"), Vector("s1"),
      Some("c-1"), Some("MT"), Some(currency), amount, ref, Some("2026-11-01"), before, Some("self-exclusion"))
  private def kit = EventSourcedTestKit.of(PlayerAttribution, "goldvibe/ps-1")
  private def bound =
    val k = kit
    k.call(PlayerAttribution.applyFact)(ApplyFact(fact(PlayerFact.Registration), "daily", None)): Unit
    k
  private def apply(k: EventSourcedTestKit[PlayerAttribution, PlayerState, PlayerAttribution.Event], f: PlayerFact, source: String = "daily", min: Option[Long] = Some(2000)) =
    k.call(PlayerAttribution.applyFact)(ApplyFact(f, source, min))
  private def state(k: EventSourcedTestKit[PlayerAttribution, PlayerState, PlayerAttribution.Event]) = k.call(PlayerAttribution.get).replyValue

  test("a registration binds once with link, campaign, sub-ids, click and country; a second is a duplicate") {
    val k = bound
    val s = state(k)
    assertEquals(s.binding.map(b => (b.affiliate, b.link, b.campaign, b.subIds, b.clickId)), Some(("aff-1", "lnk-1", Some("summer"), Vector("s1"), Some("c-1"))))
    assertEquals(s.country, Some("MT"))
    val again = apply(k, fact(PlayerFact.Registration, affiliate = "aff-2"))
    assertEquals(again.replyValue, Outcome.Duplicate)
    assert(!again.persisted)
  }

  test("a fact id seen before is a duplicate and writes nothing") {
    val k = bound
    val f = fact(PlayerFact.Deposit, 500)
    apply(k, f): Unit
    assertEquals(apply(k, f).replyValue, Outcome.Duplicate)
    assertEquals(state(k).deposits("EUR").count, 1)
  }

  test("every kind on an unbound player is unattributed") {
    assertEquals(apply(kit, fact(PlayerFact.FirstDeposit, 5000)).replyValue, Outcome.Unattributed("no_attribution"))
  }

  test("a first deposit at the minimum qualifies once, for life; a later one does not") {
    val k = bound
    assertEquals(apply(k, fact(PlayerFact.FirstDeposit, 2000, currency = "USD")).replyValue, Outcome.FirstDeposit(true))
    assertEquals(apply(k, fact(PlayerFact.Deposit, 2000, currency = "USD")).replyValue, Outcome.Deposit)
    assertEquals(apply(k, fact(PlayerFact.Deposit, 9000, currency = "USD")).replyValue, Outcome.Deposit)
    assertEquals(apply(k, fact(PlayerFact.FirstDeposit, 9000)).replyValue, Outcome.FirstDeposit(false))
    val s = state(k)
    assertEquals((s.deposits("USD").count, s.deposits("USD").sum, s.deposits("USD").second.map(_.amount)), (2, 11_000L, Some(9000L)))
    assert(s.guard.isDefined)
  }

  test("a first deposit's two facts count it once: the deposit fact counts, the first-deposit fact does not") {
    val k = bound
    apply(k, fact(PlayerFact.FirstDeposit, 2000)): Unit
    apply(k, fact(PlayerFact.Deposit, 2000)): Unit
    assertEquals((state(k).deposits("EUR").count, state(k).deposits("EUR").sum), (1, 2000L))
  }

  test("a first deposit below the minimum leaves the guard unset, so a later deposit at the minimum qualifies") {
    val k = bound
    assertEquals(apply(k, fact(PlayerFact.FirstDeposit, 1000)).replyValue, Outcome.FirstDeposit(false))
    assert(state(k).guard.isEmpty)
    assertEquals(apply(k, fact(PlayerFact.Deposit, 2500)).replyValue, Outcome.FirstDeposit(true))
    assertEquals(apply(k, fact(PlayerFact.Deposit, 2500)).replyValue, Outcome.Deposit)
  }

  test("a plan that pays no CPA never qualifies") {
    assertEquals(apply(bound, fact(PlayerFact.FirstDeposit, 9000), min = None).replyValue, Outcome.FirstDeposit(false))
  }

  test("GGR folds only from the brand's source; the other kind is recorded as other_source") {
    val k = bound
    assertEquals(apply(k, fact(PlayerFact.GgrDaily, 700)).replyValue, Outcome.Ngr("ggr", 700))
    assertEquals(apply(k, fact(PlayerFact.GgrAdjustment, -50)).replyValue, Outcome.Ngr("ggr", -50))
    assertEquals(apply(k, fact(PlayerFact.GgrRound, 30)).replyValue, Outcome.Ignored("other_source"))
    assertEquals(apply(k, fact(PlayerFact.GgrRound, 30), source = "rounds").replyValue, Outcome.Ngr("ggr", 30))
    assertEquals(apply(k, fact(PlayerFact.GgrDaily, 30), source = "rounds").replyValue, Outcome.Ignored("other_source"))
  }

  test("a GGR day before the binding is recorded and folds nothing") {
    val k = bound
    assertEquals(apply(k, fact(PlayerFact.GgrDaily, 400, before = true)).replyValue, Outcome.BeforeBinding)
    assertEquals(state(k).beforeBinding, Vector(EarlyDay("2026-11-01", "EUR", 400)))
  }

  test("bonus cost and chargeback are NGR inputs and count toward the fraud flags") {
    val k = bound
    assertEquals(apply(k, fact(PlayerFact.BonusCost, 300)).replyValue, Outcome.Ngr("bonus-cost", 300))
    assertEquals(apply(k, fact(PlayerFact.Chargeback, 100)).replyValue, Outcome.Ngr("chargeback", 100))
    assertEquals(state(k).fraud, Fraud(1, 1))
  }

  test("withdrawals: completed raises the total, reversal lowers it never below zero, only for a kept reference") {
    val k = bound
    assertEquals(apply(k, fact(PlayerFact.WithdrawalReversed, 100, Some("w-0"))).replyValue, Outcome.Ignored("no_completed_withdrawal"))
    apply(k, fact(PlayerFact.WithdrawalCompleted, 1000, Some("w-1"))): Unit
    assertEquals(state(k).withdrawals("EUR"), 1000L)
    assertEquals(apply(k, fact(PlayerFact.WithdrawalReversed, 1500, Some("w-1"))).replyValue, Outcome.WithdrawalTotal)
    assertEquals(state(k).withdrawals("EUR"), 0L)
    assertEquals(apply(k, fact(PlayerFact.WithdrawalReversed, 1, Some("w-1"))).replyValue, Outcome.Ignored("no_completed_withdrawal"))
  }

  test("a withdrawal requested or failed changes no total") {
    val k = bound
    assertEquals(apply(k, fact(PlayerFact.WithdrawalRequested, 100, Some("w-1"))).replyValue, Outcome.Ignored("no_total_changes"))
    assertEquals(apply(k, fact(PlayerFact.WithdrawalFailed, 100, Some("w-1"))).replyValue, Outcome.Ignored("no_total_changes"))
    assertEquals(state(k).withdrawals, Map.empty[String, Long])
  }

  test("player-closed forgets country and click and keeps the amounts") {
    val k = bound
    apply(k, fact(PlayerFact.Deposit, 500)): Unit
    assertEquals(apply(k, fact(PlayerFact.PlayerClosed)).replyValue, Outcome.Closed)
    val s = state(k)
    assertEquals((s.country, s.clickId, s.deposits("EUR").sum, s.closed.map(_.category)), (None, None, 500L, Some("self-exclusion")))
  }

  test("a fact naming another affiliate applies to the binding and is kept as a disagreement") {
    val k = bound
    val r = apply(k, fact(PlayerFact.Deposit, 500, affiliate = "aff-9"))
    assertEquals(r.events.collect { case e: PlayerAttribution.Applied => e.binding.map(_.affiliate) }, Vector(Option("aff-1")))
    assertEquals(state(k).disagreements.map(_.affiliate), Vector("aff-9"))
  }

  test("the contract's facts flatten; a click is not a player's") {
    val dep = contract.FirstDeposit(1, "f", at, "goldvibe", "programme", "aff-1", "lnk-1", None, Vector.empty, None, "ps-1",
      Currency.unsafe("EUR"), MinorUnits.unsafe(2000), "r")
    assertEquals(PlayerFact.from(dep).map(p => (p.kind, p.amount, p.currency)), Some(("first-deposit", 2000L, Some("EUR"))))
    val ggr = contract.GgrDaily(1, "g", at, "goldvibe", "programme", "aff-1", "lnk-1", None, Vector.empty, None, "ps-1",
      Currency.unsafe("EUR"), "2026-11-01", MinorUnits.unsafe(10), MinorUnits.unsafe(30), SignedMinorUnits(-20), "r", Some(true))
    assertEquals(PlayerFact.from(ggr).map(p => (p.amount, p.beforeBinding)), Some((-20L, true)))
    val click = contract.Click(1, "c", at, "goldvibe", "programme", "aff-1", "lnk-1", None, Vector.empty, None, None, None)
    assertEquals(PlayerFact.from(click), None)
  }
