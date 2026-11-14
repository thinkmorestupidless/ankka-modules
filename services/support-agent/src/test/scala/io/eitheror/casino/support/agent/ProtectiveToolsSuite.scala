package io.eitheror.casino.support.agent

import com.thinkmorestupidless.ankka.agent.Json as AgentJson
import io.eitheror.casino.support.clients.Called
import io.eitheror.casino.support.config.ConfigBody
import io.eitheror.casino.support.conversation.{EndReason, Outcome}

import java.time.Instant
import scala.concurrent.duration.*

/**
 * `apply_cool_off` and `apply_self_exclusion` (contracts/tools.md, neighbours.md R3 and R4; T036), ported from the
 * original's `test_rg_tools.py`: each waits for one approval, runs once under its key, and ends the conversation;
 * cancelled or not approved, it calls nothing. The approval itself is ankka's; here the trail says whether it was given.
 */
class ProtectiveToolsSuite extends munit.FunSuite:
  private val at    = Instant.parse("2026-11-10T10:00:00Z")
  private val until = "2026-12-01T00:00:00Z"

  private def setUp() =
    val r = Recorded()
    r.ok("status", """{"status":"ACTIVE","displayName":"Ann","currency":"GBP"}""")
    (r, Tools(r.neighbours, r.trail, "p-1", ConfigBody(approvalMinutes = 15), () => at))

  private def run(t: Tools, name: String, args: String = "{}") =
    t.writes.find(_.name == name).getOrElse(fail(s"no write tool $name")).invoke(AgentJson.parse(args).toOption.get)

  private def rgCalls(r: Recorded) = r.calls.filter(c => c.startsWith("rg coolOff") || c.startsWith("rg exclude")).toVector

  test("each waits for one approval within the brand's time limit, as the original's single confirmation"):
    val (_, t) = setUp()
    Seq("apply_cool_off", "apply_self_exclusion").foreach { name =>
      val tool = t.writes.find(_.name == name).getOrElse(fail(name))
      assertEquals(tool.approval.flatMap(_.within), Some(15.minutes), name)
    }

  test("an approved break runs once under the approval's key, reports its end, and ends the conversation until then"):
    val (r, t) = setUp()
    r.approvedId = Some("ap-1")
    r.ok("coolOff", s"""{"status":"cooling_off","coolingOffUntil":"$until"}""")
    assertEquals(run(t, "apply_cool_off", s"""{"cool_off_until":"$until"}"""), Right(s"Cool-off in effect until $until"))
    assertEquals(rgCalls(r), Vector(s"rg coolOff p-1 $until ap-1"))
    assertEquals(r.ends.toVector, Vector(EndReason.OnBreak(Instant.parse(until))))
    assertEquals(r.toolCalls.last.approvalId, Some("ap-1"))

  test("an approved self-exclusion runs once under the approval's key and ends the conversation for good"):
    val (r, t) = setUp()
    r.approvedId = Some("ap-2")
    r.ok("exclude", """{"status":"self_excluded"}""")
    assertEquals(run(t, "apply_self_exclusion"), Right(s"Self-exclusion applied at $at"))
    assertEquals(rgCalls(r), Vector("rg exclude p-1 ap-2"))
    assertEquals(r.ends.toVector, Vector(EndReason.SelfExcluded))

  test("not approved (cancelled, or decided by no one) calls nothing and ends nothing"):
    val (r, t) = setUp()
    assert(run(t, "apply_cool_off", s"""{"cool_off_until":"$until"}""").isLeft)
    assert(run(t, "apply_self_exclusion").isLeft)
    assertEquals((rgCalls(r), r.ends.toVector), (Vector.empty, Vector.empty))

  test("a break responsible gambling refuses is the refusal's reason, and the conversation goes on"):
    val (r, t) = setUp()
    r.approvedId = Some("ap-3")
    r.scripted.update("coolOff", Called.Answered(409, """{"error":"break_active","message":"A break is already in effect"}"""))
    val result = run(t, "apply_cool_off", s"""{"cool_off_until":"$until"}""")
    assert(result.left.exists(_.contains("break_active")), result)
    assertEquals(r.ends.toVector, Vector.empty)
    assertEquals(r.toolCalls.last.outcome, Outcome.Refused("break_active"))

  test("a break ending before now is refused before any call (FR-011)"):
    val (r, t) = setUp()
    r.approvedId = Some("ap-4")
    assert(run(t, "apply_cool_off", """{"cool_off_until":"2026-01-01T00:00:00Z"}""").isLeft)
    assertEquals(rgCalls(r), Vector.empty)
