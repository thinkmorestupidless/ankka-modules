package io.eitheror.casino.support.features

import com.thinkmorestupidless.ankka.agent.{ChatMessage, Json as AgentJson, forAgent}
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.agent.{Guardrails, Motoko}
import io.eitheror.casino.support.config.ConfigBody
import io.eitheror.casino.support.conversation.{EndReason, Status}
import io.eitheror.casino.support.domain.Softening
import io.eitheror.casino.support.it.SseReader

import scala.concurrent.duration.*

/**
 * features/support-agent/protections.feature (US3; T038). The break and the exclusion are responsible gambling's:
 * the service is asserted to ask it once under the approval's key, to end the conversation, and to put the
 * statements the summary must carry. Ending the player's game sessions is responsible gambling's own work (spec
 * 010 FR-016, FR-018), so "the player's sessions end" is asserted as the call that does it having been made. The
 * softening scenario's rejection is read from the audit (T048).
 */
class ProtectionsFeatures extends SupportFeatures("protections.feature"):

  private var until = ""
  private var decided: SseReader = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    until = ""
    decided = null

  private def rgPut(path: String) = kit.rg.calls.filter(c => c.method == "PUT" && c.path.endsWith(s"/$player$path"))

  private def askBreak(): Unit =
    until = kit.clock.instant().plusSeconds(7 * 86400L).toString
    kit.model.expectToolCall("apply_cool_off", AgentJson.obj("cool_off_until" -> AgentJson.str(until)), "call-b")

  private def askExclusion(): Unit = kit.model.expectToolCall("apply_self_exclusion", AgentJson.obj(), "call-x")

  private def shown(): Unit = stream = kit.chat(player, session, "please")

  private def summary = stream.frames().find(_.name == "confirm_action").map(_.field("summary")).getOrElse(fail("no approval request"))

  private def approveShown(reply: String): Unit =
    shown()
    assertEquals(stream.names(), Vector("confirm_action", "done"))
    kit.model.expectText(reply)
    decided = kit.resume(player, session, "approve")

  private def approval = kit.conversation(session).lastDecided.getOrElse(fail("nothing was decided"))

  // ── a break ───────────────────────────────────────────────────────────────

  Given("the player asks the support agent for a break until a date") { () => askBreak() }
  When("the approval request is shown") { () => shown() }
  Then("its summary states the end date and that the break cannot be shortened") { () =>
    assertEquals(summary, s"Apply a cool-off period until $until. This cannot be shortened once it is in effect.")
  }
  Then("it has no softening phrase") { () => assertEquals(Softening.refuses(Softening.Protective, summary), None) }

  Given("the player approved a break") { () => askBreak() }
  When("it is applied") { () =>
    if until.nonEmpty then approveShown(s"Your break is set until $until.")
    else approveShown("You are now self-excluded.")
  }
  Then("responsible gambling holds the break") { () =>
    decided.frames(): Unit
    val puts = rgPut("/cooling-off")
    assertEquals(puts.flatMap(_.json).map(j => (j.text("until"), j.text("key"))), Vector((Some(until), Some(approval))))
  }
  Then("the player's sessions end") { () =>
    decided.frames(): Unit
    val path = if until.nonEmpty then "/cooling-off" else "/permanent-exclusion"
    assertEquals(rgPut(path).size, 1, "responsible gambling, which ends the player's sessions, was asked once")
  }
  Then("the reply states when they may return") { () =>
    val result = decided.frames().head
    assertEquals((result.name, result.field("detail")), ("action_result", s"Cool-off in effect until $until"))
    val told = kit.model.requests.flatMap(_.messages).collect { case ChatMessage.ToolResults(rs) => rs }.flatten.map(_.content)
    assert(told.exists(_.contains(until)), told)
    assertEquals(kit.conversation(session).status, Status.Ended(EndReason.OnBreak(java.time.Instant.parse(until))))
  }

  // ── an exclusion ──────────────────────────────────────────────────────────

  Given("the player asks the support agent to self-exclude") { () => askExclusion() }
  Then("its summary says the exclusion is permanent and cannot be reversed") { () =>
    assertEquals(summary, "Self-exclude your account permanently. This is permanent and cannot be reversed.")
  }
  Then("exactly one confirmation is asked") { () =>
    assertEquals(stream.names().count(_ == "confirm_action"), 1)
    assertEquals(kit.components.forAgent(Motoko.session(session)).approvals().size, 1)
  }

  Given("the player approved self-exclusion") { () => askExclusion() }
  Then("the conversation ends with a closing reply") { () =>
    assert(decided.text().endsWith(ConfigBody().closingMessage), decided.text())
    assertEquals(kit.conversation(session).status, Status.Ended(EndReason.SelfExcluded))
  }
  Then("no further turn is accepted on it") { () =>
    val next = kit.chat(player, session, "hello?")
    assertEquals((next.status, next.refusal), (409, "conversation_ended"))
    assertEquals(rgPut("/permanent-exclusion").flatMap(_.json).flatMap(_.text("key")), Vector(approval))
  }

  // ── softening ─────────────────────────────────────────────────────────────

  private val Clean = "Your break has started and lasts 24 hours. It cannot be shortened."

  Given("the model's summary softens an irreversible control") { () =>
    kit.model.expectText("Your break has started, but don't worry, you can lift it early.")
    kit.model.expectText(Clean)
  }
  When("the output guardrail checks it") { () =>
    stream = kit.chat(player, session, "I need a break")
    stream.frames(): Unit
  }
  Then("the reply is rejected naming the guardrail") { () =>
    val r = kit.eventually("the softening rejection's record", within = 1.minute)(kit.audits.find(r =>
      r.correlationId == session && r.action == "support.guardrail.rejected" && r.reasonCode.contains(Guardrails.SofteningName)))
    assertEquals(r.changes.find(_.field == "side").flatMap(_.after), Some("output"))
  }
  Then("the model is asked again") { () =>
    assertEquals(kit.model.callCount, 2)
    assertEquals(stream.text(), Clean)
    assert(!stream.body.contains("don't worry"), "the rejected reply reached the player")
  }

