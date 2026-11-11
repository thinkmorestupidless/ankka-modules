package io.eitheror.casino.support.it

import com.thinkmorestupidless.ankka.agent.{ChatMessage, Json as AgentJson, forAgent}
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.conversation.Status

import scala.concurrent.duration.*

/**
 * An action asked for, confirmed or cancelled (contracts/routes.md, research R3; T030): the chat's stream ends at
 * `confirm_action` and `done`; the resume route decides and streams `action_result`, the reply and `done`. An approved
 * write runs once, keyed by the approval id (FR-015, SC-001); one cancelled, or decided twice, runs nothing more.
 */
class ResumeSuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    kit.configure(): Unit

  private def withdrawal(amount: Long = 5000L, currency: String = "GBP") =
    AgentJson.obj("amount" -> AgentJson.num(amount.toDouble), "currency" -> AgentJson.str(currency))

  /** A turn that asks for a £50 withdrawal and awaits the player's decision; its approval id. */
  private def awaiting(session: String): String =
    kit.model.expectToolCall("request_withdrawal", withdrawal(), "call-w")
    val s = kit.chat("p-1", session, "withdraw £50")
    assertEquals(s.names(), Vector("confirm_action", "done"))
    kit.conversation(session).awaiting.map(_._1).getOrElse(fail("the conversation awaits no approval"))

  private def withdrawals = kit.payouts.calls.filter(_.path.endsWith("/withdrawal-requests"))

  test("the chat's stream ends at confirm_action, with the summary composed from the arguments, and nothing is requested"):
    kit.model.expectToolCall("request_withdrawal", withdrawal(), "call-w")
    val s = kit.chat("p-1", "resume-confirm", "withdraw £50")
    val frames = s.frames()
    assertEquals(frames.map(_.name), Vector("confirm_action", "done"))
    assertEquals((frames.head.field("action_name"), frames.head.field("summary"), frames.head.field("session_id")),
      ("request_withdrawal", "Withdraw 50.00 GBP from your account", "resume-confirm"))
    assertEquals(withdrawals, Vector.empty)

  test("approved: action_result succeeds, the reply streams, and payouts gets one request keyed by the approval id"):
    val id = awaiting("resume-approve")
    kit.model.expectText("Your withdrawal wr-1 is submitted.")
    val r = kit.resume("p-1", "resume-approve", "approve")
    val frames = r.frames()
    assertEquals(frames.map(_.name).distinct, Vector("action_result", "delta", "done"))
    assertEquals((frames.head.field("action_name"), frames.head.field("success"), frames.head.field("detail")),
      ("request_withdrawal", "true", "wr-1 submitted"))
    assertEquals(withdrawals.map(kit.payouts.key), Vector(Some(id)))
    assertEquals(withdrawals.flatMap(_.json).map(j => (j.long("amount"), j.text("currency"))), Vector((Some(5000L), Some("GBP"))))

  test("rejected: nothing is requested, the model is told the player's reason, and the reply streams"):
    awaiting("resume-reject")
    kit.model.expectText("No problem, nothing was withdrawn.")
    val r = kit.resume("p-1", "resume-reject", "reject", Some("changed my mind"))
    assertEquals(r.names().distinct, Vector("delta", "done"))
    assertEquals(withdrawals, Vector.empty)
    val told = kit.model.requests.flatMap(_.messages).collect { case ChatMessage.ToolResults(rs) => rs }.flatten.map(_.content)
    assert(told.exists(_.contains("changed my mind")), told)

  test("a new message while an action awaits is 409 Session already active, and the action still awaits"):
    val id = awaiting("resume-busy")
    val s = kit.chat("p-1", "resume-busy", "hello?")
    assertEquals((s.status, s.refusal), (409, "Session already active"))
    val details = io.eitheror.casino.support.Json.parse(s.body).flatMap(n => Option(n.get("details")))
    assertEquals((details.flatMap(_.text("approvalId")), details.flatMap(_.text("action_name"))),
      (Some(id), Some("request_withdrawal")))
    assert(kit.conversation("resume-busy").awaiting.isDefined)

  test("the same decision again is 409 already_decided, and nothing runs twice"):
    awaiting("resume-twice")
    kit.model.expectText("Submitted.")
    assertEquals(kit.resume("p-1", "resume-twice", "approve").names().last, "done")
    val again = kit.resume("p-1", "resume-twice", "approve")
    assertEquals((again.status, again.refusal), (409, "already_decided"))
    assertEquals(withdrawals.size, 1)

  test("nothing awaiting is 404 no_pending_action; another action is 422 invalid_action; another player's is 403"):
    kit.model.expectText("Hello.")
    assertEquals(kit.chat("p-1", "resume-none", "hello").names().last, "done")
    val none = kit.resume("p-1", "resume-none", "approve")
    assertEquals((none.status, none.refusal), (404, "no_pending_action"))
    val bad = kit.resume("p-1", "resume-none", "maybe")
    assertEquals((bad.status, bad.refusal), (422, "invalid_action"))
    val other = kit.resume("p-2", "resume-none", "approve")
    assertEquals((other.status, other.refusal), (403, "session_not_yours"))

  test("a refusal from the service is action_result success false naming it, and is not requested again"):
    awaiting("resume-refused")
    kit.payouts.answer("POST", "/players/p-1/withdrawal-requests",
      Answer(422, """{"error":"INSUFFICIENT_FUNDS","message":"The balance does not cover it"}"""))
    kit.model.expectText("Your balance does not cover that withdrawal.")
    val frames = kit.resume("p-1", "resume-refused", "approve").frames()
    assertEquals((frames.head.name, frames.head.field("success")), ("action_result", "false"))
    assert(frames.head.field("detail").contains("INSUFFICIENT_FUNDS"), frames.head.data)
    assertEquals(withdrawals.size, 1)

  private def preview(forfeit: Long) =
    kit.wallet.answer("GET", "/wallets/p-1/GBP/holds/preview", Answer.ok(io.eitheror.casino.support.Json.obj("totalForfeit" -> forfeit,
      "forfeits" -> io.eitheror.casino.support.Json.arr())))
  private def confirmed = withdrawals.flatMap(_.json).flatMap(_.bool("confirmForfeit"))

  test("a forfeit the summary named, and still no larger, is confirmed; one never shown, or grown since, is not (FR-013)"):
    def approved(session: String) =
      kit.model.expectText("Submitted.")
      assertEquals(kit.resume("p-1", session, "approve").names().last, "done")
    preview(1500L)
    kit.model.expectToolCall("request_withdrawal", withdrawal(), "call-f1")
    val named = kit.chat("p-1", "resume-forfeit-named", "withdraw £50").frames()
    assert(named.head.field("summary").contains("forfeits 15.00 GBP"), named.head.data)
    approved("resume-forfeit-named")
    // The preview could not be read for the summary, and names a forfeit by the time the player approves.
    kit.wallet.answer("GET", "/wallets/p-1/GBP/holds/preview", Answer(503, """{"error":"unavailable"}"""))
    kit.model.expectToolCall("request_withdrawal", withdrawal(), "call-f2")
    assertEquals(kit.chat("p-1", "resume-forfeit-unread", "withdraw £50").frames().head.field("summary"), "Withdraw 50.00 GBP from your account")
    preview(1500L)
    approved("resume-forfeit-unread")
    // Named at 15.00, grown to 20.00 since.
    kit.model.expectToolCall("request_withdrawal", withdrawal(), "call-f3")
    kit.chat("p-1", "resume-forfeit-grown", "withdraw £50").frames(): Unit
    preview(2000L)
    approved("resume-forfeit-grown")
    assertEquals(confirmed, Vector(true, false, false))

  test("another currency or an amount of zero is refused to the model with no approval shown and nothing called (FR-011)"):
    kit.model.expectToolCall("request_withdrawal", withdrawal(currency = "EUR"), "call-eur").expectText("I can only withdraw in GBP.")
    val eur = kit.chat("p-1", "resume-eur", "withdraw €50")
    assertEquals(eur.names().distinct, Vector("delta", "done"))
    kit.model.expectToolCall("request_withdrawal", withdrawal(amount = 0L), "call-zero").expectText("That amount is not valid.")
    assertEquals(kit.chat("p-1", "resume-zero", "withdraw nothing").names().distinct, Vector("delta", "done"))
    assertEquals(withdrawals, Vector.empty)
    assertEquals((kit.conversation("resume-eur").awaiting, kit.conversation("resume-zero").awaiting), (None, None))

  test("a restart while the approved tool runs ends the turn without running it again, and the player may ask again"):
    val id = awaiting("resume-restart")
    // Under the kit's 1 s neighbour timeout, so the one call is not retried (FR-012 would retry it, with the same key).
    kit.payouts.delayMillis = 800L
    val r = kit.resume("p-1", "resume-restart", "approve")
    kit.eventually("the request reached payouts")(Option(withdrawals).filter(_.nonEmpty))
    kit.restartService()
    r.frames(30.seconds): Unit
    kit.payouts.delayMillis = 0L
    Thread.sleep(2000)
    assertEquals(withdrawals.size, 1)
    kit.model.expectText("Hello again.")
    val next = kit.chat("p-1", "resume-restart", "is it done?")
    assertEquals((next.status, next.names().last), (200, "done"))
    assertEquals(withdrawals.map(kit.payouts.key), Vector(Some(id)))

  test("an approval not decided within approvalMinutes is refused by ankka, and the next message is a new turn (FR-016)"):
    kit.configure(_.copy(approvalMinutes = 1)): Unit
    awaiting("resume-expiry")
    // ankka's expiry resumes the turn, and the model answers the refusal: scripted here, so it never takes the next
    // turn's reply.
    kit.model.expectText("That request has expired.")
    val calls = kit.model.callCount
    kit.clock.moveBy(2.minutes)
    kit.eventually("ankka refused the approval and the model answered it") {
      Option(io.eitheror.casino.support.agent.Motoko.session("resume-expiry")).filter(s =>
        kit.components.forAgent(s).approvals().isEmpty && kit.model.callCount > calls)
    }
    kit.model.expectText("Fresh turn.")
    val next = kit.chat("p-1", "resume-expiry", "still there?")
    assertEquals((next.status, next.names().last), (200, "done"))
    assertEquals(kit.conversation("resume-expiry").status, Status.Open)
    assertEquals(kit.conversation("resume-expiry").lastDecided.isDefined, true)
    assertEquals(withdrawals, Vector.empty)
