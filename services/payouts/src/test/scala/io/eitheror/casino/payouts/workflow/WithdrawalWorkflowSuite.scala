package io.eitheror.casino.payouts.workflow

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.it.{Answer, FakeWallet, SharedKit}
import io.eitheror.casino.payouts.request.*

import java.util.UUID

/**
 * The workflow's request half (R9) over the whole service, ankka 0.13 having no workflow test kit: the claim,
 * the keyed hold, the open, the pause for review, a refusal recorded, a transport failure retried, and a cancel.
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
