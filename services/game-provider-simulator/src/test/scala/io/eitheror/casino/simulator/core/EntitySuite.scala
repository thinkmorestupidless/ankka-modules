package io.eitheror.casino.simulator.core

import com.thinkmorestupidless.ankka.core.Done
import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, KeyValueEntityTestKit}
import io.eitheror.casino.simulator.st8.{Bonus, BonusBook, Instance}

/** The entities with no runtime (research R5): their rules, their idempotency, their folds. */
class EntitySuite extends munit.FunSuite:

  private val launch = SessionEntity.Launch("goldvibe", "p1", "EUR", "book-of-ra", "default", "L1", 42L, 1000L)

  test("a scenario chosen before launch stands over the brand's default") {
    val kit = EventSourcedTestKit.of(SessionEntity, "L1")
    assertEquals(kit.call(SessionEntity.selectScenario)(SessionEntity.Selection("slow-operator", None)).replyValue, Done)
    kit.call(SessionEntity.launch)(launch): Unit
    assertEquals(kit.currentState.scenario, "slow-operator")
  }

  test("a second selection is refused; the first stands") {
    val kit = EventSourcedTestKit.of(SessionEntity, "L1")
    kit.call(SessionEntity.selectScenario)(SessionEntity.Selection("slow-operator", None)): Unit
    val second = kit.call(SessionEntity.selectScenario)(SessionEntity.Selection("retry-storm", None))
    assert(second.isError)
    assertEquals(second.events, Vector.empty)
    assertEquals(kit.currentState.scenario, "slow-operator")
  }

  test("a selection after the game page's check is refused") {
    val kit = EventSourcedTestKit.of(SessionEntity, "L1")
    kit.call(SessionEntity.launch)(launch): Unit
    kit.call(SessionEntity.check)("W1"): Unit
    assert(kit.call(SessionEntity.selectScenario)(SessionEntity.Selection("slow-operator", None)).isError)
  }

  test("planned callbacks move rounds: a stake opens, a win or loss settles, a cancel cancels") {
    val kit = EventSourcedTestKit.of(SessionEntity, "L1")
    kit.call(SessionEntity.launch)(launch): Unit
    def plan(id: String, role: RoundRole, round: String) =
      kit.call(SessionEntity.plan)(SessionEvent.CallbackPlanned(id, "k", Some(round), s"t-$id", None, role.toString, free = false, None)): Unit
    plan("1", RoundRole.Stake, "r1")
    assertEquals(kit.currentState.openRounds.map(_.roundId), Vector("r1"))
    plan("2", RoundRole.Loss, "r1")
    assertEquals(kit.currentState.rounds("r1").state, Round.Lost)
    plan("3", RoundRole.Stake, "r2")
    plan("4", RoundRole.Cancel, "r2")
    assertEquals(kit.currentState.rounds("r2").state, Round.Cancelled)
    assertEquals(kit.currentState.openRounds, Vector.empty)
  }

  test("nothing is planned on a session that was never launched") {
    val kit = EventSourcedTestKit.of(SessionEntity, "L9")
    assert(kit.call(SessionEntity.plan)(SessionEvent.CallbackPlanned("1", "k", None, "t", None, "Outside", false, None)).isError)
  }

  test("an attempt recorded twice is recorded once") {
    val kit = EventSourcedTestKit.of(CallbackEntity, "c1")
    kit.call(CallbackEntity.schedule)(CallbackEvent.Scheduled("L1", "L1", "goldvibe", "debit", "/st8/debit", "{}", None, 1L)): Unit
    val attempt = Attempt(2, "k1", "sig", 10L, 5L, Some(200), Some("ok"), Some("1.00"), Some("EUR"), Some("{}"), None)
    assertEquals(kit.call(CallbackEntity.record)(attempt).events.size, 1)
    assertEquals(kit.call(CallbackEntity.record)(attempt).events.size, 0)
    assertEquals(kit.currentState.attempts.map(_.attempt), Vector(2))
  }

  test("a discarded callback is deleted after the view is told") {
    val kit = EventSourcedTestKit.of(CallbackEntity, "c1")
    kit.call(CallbackEntity.schedule)(CallbackEvent.Scheduled("L1", "L1", "goldvibe", "debit", "/st8/debit", "{}", None, 1L)): Unit
    val result = kit.call(CallbackEntity.discard)
    assertEquals(result.events, Vector(CallbackEvent.Discarded))
  }

  test("the default scenario is not replaced by an operator; a shipped seed only moves forward") {
    val kit = KeyValueEntityTestKit.of(ScenarioEntity, "default")
    assert(kit.call(ScenarioEntity.put)(StoredScenario("default", 9, "{}", shipped = false)).isError)
    assertEquals(kit.call(ScenarioEntity.seed)(StoredScenario("default", 2, "{}", shipped = true)).replyValue, true)
    assertEquals(kit.call(ScenarioEntity.seed)(StoredScenario("default", 1, "{}", shipped = true)).replyValue, false)
  }

  test("a free spin takes the lowest bonus with an active instance for the player's game, and is used at zero") {
    val kit = KeyValueEntityTestKit.of(BonusBook, "book")
    def bonus(id: String, games: String*) = Bonus(id, Vector("p1"), 1, "0.10", "free_bets", "", games.toVector, "s", Map("p1" -> Instance("active", 1)))
    kit.call(BonusBook.createBonus)(bonus("b2", "book-of-ra")): Unit
    kit.call(BonusBook.createBonus)(bonus("b1", "book-of-ra")): Unit
    kit.call(BonusBook.createBonus)(bonus("b0", "fake-slot-002")): Unit
    val first = kit.call(BonusBook.spend)(BonusBook.Spend("p1", "book-of-ra", "EUR")).replyValue
    assertEquals((first.bonusId, first.remaining, first.currency), ("b1", 0, "EUR"))
    assertEquals(kit.call(BonusBook.get)("b1").replyValue.instances("p1").status, "used")
    assertEquals(kit.call(BonusBook.spend)(BonusBook.Spend("p1", "book-of-ra", "EUR")).replyValue.bonusId, "b2")
    assert(kit.call(BonusBook.spend)(BonusBook.Spend("p1", "book-of-ra", "EUR")).isError)
  }

  test("a cancelled instance is not spent") {
    val kit = KeyValueEntityTestKit.of(BonusBook, "book")
    kit.call(BonusBook.createBonus)(Bonus("b1", Vector("p1"), 3, "0.10", "free_bets", "EUR", Vector("book-of-ra"), "s", Map("p1" -> Instance("active", 3)))): Unit
    assertEquals(kit.call(BonusBook.cancel)(BonusBook.Cancel("b1", "p1")).replyValue.instances("p1").status, "canceled")
    assert(kit.call(BonusBook.spend)(BonusBook.Spend("p1", "book-of-ra", "EUR")).isError)
  }
