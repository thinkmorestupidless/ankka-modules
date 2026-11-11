package io.eitheror.casino.support.it

import com.thinkmorestupidless.ankka.agent.{ModelResponse, TokenUsage}
import io.eitheror.casino.support.Json
import io.eitheror.casino.support.config.ConfigBody
import io.eitheror.casino.support.limits.{AddTokens, Budget}

import scala.concurrent.duration.*

/**
 * The chat route's limits (T045; FR-024, contracts/routes.md): a player's turns per minute, refused as 429 with when to
 * send again before any model call; each turn's tokens added to the brand's day; and a day whose budget is spent
 * answered with the unavailable message as one `delta`, then `done`.
 */
class LimitsRouteSuite extends munit.FunSuite:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    kit.configure(): Unit

  private def budget = kit.components.forKeyValueEntity(Budget.id(kit.slug, kit.clock.instant())).call(Budget.get).invoke()

  test("past the brand's turns per minute the next turn is 429 too_many_turns with retryAfter, and no model is called"):
    val limit = ConfigBody().turnsPerMinute
    kit.configure(_.copy(turnsPerMinute = limit)): Unit
    (1 to limit).foreach { i =>
      kit.model.expectText(s"answer $i")
      assertEquals(kit.chat("p-rate", s"rate-$i", "hello").names().last, "done")
    }
    kit.clock.moveBy(20.seconds)
    val refused = kit.chat("p-rate", "rate-next", "hello")
    assertEquals((refused.status, refused.refusal), (429, "too_many_turns"))
    assertEquals(Json.parse(refused.body).map(_.get("details").get("retryAfter").asText), Some("40"))
    assertEquals(kit.model.callCount, limit)
    kit.clock.moveBy(41.seconds)
    kit.model.expectText("again")
    assertEquals(kit.chat("p-rate", "rate-next", "hello").names().last, "done")

  test("each turn's tokens are added to the day's budget once, as the session's increase"):
    kit.model.expect(ModelResponse("one", usage = TokenUsage(inputTokens = 100, outputTokens = 50)))
    assertEquals(kit.chat("p-budget", "budget-s", "hello").names().last, "done")
    val first = budget.counted.get("budget-s")
    kit.model.expect(ModelResponse("two", usage = TokenUsage(inputTokens = 20, outputTokens = 10)))
    assertEquals(kit.chat("p-budget", "budget-s", "again").names().last, "done")
    assertEquals((first, budget.counted.get("budget-s")), (Some(150L), Some(180L)))
    assertEquals(kit.conversation("budget-s").tokensCounted, 180L)

  test("a day whose budget is spent answers the unavailable message as one delta, then done, and calls no model"):
    val limit = ConfigBody().dailyTokens
    kit.components.forKeyValueEntity(Budget.id(kit.slug, kit.clock.instant())).call(Budget.add)
      .invoke(AddTokens("budget-filler", 0L, limit)): Unit
    try
      val s = kit.chat("p-spent", "budget-spent", "hello")
      assertEquals(s.status, 200)
      assertEquals(s.frames().map(f => (f.name, f.field("token"))),
        Vector(("delta", ConfigBody().unavailableMessage), ("done", "")))
      assertEquals(kit.model.callCount, 0)
      assert(!kit.conversation("budget-spent").started, "a refused turn binds no session")
    // The next suites run on a day with nothing spent.
    finally kit.clock.moveBy(1.day)
