package io.eitheror.casino.payouts.features

import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.it.{FakeGateway, FakeWallet}
import io.eitheror.casino.payouts.request.{PayoutStatus, Status}

import scala.concurrent.duration.*

/**
 * features/payouts/review.feature (US4; FR-014, FR-015). The operator acts through the brand's queue routes
 * with a finance role; the payout the approval starts is the fake gateway's, which answers `initiated`.
 */
class ReviewFeatures extends PayoutsFeatures("review.feature"):

  // spec 030 Y2 (T057) binds this, with the queue's counts
  override protected def ranElsewhere: Map[String, String] = Map(
    "the queue answers how many requests are pending review and how many payments need review" -> "T057 (pending)")

  private val polled = terminal("t-polled", "unityfinance", destination = true).copy(hasStatus = true)

  private var id = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    id = ""

  Given("a player with a wallet in {string}") { (c: String) => currency = c }

  Given("a withdrawal request pending review") { () => id = pending() }

  Given("a withdrawal request already approved") { () =>
    id = pending()
    assertEquals(approve(id).status, 200, last.text)
  }

  When("an operator with a finance or admin role for the brand approves it") { () => approve(id): Unit }

  Then("the request is approved") { () =>
    assertEquals((last.status, last.str("status")), (200, "APPROVED"), last.text)
    assertEquals(requestState(id).status, Status.Approved)
  }

  Then("a {string} fact is emitted") { (family: String) =>
    eventually(family)(withdrawals.collectFirst {
      case f: WithdrawalApproved if f.payoutId == id && family == "withdrawal_approved"   => ()
      case f: WithdrawalCancelled if f.payoutId == id && family == "withdrawal_cancelled" => ()
    })
  }

  Then("the withdrawal payment is pursued") { () =>
    eventually("the payout")(kit.gateway.payouts.find(_.json.exists(_.text("requestId").contains(id))))
  }

  When("an operator rejects it with the reason {string}") { (reason: String) => reject(id, reason): Unit }

  Then("the request is rejected") { () =>
    assertEquals((last.status, last.str("status")), (200, "REJECTED"), last.text)
    assertEquals(requestState(id).status, Status.Rejected)
  }

  Then("the held amount returns to real balance as a withdrawal return") { () =>
    assertEquals(kit.wallet.balance(player), Some(10000L))
    assertEquals(kit.wallet.calls("/release").flatMap(_.json).map(_.text("outcome")), Vector(Some("rejected")))
  }

  Then("the reason is recorded") { () =>
    assertEquals(requestState(id).decisions.map(d => (d.approved, d.operatorId, d.reason)),
      Vector((false, "op-1", Some(Personal("mismatched name")))))
  }

  Then("a {string} fact is emitted naming the operator's action") { (family: String) =>
    assertEquals(family, "withdrawal_cancelled")
    val f = eventually(family)(withdrawals.collectFirst { case f: WithdrawalCancelled if f.payoutId == id => f })
    assertEquals((f.actor, f.reason), ("operator", Some("rejected")))
  }

  When("an operator approves it again") { () => approve(id): Unit }
  When("an operator rejects it again") { () => reject(id, "again"): Unit }

  Then("it is refused as not pending") { () => refusedWith(409, "REQUEST_NOT_PENDING") }

  Given("an operator whose roles name another brand") { () => () }

  When("they open this brand's queue") { () => operator("GET", "/", roles = s"brand_other,${kit.in("brand_finance", "other")}"): Unit }

  Then("they are refused") { () => assertEquals(last.status, 403, last.text) }

  When("the player cancels it") { () =>
    last = kit.request("DELETE", kit.playerPath(s"/$player/withdrawal-requests/$id"), None, kit.asPlayer(player))
  }

  Then("the request is cancelled") { () =>
    assertEquals((last.status, last.str("status")), (200, "CANCELLED"), last.text)
    assertEquals(requestState(id).status, Status.Cancelled)
  }

  Then("the held amount returns to real balance") { () => assertEquals(kit.wallet.balance(player), Some(10000L)) }

  Then("it is refused as not cancellable") { () => refusedWith(409, "WITHDRAWAL_REQUEST_NOT_CANCELLABLE") }

  // ── spec 030 Y3: who confirmed a payment by hand, and the balances a request was made at ─────

  Given("an approved withdrawal request whose withdrawal payment needs review") { () =>
    id = pending(to = polled)
    kit.gateway.initiateFor(id, FakeGateway.answer(id, "initiated", Some(s"ref-$id")))
    kit.gateway.statusFor(id, FakeGateway.answer(id, "no_record"))
    assertEquals(approve(id).status, 200, last.text)
    eventually("needs review", 20.seconds)(Some(requestState(id)).filter(_.payoutStatus == PayoutStatus.NeedsReview))
  }

  When("an operator with a finance or admin role for the brand confirms the withdrawal payment") { () =>
    operator("POST", s"/$id/confirm-payout", Some("""{"note":"paid by bank transfer"}"""), subject = "op-confirmer"): Unit
    assertEquals(last.status, 200, last.text)
  }

  Then("the request read back names that operator against the confirmation") { () =>
    val read = operator("GET", s"/$id")
    assertEquals((read.status, read.str("status")), (200, "COMPLETED"), read.text)
    val confirmed = read.json.objects("outcomes").filter(_.text("answer").contains("completed"))
    assertEquals(confirmed.map(o => (o.text("source"), o.text("operatorId"))), Vector((Some("operator"), Some("op-confirmer"))), read.text)
    val moved = read.json.objects("transitions").last
    assertEquals((moved.text("status"), moved.text("by"), moved.text("operatorId")), (Some("completed"), Some("operator"), Some("op-confirmer")))
    // the approval names its own operator, apart from the confirmation's
    assert(read.json.objects("transitions").exists(t => t.text("status").contains("approved") && t.text("operatorId").contains("op-1")), read.text)
  }

  private var real = 0L
  private var restricted = 0L

  Given("a real balance of {string} and restricted funds of {string}") { (r: String, b: String) =>
    real = r.toLong
    restricted = b.toLong
    verified()
    kit.wallet.balanceFor(player, real)
    kit.wallet.previewBy(player)(_ => FakeWallet.preview(bonusBalance = restricted, available = Some(real)))
    terminals(bank)
  }

  When("the player makes a withdrawal request of {string}") { (a: String) =>
    val r = ask("amount" -> a.toLong, "currency" -> currency, "terminalId" -> bank.id, "destinationId" -> destination())
    assertEquals(r.status, 201, r.text)
    id = r.str("requestId")
  }

  Then("the request read back carries the real balance {string} and the restricted funds {string} it was made at") { (r: String, b: String) =>
    val read = operator("GET", s"/$id")
    assertEquals(read.status, 200, read.text)
    assertEquals((read.json.long("balancesAtRequest"), read.json.long("restrictedAtRequest")), (Some(r.toLong), Some(b.toLong)), read.text)
  }
