package io.eitheror.casino.support.features

import com.thinkmorestupidless.ankka.agent.{ChatMessage, Json as AgentJson, forAgent}
import io.eitheror.casino.support.Json
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.agent.Motoko
import io.eitheror.casino.support.it.{Answer, SseReader}

import scala.concurrent.duration.*

/**
 * features/support-agent/actions.feature (US2; T034). The model is scripted to call a write; what is asserted is
 * what the service did with it: the approval request it put to the player, what it asked of the owning part and
 * under which key, and the frames the decision streamed.
 */
class ActionsFeatures extends SupportFeatures("actions.feature"):

  private var approval = ""
  private var decided: SseReader = null

  private def withdrawal(amount: Long = 5000L) =
    AgentJson.obj("amount" -> AgentJson.num(amount.toDouble), "currency" -> AgentJson.str("GBP"))

  private def withdrawals = kit.payouts.calls.filter(_.path.endsWith(s"/players/$player/withdrawal-requests"))

  private def await(): Unit =
    kit.model.expectToolCall("request_withdrawal", withdrawal(), "call-w")
    stream = kit.chat(player, session, "withdraw £50")
    assertEquals(stream.names(), Vector("confirm_action", "done"))
    approval = kit.conversation(session).awaiting.map(_._1).getOrElse(fail("the conversation awaits no approval"))

  private def toldModel: Seq[String] =
    kit.model.requests.flatMap(_.messages).collect { case ChatMessage.ToolResults(rs) => rs }.flatten.map(_.content)

  // ── asked for ─────────────────────────────────────────────────────────────

  Given("the player asks the support agent to withdraw {string}") { (_: String) =>
    kit.model.expectToolCall("request_withdrawal", withdrawal(), "call-w")
  }
  When("the model calls the withdrawal tool") { () => stream = kit.chat(player, session, "withdraw £50") }
  Then("the turn ends with an approval request stating {string} minor units of {string} and the summary the player sees") {
    (minor: String, currency: String) =>
      val frames = stream.frames()
      assertEquals(frames.map(_.name), Vector("confirm_action", "done"))
      assertEquals((frames.head.field("action_name"), frames.head.field("summary")),
        ("request_withdrawal", s"Withdraw 50.00 $currency from your account"))
      val pending = kit.components.forAgent(Motoko.session(session)).approvals()
      assertEquals(pending.map(_.tool), Vector("request_withdrawal"))
      val args = Json.parse(pending.head.arguments.render).getOrElse(fail("the approval request has no arguments"))
      assertEquals((args.long("amount"), args.text("currency")), (Some(minor.toLong), Some(currency)))
      assertEquals(kit.conversation(session).awaiting.map(_._1), Some(pending.head.id))
  }
  Then("nothing has been requested of the brand's withdrawals") { () => assertEquals(withdrawals, Vector.empty) }

  // ── approved ──────────────────────────────────────────────────────────────

  Given("an approval request for a withdrawal awaiting the player's decision") { () => await() }
  When("the player approves it") { () =>
    kit.model.expectText("Your withdrawal is submitted.")
    decided = kit.resume(player, session, "approve")
  }
  Then("the brand's withdrawals receive one withdrawal request keyed by the approval request's id") { () =>
    decided.frames(): Unit
    assertEquals(withdrawals.map(kit.payouts.key), Vector(Some(approval)))
    assertEquals(withdrawals.flatMap(_.json).map(j => (j.long("amount"), j.text("currency"))), Vector((Some(5000L), Some("GBP"))))
  }
  Then("the reply carries the request's reference") { () =>
    val result = decided.frames().head
    assertEquals((result.name, result.field("success"), result.field("detail")), ("action_result", "true", "wr-1 submitted"))
    assert(toldModel.exists(_.contains("wr-1")), toldModel)
  }

  // ── cancelled ─────────────────────────────────────────────────────────────

  When("the player cancels it") { () =>
    kit.model.expectText("No problem, nothing was withdrawn.")
    decided = kit.resume(player, session, "reject", Some("changed my mind"))
  }
  Then("nothing is requested") { () =>
    decided.frames(): Unit
    assertEquals(withdrawals, Vector.empty)
  }
  Then("the model is told the player cancelled") { () => assert(toldModel.exists(_.contains("changed my mind")), toldModel) }
  Then("the reply acknowledges it") { () =>
    assertEquals(decided.names().distinct, Vector("delta", "done"))
    assertEquals(decided.text(), "No problem, nothing was withdrawn.")
  }

  // ── a turn while it awaits ────────────────────────────────────────────────

  When("the player sends a new turn instead") { () => stream = kit.chat(player, session, "hello?") }
  Then("it is refused as a conflict naming the pending approval request") { () =>
    assertEquals((stream.status, stream.refusal), (409, "Session already active"))
    val details = Json.parse(stream.body).flatMap(n => Option(n.get("details")))
    assertEquals((details.flatMap(_.text("approvalId")), details.flatMap(_.text("action_name"))),
      (Some(approval), Some("request_withdrawal")))
  }
  Then("the approval request still awaits") { () => assertEquals(kit.conversation(session).awaiting.map(_._1), Some(approval)) }

  // ── refused by the owning part ────────────────────────────────────────────

  Given("the player approved a withdrawal") { () => await() }
  When("the brand's withdrawals refuse it for insufficient funds or a gate") { () =>
    kit.payouts.answer("POST", s"/players/$player/withdrawal-requests",
      Answer(422, """{"error":"INSUFFICIENT_FUNDS","message":"The balance does not cover it"}"""))
    kit.model.expectText("Your balance does not cover that withdrawal.")
    decided = kit.resume(player, session, "approve")
  }
  Then("the tool's result is the refusal's reason") { () =>
    val result = decided.frames().head
    assertEquals((result.name, result.field("success")), ("action_result", "false"))
    assert(result.field("detail").contains("INSUFFICIENT_FUNDS"), result.data)
    assert(toldModel.exists(_.contains("INSUFFICIENT_FUNDS")), toldModel)
  }
  Then("the reply explains it") { () => assertEquals(decided.text(), "Your balance does not cover that withdrawal.") }
  Then("no second request is made") { () => assertEquals(withdrawals.size, 1) }

  // ── a restart ─────────────────────────────────────────────────────────────

  Given("the player approved an action") { () =>
    await()
    // Under the kit's 1 s neighbour timeout, so the one call is not retried.
    kit.payouts.delayMillis = 800L
    decided = kit.resume(player, session, "approve")
  }
  When("the support agent restarts between the decision and the tool's run") { () =>
    kit.eventually("the request reached payouts")(Option(withdrawals).filter(_.nonEmpty))
    kit.restartService()
    kit.payouts.delayMillis = 0L
  }
  Then("the turn ends") { () => decided.frames(30.seconds): Unit }
  Then("the tool is not run again") { () =>
    Thread.sleep(2000)
    assertEquals(withdrawals.map(kit.payouts.key), Vector(Some(approval)))
  }
  Then("the player sees the turn ended and may ask again") { () =>
    kit.model.expectText("Hello again.")
    val next = kit.chat(player, session, "is it done?")
    assertEquals((next.status, next.names().last), (200, "done"))
    assertEquals(withdrawals.size, 1)
  }

  // ── decided twice ─────────────────────────────────────────────────────────

  When("the same decision arrives again") { () =>
    kit.model.expectText("Submitted.")
    assertEquals(kit.resume(player, session, "approve").names().last, "done")
    decided = kit.resume(player, session, "approve")
  }
  Then("it is refused as already decided") { () => assertEquals((decided.status, decided.refusal), (409, "already_decided")) }
  Then("nothing runs twice") { () => assertEquals(withdrawals.size, 1) }

  // ── a deposit limit ───────────────────────────────────────────────────────

  Given("the player asks the support agent to set a daily deposit limit") { () =>
    kit.rg.answer("GET", s"/$player/limits", Answer.ok(Json.obj(
      "limits" -> Json.arr(Json.obj("limitType" -> "DEPOSIT_DAILY", "value" -> 5000L)), "pendingChanges" -> Json.arr(),
      "activeRestrictions" -> Json.arr())))
    kit.model.expectToolCall("set_deposit_limit",
      AgentJson.obj("amount" -> AgentJson.num(10000d), "period" -> AgentJson.str("daily")), "call-l")
  }
  When("the approval request is shown") { () => stream = kit.chat(player, session, "raise my daily limit to £100") }
  Then("its summary states whether the limit applies at once or after the {string} hour rule, as responsible gambling answers") {
    (hours: String) =>
      val frames = stream.frames()
      assertEquals(frames.map(_.name), Vector("confirm_action", "done"))
      assertEquals(frames.head.field("summary"),
        s"Set a daily deposit limit of 100.00. It applies $hours hours after you confirm, under the regulatory cooling-off rule.")
      assert(kit.rg.calls(s"/$player/limits").nonEmpty, "the summary read responsible gambling's current limits")
  }
