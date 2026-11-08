package io.eitheror.casino.programme.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.programme.commission.FactType

/** The topic as a channel (contracts/inbound.md *Intake*): once per fact, the brand's own topic only. */
class IntakeSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = scala.concurrent.duration.Duration(120, "s")
  private def kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = { kit.rewire(); kit.reset() }

  test("a fact published twice writes once and is counted as a duplicate") {
    val s      = Scene(kit)
    val p      = s.player()
    val before = Scene.brand(kit, "goldvibe").duplicates
    val ggr    = s.facts.ggrDaily(p, "2026-10-25", 1_000, 0, kit.now)
    kit.publish(ggr); kit.publish(ggr)
    assertEquals(s.outcome(ggr.factId), "ggr")
    kit.eventually("the duplicate counted")(Option(Scene.brand(kit, "goldvibe").duplicates).filter(_ > before))
    val rows = s.rowsWhen("the GGR")(rs => s.sum(rs, FactType.Ggr) == 1_000)
    assertEquals(rows.count(_.kind == FactType.Ggr), 1)
  }

  test("a fact on another brand's topic writes nothing and is counted against that topic's brand") {
    val s      = Scene(kit)
    val before = Scene.brand(kit, "silvervibe").mismatches
    val reg    = s.facts.registration(kit.fresh("ps"), kit.now)
    kit.publish(reg, Some("silvervibe"))
    kit.eventually("the mismatch counted")(Option(Scene.brand(kit, "silvervibe").mismatches).filter(_ > before))
    assertEquals(s.state(reg.pseudonym).binding, None)
  }

  test("a fact for a player with no attribution is recorded with its reason and writes no commission") {
    val s   = Scene(kit)
    val dep = s.facts.deposit(kit.fresh("ps"), 5_000, kit.now)
    assertEquals(s.send(dep), "unattributed:no_attribution")
    Thread.sleep(2_000)
    assertEquals(s.rows, Vector.empty)
  }
