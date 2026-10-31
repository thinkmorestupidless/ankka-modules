package io.eitheror.casino.payouts.features

import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.it.{Answer, FakeGateway, Reply}
import io.eitheror.casino.payouts.request.{PayoutStatus, Status}
import io.eitheror.casino.payouts.terminals.Terminal
import io.eitheror.casino.payouts.workflow.{Flow, WithdrawalWorkflow}

import java.time.Duration
import scala.concurrent.duration.*

/**
 * features/payouts/settlement.feature (US5; FR-016 to FR-021). The payout, its status and the provider's
 * outcome are the fake gateway's, scripted per request; the outcome arrives on the outcome route as the
 * gateway delivers it. The kit's waits stand for the original's: the first poll's thirty minutes are two
 * seconds, each five minutes more is one, and a provider's window is two seconds on its terminal.
 */
class SettlementFeatures extends PayoutsFeatures("settlement.feature"):

  private var id                  = ""
  private var reads               = 0
  private var balanceBefore: Long = 0L

  /** Unity Finance, which has a status to ask. */
  private val polled: Terminal = terminal("t-polled", "unityfinance", destination = true).copy(hasStatus = true)

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    id = ""; reads = 0; balanceBefore = 0L

  private def requested(to: Terminal = polled): Unit =
    id = pending(to = to)
    balanceBefore = kit.wallet.balance(player).getOrElse(0L)

  private def scripted(status: String, reference: Option[String] = Some("ref-1")): Unit =
    kit.gateway.initiateFor(id, FakeGateway.answer(id, status, reference))

  private def approved(): Unit = assertEquals(approve(id).status, 200, last.text)

  private def flow = kit.components.forWorkflow(com.thinkmorestupidless.ankka.core.EntityId(id)).call(WithdrawalWorkflow.status).invoke()
  private def at(step: String) = eventually(s"at $step")(Some(flow).filter(_.step == step))
  private def settled(status: Status) = eventually(s"$status")(Some(requestState(id)).filter(_.status == status))

  private def outcome(word: String, reference: String = "ref-1", reason: Option[String] = None): Reply =
    last = kit.json("POST", s"/v1/casino/brands/${kit.slug}/withdrawals/$id/outcome",
      Json.obj("outcome" -> word, "providerReference" -> reference, "reason" -> reason), kit.asGateway)
    last

  private def published[A](what: String)(pf: PartialFunction[CasinoWithdrawalsFact, A]): A = eventually(what)(withdrawals.collectFirst(pf))

  Given("a player with a wallet in {string}") { (c: String) => currency = c }

  // ── the payout ───────────────────────────────────────────────────────────────────────────────

  Given("an approved withdrawal request to a terminal with automated withdrawal payment") { () => requested() }

  When("the gateway answers completed") { () =>
    scripted("completed")
    approved()
  }

  Then("the hold is confirmed") { () =>
    eventually("confirmed")(kit.wallet.calls("/confirm").flatMap(_.json).find(_.text("requestId").contains(id)))
  }

  Then("the request is completed") { () => settled(Status.Completed) }

  Then("a {string} fact is emitted") { (family: String) =>
    family match
      case "withdrawal_completed" => published(family) { case f: WithdrawalCompleted if f.payoutId == id => f }
      case "withdrawal_failed"    => published(family) { case f: WithdrawalFailed if f.payoutId == id => f }
      case "withdrawal_reversed"  => published(family) { case f: WithdrawalReversed if f.payoutId == id => f }
      case other                  => fail(s"no binding for $other")
  }

  Given("a withdrawal payment the gateway answered initiated with the provider's reference") { () =>
    requested()
    scripted("initiated")
    approved()
    at(Flow.Waiting)
  }

  When("the provider's outcome arrives as completed") { () => assertEquals(outcome("completed").status, 200, last.text) }

  When("the provider's outcome arrives as failed") { () =>
    assertEquals(outcome("failed", reason = Some("account_closed")).status, 200, last.text)
  }

  Then("the held amount returns to real balance") { () =>
    eventually("returned")(Option.when(kit.wallet.balance(player).contains(balanceBefore + 5000L))(()))
  }

  Then("the request is failed naming the reason") { () =>
    settled(Status.Failed)
    published("withdrawal_failed") { case f: WithdrawalFailed if f.payoutId == id && f.failureReason == "account_closed" => f }
  }

  // ── waiting: the status loop and the provider's window ───────────────────────────────────────

  private def inDoubt(): Unit =
    requested()
    scripted("in_doubt", None)
    approved()
    at(Flow.Waiting)

  Given("a withdrawal payment in doubt to a provider that can be polled") { () => inDoubt() }

  When("{string} minutes pass with no outcome") { (_: String) => () }

  Then("the gateway is asked for its status") { () =>
    eventually("asked", 20.seconds)(Option.when(kit.gateway.statusReads(id).nonEmpty)(()))
  }

  Given("a withdrawal payment in doubt whose status was asked") { () =>
    inDoubt()
    eventually("asked", 20.seconds)(Option.when(kit.gateway.statusReads(id).nonEmpty)(()))
    reads = kit.gateway.statusReads(id).size
  }

  When("the status is not final") { () => () }

  Then("it is asked again {string} minutes later") { (_: String) =>
    eventually("asked again", 20.seconds)(Option.when(kit.gateway.statusReads(id).size > reads)(()))
    assertEquals(requestState(id).payoutStatus, PayoutStatus.Initiated)
  }

  When("the status is completed") { () => kit.gateway.statusFor(id, FakeGateway.answer(id, "completed", Some("ref-1"))) }
  When("the status is failed") { () => kit.gateway.statusFor(id, FakeGateway.answer(id, "failed", Some("ref-1"), Some("declined"))) }
  When("the status is reversed") { () => kit.gateway.statusFor(id, FakeGateway.answer(id, "reversed", Some("ref-1"), Some("chargeback"))) }

  Then("the request is failed") { () => settled(Status.Failed) }
  Then("the request is reversed") { () => settled(Status.Reversed) }

  When("the provider says the withdrawal payment was never created") { () =>
    kit.gateway.statusFor(id, FakeGateway.answer(id, "no_record"))
  }

  Then("the request is marked needs review") { () =>
    eventually("needs review", 20.seconds)(Some(requestState(id)).filter(_.payoutStatus == PayoutStatus.NeedsReview))
  }

  Then("the polling stops") { () =>
    val now = kit.gateway.statusReads(id).size
    Thread.sleep(3000)
    assertEquals(kit.gateway.statusReads(id).size, now)
  }

  private def webhooks(provider: String): Terminal =
    val window = Some(Duration.ofSeconds(2))
    provider match
      case "gigadat" => terminal("t-gigadat", "gigadat").copy(reviewWindow = window)
      case other     => terminal(s"t-$other", other, scheme = "USDT", coins = Vector("USDT")).copy(reviewWindow = window)

  Given("a withdrawal payment in doubt to a provider {string} that only sends webhooks") { (provider: String) =>
    val t = webhooks(provider)
    verified()
    kit.wallet.balanceFor(player, 10000L)
    terminals(bank, t)
    val extra: Vector[(String, Any)] =
      if t.crypto then Vector("walletAddress" -> "0xabc", "cryptoCurrency" -> "USDT")
      else Vector("payoutMethod" -> "ETO", "mobile" -> "+15550100")
    val r = ask((Vector[(String, Any)]("amount" -> 5000L, "currency" -> currency, "terminalId" -> t.id) ++ extra)*)
    assertEquals(r.status, 201, r.text)
    id = r.str("requestId")
    scripted("in_doubt", None)
    approved()
  }

  When("its window of {string} passes with no outcome") { (_: String) => () }

  Then("the queue shows it") { () =>
    eventually("queued")(queue.rows.find(r => r.requestId == id && r.payoutStatus == PayoutStatus.NeedsReview.wire))
  }

  When("the withdrawal payment is initiated and the gateway cannot be reached") { () =>
    kit.gateway.stop()
    try
      approved()
      at(Flow.Waiting)
    finally kit.gateway.start()
  }

  When("the withdrawal payment is initiated and the gateway answers something unrecognised") { () =>
    kit.gateway.initiateFor(id, Answer.ok(Json.obj("requestId" -> id, "status" -> "teleported")))
    approved()
    at(Flow.Waiting)
  }

  Then("the request is in doubt and reconciled") { () =>
    assertEquals(requestState(id).payoutStatus, PayoutStatus.Initiated)
    eventually("asked", 20.seconds)(Option.when(kit.gateway.statusReads(id).nonEmpty)(()))
  }

  Then("never awaiting manual payment") { () => assertNotEquals(requestState(id).status, Status.AwaitingManualPayout) }

  // ── rejected at initiation, and the manual path ──────────────────────────────────────────────

  Given("a withdrawal request whose withdrawal payment the gateway rejects at initiation") { () =>
    requested()
    kit.gateway.initiateFor(id, FakeGateway.answer(id, "rejected", None, Some("terminal_inactive")))
  }

  When("the request is approved") { () => approved() }

  Then("the hold is released as a withdrawal return with reason {string}") { (reason: String) =>
    settled(Status.Failed)
    assertEquals(kit.wallet.calls("/release").flatMap(_.json).map(_.text("outcome")), Vector(Some("failed")))
    published("withdrawal_failed") { case f: WithdrawalFailed if f.payoutId == id && f.failureReason == reason => f }
  }

  Then("the player sees it as failed and is told to try another terminal") { () =>
    eventually("listed as failed")(requests().objects("requests").find(r => r.text("requestId").contains(id) && r.text("status").contains("FAILED")))
    // The reason the player's site reads to tell them to choose another terminal (FR-018).
    published("provider_rejected") { case f: WithdrawalFailed if f.payoutId == id && f.failureReason == "provider_rejected" => f }
  }

  Then("the request is not awaiting manual payment") { () => assertNotEquals(requestState(id).status, Status.AwaitingManualPayout) }

  private val manualTerminal: Terminal = terminal("t-manual", "paymentiq", destination = true)

  Given("a withdrawal request to a terminal with no automated withdrawal payment") { () => requested(manualTerminal) }

  Then("the request is awaiting manual payment") { () =>
    assertEquals(last.str("status"), "AWAITING_MANUAL_PAYOUT", last.text)
    assertEquals(requestState(id).status, Status.AwaitingManualPayout)
  }

  Then("the player sees it as approved") { () =>
    // The player's activity shows "Approved" for both approved and awaiting manual payout, from this fact (the original's).
    published("withdrawal_approved") { case f: WithdrawalApproved if f.payoutId == id => f }
  }

  Then("the money stays held") { () =>
    assertEquals(kit.wallet.balance(player), Some(balanceBefore))
    assertEquals(kit.wallet.calls("/release").size + kit.wallet.calls("/confirm").size, 0)
  }

  Given("a withdrawal request pending review") { () => requested() }

  Given("a withdrawal request awaiting manual payment") { () =>
    requested(manualTerminal)
    approved()
  }

  Given("a withdrawal request approved and marked needs review") { () =>
    requested()
    scripted("initiated")
    approved()
    kit.gateway.statusFor(id, FakeGateway.answer(id, "no_record"))
    eventually("needs review", 20.seconds)(Some(requestState(id)).filter(_.payoutStatus == PayoutStatus.NeedsReview))
  }

  When("an operator confirms the manual payment") { () =>
    operator("POST", s"/$id/confirm-payout", Some("""{"note":"paid by bank transfer"}""")): Unit
  }

  When("an operator marks the manual payment failed with a reason") { () =>
    operator("POST", s"/$id/mark-payout-failed", Some("""{"reason":"the bank returned it"}""")): Unit
  }

  Then("it is refused as not awaiting payment") { () => refusedWith(409, "WITHDRAWAL_NOT_AWAITING_PAYOUT") }

  // ── reversal ─────────────────────────────────────────────────────────────────────────────────

  private def completed(): Unit =
    requested()
    scripted("completed")
    approved()
    at(Flow.Completed)

  Given("a completed withdrawal request") { () => completed() }

  When("the provider reports it charged back") { () => assertEquals(outcome("reversed", reason = Some("chargeback")).status, 200, last.text) }

  Then("the amount returns to real balance") { () =>
    eventually("reversed")(kit.wallet.calls("/reverse").flatMap(_.json).find(_.text("requestId").contains(id)))
  }

  Given("a reversed withdrawal request") { () =>
    completed()
    assertEquals(outcome("reversed", reason = Some("chargeback")).status, 200, last.text)
    settled(Status.Reversed)
  }

  When("the same reversal arrives again") { () => outcome("reversed", reason = Some("chargeback")): Unit }

  Then("nothing moves") { () =>
    assertEquals(kit.wallet.calls("/reverse").size, 1)
    assertEquals(requestState(id).status, Status.Reversed)
  }

  Then("the first outcome is returned") { () => assertEquals((last.status, last.str("status")), (200, "REVERSED"), last.text) }

  Given("a withdrawal request not completed") { () =>
    requested()
    scripted("initiated")
    approved()
    at(Flow.Waiting)
  }

  When("a reversal arrives for it") { () => outcome("reversed"): Unit }

  Then("it is refused as not completed") { () => refusedWith(409, "WITHDRAWAL_NOT_IN_COMPLETED_STATE") }
