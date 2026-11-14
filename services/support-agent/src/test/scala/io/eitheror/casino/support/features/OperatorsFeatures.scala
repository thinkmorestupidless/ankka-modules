package io.eitheror.casino.support.features

import com.thinkmorestupidless.ankka.agent.Json as AgentJson
import io.eitheror.casino.contract.facts.AuditRecord
import io.eitheror.casino.support.Json
import io.eitheror.casino.support.config.ConfigBody
import io.eitheror.casino.support.it.{Kit, Reply}

import scala.concurrent.duration.*

/**
 * features/support-agent/operators.feature (US6; T052), through the operator's routes as a brand admin. The player
 * site hides the chat on the chat route's 503 `agent_unavailable` (spec 019), so "the chat stays hidden" is that
 * answer with no stream opened. An audit record is read from the in-memory broker.
 */
class OperatorsFeatures extends SupportFeatures("operators.feature"):

  private var answer: Reply = null
  private var oldSession    = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    answer = null
    oldSession = ""

  private def put(body: String): Reply =
    kit.request("PUT", s"/v1/casino/agent-config/${kit.slug}/", Some(body), kit.asOperator())
  private def shown = Json.parse(kit.request("GET", s"/v1/casino/agent-config/${kit.slug}/", headers = kit.asOperator()).text).get
  private def prompt = kit.model.lastRequest.systemMessage.getOrElse("")

  // ── off ───────────────────────────────────────────────────────────────────

  Given("the brand's support agent is off") { () => kit.configure(_.copy(enabled = false)): Unit }
  When("a player opens the chat") { () => stream = kit.chat(player, session, "hello") }
  Then("the answer is that the support agent is not available for the brand") { () =>
    assertEquals((stream.status, stream.refusal), (503, "agent_unavailable"))
    assertEquals(Json.parse(stream.body).map(_.get("details").get("message").asText), Some(ConfigBody().unavailableMessage))
  }
  Then("the chat stays hidden") { () =>
    assert(!stream.body.contains("event:"), "a stream was opened")
    assertEquals(kit.model.callCount, 0)
  }

  // ── safeguarding ──────────────────────────────────────────────────────────

  Given("the brand takes real money and has no safeguarding reply configured") { () =>
    kit.reset(Kit.Base.copy(realMoney = true))
    kit.configure(_.copy(enabled = false)): Unit
  }
  When("an operator turns its support agent on") { () => answer = put("""{"enabled": true}""") }
  Then("it is refused naming the missing safeguarding reply and helpline") { () =>
    assertEquals((answer.status, answer.str("error")), (422, "safeguarding_not_configured"))
    assert(answer.str("message").contains("safeguarding message and a helpline"), answer.text)
  }
  Given("the brand takes real money and has a safeguarding reply and helpline but no safeguarding judge") { () =>
    kit.reset(Kit.Base.copy(realMoney = true, judge = None))
    kit.configure(_.copy(enabled = false, safeguardingMessage = Some("Please talk to someone."), helpline = Some("0800 000 000"))): Unit
  }
  Then("it is refused naming the missing safeguarding judge") { () =>
    assertEquals((answer.status, answer.str("error")), (422, "safeguarding_not_configured"))
    assert(answer.str("message").contains("judgment provider"), answer.text)
  }
  Then("the support agent stays off") { () => assertEquals(shown.get("config").get("enabled").asBoolean, false) }

  // ── versions ──────────────────────────────────────────────────────────────

  Given("an operator sets a new prompt version") { () =>
    kit.configure(): Unit
    oldSession = s"$session-old"
    kit.model.expectText("one")
    assertEquals(kit.chat(player, oldSession, "hello").names().last, "done")
    assertEquals(put("""{"prompt": "You are Motoko, the new version."}""").status, 200)
  }
  When("the next conversation starts") { () =>
    kit.model.expectText("two")
    assertEquals(kit.chat(player, session, "hello").names().last, "done")
  }
  Then("its prompt is the new version") { () => assert(prompt.startsWith("You are Motoko, the new version."), prompt) }
  Then("conversations in progress keep theirs") { () =>
    kit.model.expectText("three")
    assertEquals(kit.chat(player, oldSession, "again").names().last, "done")
    assert(prompt.startsWith("You are Motoko") && !prompt.contains("the new version"), prompt)
  }

  // ── the audit ─────────────────────────────────────────────────────────────

  private var expected: AuditRecord => Boolean = _ => false

  Given("the brand's support agent is on") { () => kit.configure(): Unit }

  private def withdrawal = AgentJson.obj("amount" -> AgentJson.num(5000d), "currency" -> AgentJson.str("GBP"))

  When("a tool is called") { () =>
    kit.model.expectToolCall("get_wallet_balance", AgentJson.obj(), "call-r").expectText("You have £10.")
    assertEquals(kit.chat(player, session, "my balance?").names().last, "done")
    expected = r => r.action == "support.tool.get_wallet_balance" && r.outcome == "ok"
  }
  When("an approval request is decided") { () =>
    kit.model.expectToolCall("request_withdrawal", withdrawal, "call-w")
    assertEquals(kit.chat(player, session, "withdraw £50").names(), Vector("confirm_action", "done"))
    kit.model.expectText("Nothing was withdrawn.")
    assertEquals(kit.resume(player, session, "reject").names().last, "done")
    expected = r => r.action == "support.approval.decided" && r.outcome == "refused" &&
      r.changes.exists(c => c.field == "tool" && c.after.contains("request_withdrawal"))
  }
  When("a guardrail rejects a turn") { () =>
    kit.chat(player, session, "what does someone@example.com have?").names(): Unit
    expected = r => r.action == "support.guardrail.rejected" && r.reasonCode.contains("other-player") && r.outcome == "refused"
  }
  Then("an audit record names the session, the tool or guardrail, the redacted arguments and the outcome") { () =>
    val r = kit.eventually("the audit record", within = 1.minute)(kit.audits.find(r => r.correlationId == session && expected(r)))
    assertEquals((r.subjectKind, r.subjectId), ("player", player))
    assert(!kit.audits.filter(_.correlationId == session).exists(_.changes.exists(_.after.exists(_.contains("@")))),
      "an audit record carries the email address")
  }
