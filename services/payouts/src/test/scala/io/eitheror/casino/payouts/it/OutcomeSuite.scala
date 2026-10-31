package io.eitheror.casino.payouts.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.{CasinoWithdrawals, WithdrawalReversed}
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.request.Status
import io.eitheror.casino.payouts.workflow.Flow

import scala.concurrent.duration.*

/**
 * The provider's outcome route (R6, R8): admitted for `payments`/`psp-gateway` alone; an outcome settles a
 * request awaiting manual payout; a reversal of a completed request credits the player back once.
 */
class OutcomeSuite extends munit.FunSuite with LogCapturing:
  private lazy val kit   = SharedKit.kit
  private lazy val scene = Scene(kit)
  override def munitTimeout = 300.seconds
  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    scene.terminals()

  test("only the gateway's identity is admitted"):
    val p  = scene.player()
    val id = scene.request(p)
    kit.gateway.initiateFor(id, FakeGateway.answer(id, "initiated", Some(s"ref-$id")))
    assertEquals(scene.approve(id).status, 200)
    scene.at(id, Flow.Waiting)
    val others = Vector(
      "another service"              -> kit.asService("players"),
      "psp-gateway of another project" -> kit.asService("psp-gateway", "reasoner"),
      "a player"                     -> kit.asPlayer(p),
      "an operator"                  -> kit.asOperator(),
      "an admin"                     -> kit.asOperator("op-2", s"brand_${kit.slug},platform_admin"))
    others.foreach { (who, headers) =>
      val r = scene.outcome(id, "completed", s"ref-$id", headers)
      assert(Set(401, 403)(r.status), s"$who: ${r.status} ${r.text}")
    }
    assertEquals(scene.state(id).status, Status.Approved)
    val r = scene.outcome(id, "completed", s"ref-$id")
    assertEquals((r.status, r.str("status")), (200, "COMPLETED"), r.text)

  test("an outcome on a request awaiting manual payout wins"):
    val id = scene.request(scene.player(), terminalId = "t-manual")
    assertEquals(scene.approve(id).str("status"), "AWAITING_MANUAL_PAYOUT")
    val r = scene.outcome(id, "completed", "ref-manual")
    assertEquals((r.status, r.str("status")), (200, "COMPLETED"), r.text)
    scene.at(id, Flow.Completed)
    val late = kit.request("POST", s"/v1/casino/brands/${kit.slug}/withdrawal-requests/$id/mark-payout-failed", Some("{}"),
      kit.asOperator("op-9", s"brand_${kit.slug},brand_finance"))
    assertEquals(late.status, 409, late.text)
    assertEquals(scene.state(id).status, Status.Completed)

  test("a reversal of a completed request credits back once"):
    val id  = scene.request(scene.player())
    val ref = s"ref-$id"
    kit.gateway.initiateFor(id, FakeGateway.answer(id, "completed", Some(ref)))
    assertEquals(scene.approve(id).status, 200)
    scene.at(id, Flow.Completed)
    val answers = (1 to 3).map(_ => scene.outcome(id, "reversed", ref))
    assertEquals(answers.map(_.status).distinct, Vector(200), answers.map(_.text).mkString("\n"))
    scene.at(id, Flow.Reversed)
    assertEquals(scene.state(id).status, Status.Reversed)
    val reverses = kit.wallet.calls("/reverse").flatMap(_.json).filter(_.text("requestId").contains(id))
    assertEquals(reverses.size, 1)
    val reversed = kit.eventually("published")(Some(kit.facts(CasinoWithdrawals).collect {
      case f: WithdrawalReversed if f.payoutId == id => f
    }).filter(_.nonEmpty))
    Thread.sleep(500)
    assertEquals(kit.facts(CasinoWithdrawals).count { case f: WithdrawalReversed => f.payoutId == id; case _ => false }, 1)
    assertEquals(reversed.size, 1)
