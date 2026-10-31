package io.eitheror.casino.payouts.workflow

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.it.{Answer, FakeGateway, FakeWallet, SharedKit}
import io.eitheror.casino.payouts.player.{PlayerPayouts, SeeEmail, SeeNames}
import io.eitheror.casino.payouts.request.*

import java.util.UUID

/**
 * The workflow's request half (R9) over the whole service, ankka 0.13 having no workflow test kit: the claim,
 * the keyed hold, the open, the pause for review, a refusal recorded, a transport failure retried, and a cancel;
 * then the payout half: the decision, every answer of R7's table, the waits of R8, the confirm across the
 * wallet's answer, and the reversal.
 */
class WithdrawalWorkflowSuite extends munit.FunSuite with LogCapturing:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(120, "s")
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private val terminal = TerminalRef("t-1", "sb-1", "Bank", "unityfinance", "EUBANKING", true)
  private val payee    = Payee(destinationId = Some("d-1"), holder = Some(Holder(Personal("Ana"), Personal("Silva"))),
    iban = Some(Personal("DE89370400440532013000")))

  private def job(player: String = s"wf-${UUID.randomUUID().toString.take(8)}", fingerprint: String = "f-1") =
    Job(WithdrawalWorkflow.requestId(player, UUID.randomUUID().toString), player, "EUR", 5000L, terminal, payee, "verified", false,
      Preview(bonusBalance = 300L), fingerprint)

  private def workflow(id: String) = kit.components.forWorkflow(EntityId(id))
  private def flow(id: String)     = workflow(id).call(WithdrawalWorkflow.status).invoke()
  private def request(id: String)  = kit.components.forEventSourcedEntity(EntityId(id)).call(WithdrawalRequest.get).invoke()
  private def at(id: String, step: String) = kit.eventually(s"$id at $step")(Some(flow(id)).filter(_.step == step))

  test("start holds keyed by the request id, opens the request with what the hold moved, and pauses for review"):
    val j = job()
    kit.wallet.holdFor(j.requestId, Answer.ok(io.eitheror.casino.payouts.Json.obj("paidAmount" -> 4800L, "clawbackAmount" -> 100L,
      "forfeited" -> io.eitheror.casino.payouts.Json.arr("rec-1"))))
    workflow(j.requestId).call(WithdrawalWorkflow.start).invoke(j): Unit
    val f = at(j.requestId, Flow.Review)
    assertEquals((f.money, f.job.map(_.payee)), (Set("hold"), Some(Payee())))
    val s = request(j.requestId)
    assertEquals((s.opened, s.amount, s.paidAmount, s.forfeit, s.payee), (true, 5000L, 4800L, Forfeit(true, 300L, 100L), payee))
    val holds = kit.wallet.calls("")
    assertEquals(holds.flatMap(_.json.flatMap(n => Option(n.get("key")).map(_.asText))), Vector(j.requestId))

  test("a second start answers the first; another body under the same id is KEY_REUSED"):
    val j = job()
    workflow(j.requestId).call(WithdrawalWorkflow.start).invoke(j): Unit
    at(j.requestId, Flow.Review)
    assertEquals(workflow(j.requestId).call(WithdrawalWorkflow.start).invoke(j).refusal, None)
    assertEquals(workflow(j.requestId).call(WithdrawalWorkflow.start).invoke(j.copy(fingerprint = "f-2")).refusal, Some("KEY_REUSED"))
    assertEquals(kit.wallet.calls("").size, 1)

  test("a wallet refusal is recorded, ends the workflow, and keeps no payee; no request exists"):
    val j = job()
    kit.wallet.holdFor(j.requestId, FakeWallet.refusal("INSUFFICIENT_FUNDS"))
    workflow(j.requestId).call(WithdrawalWorkflow.start).invoke(j): Unit
    val f = at(j.requestId, Flow.Refused)
    assertEquals((f.refusal.map(_.code), f.job.map(_.payee)), (Some("INSUFFICIENT_FUNDS"), Some(Payee())))
    assert(!request(j.requestId).opened)

  test("a hold the wallet does not answer is tried again until it is"):
    val j = job()
    kit.wallet.down = Some(Answer(503, "{}"))
    workflow(j.requestId).call(WithdrawalWorkflow.start).invoke(j): Unit
    kit.eventually("tried more than once")(Option.when(kit.wallet.calls("").size >= 2)(()))
    kit.wallet.down = None
    at(j.requestId, Flow.Review)
    assert(request(j.requestId).opened)

  test("a cancel in review releases the hold, then the request is cancelled; outside review it is refused"):
    val j = job()
    workflow(j.requestId).call(WithdrawalWorkflow.start).invoke(j): Unit
    at(j.requestId, Flow.Review)
    assertEquals(workflow(j.requestId).call(WithdrawalWorkflow.cancel).invoke(Actor.Player).refusal, None)
    at(j.requestId, Flow.Released)
    assertEquals(request(j.requestId).status, Status.Cancelled)
    val release = kit.wallet.calls("/release").flatMap(_.json)
    assertEquals(release.flatMap(n => Option(n.get("outcome")).map(_.asText)), Vector("cancelled"))
    assertEquals(workflow(j.requestId).call(WithdrawalWorkflow.cancel).invoke(Actor.Player).refusal, Some(RequestCodes.NotCancellable))

  // ── the payout half ──────────────────────────────────────────────────────────────────────────

  /** A job opened and paused for review, its player named with an email so Unity Finance's payout is automated. */
  private def reviewed(t: TerminalRef = terminal): Job =
    val j = job().copy(terminal = t)
    val record = kit.components.forEventSourcedEntity(EntityId(j.playerId))
    record.call(PlayerPayouts.seeNames).invoke(SeeNames(Personal("Ana"), Personal("Silva"), kit.now())): Unit
    record.call(PlayerPayouts.seeEmail).invoke(SeeEmail(Personal("ana@example.test"), kit.now())): Unit
    workflow(j.requestId).call(WithdrawalWorkflow.start).invoke(j): Unit
    at(j.requestId, Flow.Review)
    j

  private def approved(j: Job): Unit =
    assertEquals(workflow(j.requestId).call(WithdrawalWorkflow.approve).invoke(ByOperator("op-1")).refusal, None)

  private def payouts(id: String) = kit.gateway.payouts.flatMap(_.json).filter(_.text("requestId").contains(id))

  test("approval initiates one payout keyed by the request id, and each of R7's answers leads where its table says"):
    val cases = Vector[(Answer, String)](
      FakeGateway.answer("x", "completed", Some("ref-c"))                  -> Flow.Completed,
      FakeGateway.answer("x", "initiated", Some("ref-i"))                  -> Flow.Waiting,
      FakeGateway.answer("x", "already_initiated", Some("ref-a"))          -> Flow.Waiting,
      FakeGateway.answer("x", "in_doubt", None, Some("timeout"))           -> Flow.Waiting,
      Answer.json(409, Json.obj("error" -> "initiation_in_progress"))      -> Flow.Waiting,
      Answer(503, "{}")                                                   -> Flow.Waiting,
      Answer(200, "not json")                                             -> Flow.Waiting,
      FakeGateway.answer("x", "rejected", None, Some("terminal_inactive")) -> Flow.Released,
      Answer.json(400, Json.obj("error" -> "invalid_request"))             -> Flow.Released,
      Answer.json(502, Json.obj("error" -> "provider_not_configured"))     -> Flow.Released
    )
    cases.foreach { (answer, step) =>
      val j = reviewed()
      kit.gateway.initiateFor(j.requestId, answer)
      approved(j)
      at(j.requestId, step)
      assertEquals(payouts(j.requestId).size, 1, answer.toString)
      assertEquals(payouts(j.requestId).head.text("subBrandId"), Some("sb-1"))
      val r = request(j.requestId)
      step match
        case Flow.Waiting   => assertEquals((r.status, r.payoutStatus), (Status.Approved, PayoutStatus.Initiated), answer.toString)
        case Flow.Completed => assertEquals(r.status, Status.Completed)
        case _ =>
          assertEquals(r.status, Status.Failed, answer.toString)
          val release = kit.wallet.calls("/release").flatMap(_.json).filter(_.text("requestId").contains(j.requestId))
          assertEquals(release.map(_.text("outcome")), Vector(Some("failed")))
      assert(r.status != Status.AwaitingManualPayout, answer.toString)
    }

  test("a terminal with no automated payout awaits manual payout, with the money held and nothing sent"):
    val j = reviewed(terminal.copy(id = "t-manual", gateway = "paymentiq"))
    approved(j)
    at(j.requestId, Flow.AwaitingManual)
    assertEquals((request(j.requestId).status, payouts(j.requestId)), (Status.AwaitingManualPayout, Vector.empty))
    assertEquals(kit.wallet.calls("/release").size + kit.wallet.calls("/confirm").size, 0)
    assertEquals(workflow(j.requestId).call(WithdrawalWorkflow.approve).invoke(ByOperator("op-2")).refusal, Some(RequestCodes.NotPending))

  test("a polled provider is asked after the first wait, then every poll; no_record marks needs review and stops"):
    val j = reviewed(terminal.copy(hasStatus = true))
    kit.gateway.initiateFor(j.requestId, FakeGateway.answer("x", "in_doubt"))
    approved(j)
    at(j.requestId, Flow.Waiting)
    assertEquals(kit.gateway.statusReads(j.requestId).size, 0)
    kit.eventually("asked twice", scala.concurrent.duration.Duration(20, "s"))(Option.when(kit.gateway.statusReads(j.requestId).size >= 2)(()))
    kit.gateway.statusFor(j.requestId, FakeGateway.answer(j.requestId, "no_record"))
    at(j.requestId, Flow.NeedsReview)
    assertEquals(request(j.requestId).payoutStatus, PayoutStatus.NeedsReview)
    val reads = kit.gateway.statusReads(j.requestId).size
    Thread.sleep(2500)
    assertEquals(kit.gateway.statusReads(j.requestId).size, reads)

  test("a provider with no status to ask is marked needs review when its window has passed, and never asked"):
    val j = reviewed(terminal.copy(reviewWindow = Some(java.time.Duration.ofSeconds(1))))
    kit.gateway.initiateFor(j.requestId, FakeGateway.answer("x", "initiated", Some("ref-w")))
    approved(j)
    at(j.requestId, Flow.NeedsReview)
    assertEquals((request(j.requestId).payoutStatus, kit.gateway.statusReads(j.requestId).size), (PayoutStatus.NeedsReview, 0))

  test("a completion is recorded before the wallet confirms; the confirm, keyed <id>/confirm, is tried until it answers"):
    val j = reviewed()
    kit.gateway.initiateFor(j.requestId, FakeGateway.answer("x", "completed", Some("ref-c")))
    kit.wallet.stepFor(j.requestId, "confirm", Answer(503, "{}"))
    approved(j)
    kit.eventually("completed first")(Option.when(request(j.requestId).status == Status.Completed && flow(j.requestId).step == Flow.Confirming)(()))
    def confirms = kit.wallet.calls("/confirm").flatMap(_.json).filter(_.text("requestId").contains(j.requestId))
    kit.eventually("tried again")(Option.when(confirms.size >= 2)(()))
    // A confirm the wallet already made answers REQUEST_NOT_PENDING: the first outcome.
    kit.wallet.stepFor(j.requestId, "confirm", FakeWallet.refusal("REQUEST_NOT_PENDING"))
    at(j.requestId, Flow.Completed)
    assertEquals(confirms.flatMap(_.text("key")).distinct, Vector(s"${j.requestId}/confirm"))

  test("a completed workflow waits for a reversal; the reverse, keyed <id>/reverse, ends it"):
    val j = reviewed()
    kit.gateway.initiateFor(j.requestId, FakeGateway.answer("x", "completed", Some("ref-c")))
    approved(j)
    at(j.requestId, Flow.Completed)
    def outcome(o: Outcome) = workflow(j.requestId).call(WithdrawalWorkflow.outcome).invoke(o).refusal
    assertEquals(outcome(Outcome("completed")), None)
    assertEquals(outcome(Outcome("failed")), Some(FlowCodes.AlreadyResolved))
    assertEquals(outcome(Outcome("reversed", Some("ref-c"), Some("chargeback"))), None)
    at(j.requestId, Flow.Reversed)
    assertEquals(request(j.requestId).status, Status.Reversed)
    val keys = kit.wallet.calls("/reverse").flatMap(_.json).filter(_.text("requestId").contains(j.requestId)).flatMap(_.text("key"))
    assertEquals(keys, Vector(s"${j.requestId}/reverse"))
    assertEquals(outcome(Outcome("reversed")), None)

  test("a settlement by hand is taken only awaiting manual payout or needs review; a failure without a reason has the original's"):
    val j = reviewed(terminal.copy(id = "t-manual", gateway = "paymentiq"))
    assertEquals(workflow(j.requestId).call(WithdrawalWorkflow.confirmPayout).invoke(ByOperator("op-1")).refusal, Some(RequestCodes.NotAwaitingPayout))
    approved(j)
    at(j.requestId, Flow.AwaitingManual)
    val r = kit.request("POST", s"/v1/casino/brands/${kit.slug}/withdrawal-requests/${j.requestId}/mark-payout-failed", Some("{}"),
      kit.asOperator("op-9", s"brand_${kit.slug},brand_finance"))
    assertEquals((r.status, r.str("status")), (200, "FAILED"), r.text)
    assertEquals(request(j.requestId).status, Status.Failed)
    kit.eventually("published")(kit.facts(io.eitheror.casino.contract.facts.CasinoWithdrawals).collectFirst {
      case f: io.eitheror.casino.contract.facts.WithdrawalFailed if f.payoutId == j.requestId => f.failureReason
    }.filter(_ == "manual_payout_failed"))
