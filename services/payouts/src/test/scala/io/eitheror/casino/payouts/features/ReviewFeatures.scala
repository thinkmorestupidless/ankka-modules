package io.eitheror.casino.payouts.features

import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.request.Status

/**
 * features/payouts/review.feature (US4; FR-014, FR-015). The operator acts through the brand's queue routes
 * with a finance role; the payout the approval starts is the fake gateway's, which answers `initiated`.
 */
class ReviewFeatures extends PayoutsFeatures("review.feature"):

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

  When("they open this brand's queue") { () => operator("GET", "/", roles = "brand_other,brand_finance"): Unit }

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
