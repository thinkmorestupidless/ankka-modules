package io.eitheror.casino.payouts.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.request.{PayoutStatus, Status}
import io.eitheror.casino.payouts.workflow.Flow

import scala.concurrent.duration.*

/**
 * SC-002: the gateway broken at each answer of the payout and at each wait. Nothing that may have reached a
 * provider is offered for manual payout; such a request waits for its outcome or its status. Awaiting manual
 * payout is reached only by approval on a terminal with no automated payout, and `rejected` returns the money.
 */
class PayoutFailureSuite extends munit.FunSuite with LogCapturing:
  private lazy val kit   = SharedKit.kit
  private lazy val scene = Scene(kit)
  override def munitTimeout = 300.seconds
  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    scene.terminals()

  private def notManual(id: String): Unit =
    assertNotEquals(scene.state(id).status, Status.AwaitingManualPayout)

  private val broken: Vector[(String, Answer)] = Vector(
    "a 500"                    -> Answer(500, """{"error":"boom"}"""),
    "a 503"                    -> Answer(503, "{}"),
    "an unreadable body"       -> Answer(200, "<html>gateway</html>"),
    "an unrecognised status"   -> Answer.ok(Json.obj("status" -> "teleported")),
    "in_doubt"                 -> Answer.ok(Json.obj("status" -> "in_doubt", "reason" -> "provider_timeout")),
    "initiation_in_progress"   -> Answer.json(409, Json.obj("error" -> "initiation_in_progress"))
  )

  test("each broken answer of the payout leaves the request initiated and waiting, asked for its status, never manual"):
    broken.foreach { (what, answer) =>
      val id = scene.request(scene.player())
      kit.gateway.initiateFor(id, answer)
      assertEquals(scene.approve(id).status, 200, what)
      scene.at(id, Flow.Waiting)
      assertEquals((scene.state(id).status, scene.state(id).payoutStatus), (Status.Approved, PayoutStatus.Initiated), what)
      kit.eventually(s"$what: asked", 20.seconds)(Option.when(kit.gateway.statusReads(id).nonEmpty)(()))
      notManual(id)
      assertEquals(kit.wallet.calls("/release").count(_.json.exists(_.text("requestId").contains(id))), 0, what)
    }

  test("an unreachable gateway at the payout is in doubt, and reconciled once it answers"):
    val id = scene.request(scene.player())
    kit.gateway.stop()
    try
      assertEquals(scene.approve(id).status, 200)
      scene.at(id, Flow.Waiting)
    finally kit.gateway.start()
    notManual(id)
    kit.gateway.statusFor(id, FakeGateway.answer(id, "completed", Some("ref-late")))
    kit.eventually("completed", 20.seconds)(Some(scene.state(id)).filter(_.status == Status.Completed))

  test("each broken answer at a wait asks again; nothing moves until a final status"):
    val waits = Vector[(String, Answer)](
      "a 500"                -> Answer(500, "{}"),
      "a 502 unavailable"    -> Answer.json(502, Json.obj("error" -> "status_unavailable")),
      "an unreadable body"   -> Answer(200, "not json"),
      "pending"              -> FakeGateway.answer("x", "pending"))
    waits.foreach { (what, answer) =>
      val id = scene.request(scene.player())
      kit.gateway.initiateFor(id, FakeGateway.answer(id, "initiated", Some(s"ref-$id")))
      kit.gateway.statusFor(id, answer)
      assertEquals(scene.approve(id).status, 200, what)
      kit.eventually(s"$what: asked twice", 20.seconds)(Option.when(kit.gateway.statusReads(id).size >= 2)(()))
      assertEquals((scene.state(id).status, scene.state(id).payoutStatus), (Status.Approved, PayoutStatus.Initiated), what)
      assertEquals(kit.wallet.calls("/confirm").size + kit.wallet.calls("/release").size, 0, what)
      notManual(id)
      kit.gateway.statusFor(id, FakeGateway.answer(id, "failed", Some(s"ref-$id"), Some("declined")))
      kit.eventually(s"$what: failed", 20.seconds)(Some(scene.state(id)).filter(_.status == Status.Failed))
      kit.wallet.clearReceived()
    }

  test("rejected at initiation returns the money with provider_rejected, and is not manual"):
    val p  = scene.player()
    val id = scene.request(p)
    kit.gateway.initiateFor(id, FakeGateway.answer(id, "rejected", None, Some("terminal_inactive")))
    assertEquals(scene.approve(id).status, 200)
    kit.eventually("failed")(Some(scene.state(id)).filter(_.status == Status.Failed))
    assertEquals(kit.wallet.balance(p), Some(10000L))
    val failed = kit.eventually("published")(kit.facts(io.eitheror.casino.contract.facts.CasinoWithdrawals).collectFirst {
      case f: io.eitheror.casino.contract.facts.WithdrawalFailed if f.payoutId == id => f
    })
    assertEquals(failed.failureReason, "provider_rejected")
    notManual(id)

  test("awaiting manual payout is reached only by approval on a terminal with no automated payout"):
    val id = scene.request(scene.player(), terminalId = "t-manual")
    val r  = scene.approve(id)
    assertEquals((r.status, r.str("status")), (200, "AWAITING_MANUAL_PAYOUT"), r.text)
    assertEquals(kit.gateway.payouts.count(_.json.exists(_.text("requestId").contains(id))), 0)
