package io.eitheror.casino.wallet.entity.fixtures

import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.entity.fixtures.FixtureRunner.*
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

/**
 * `create-withdrawal-request.test.tl` (FR-023): the hold. The original's route computed the clawback
 * and passed it in (`clawback_amount`); the rebuild computes it from the forfeited records' baseline
 * (FR-015a), so each case seeds the baseline that makes the wallet's formula yield the fixture's
 * figure. A policy that applies no clawback (`none`, `funds`) still applies none, whatever is seeded.
 */
class CreateWithdrawalRequestFixturesSuite extends FixtureSuite("create-withdrawal-request"):

  override protected def ignored: Map[String, String] = Map(
    "withdrawal cap — worked example: deposits drawn first, cap exhausted; plan recorded, record untouched"           -> Since002.WithdrawalPlans,
    "withdrawal cap — closure trigger: real_balance reaching zero flags closes 1 in the plan, record still open"      -> Since002.WithdrawalPlans,
    "two-cap draw-down — 45.00 plans 30.00 then 15.00 in priority order, first flagged closes, none voided"           -> Since002.WithdrawalPlans,
    "two-cap draw-down — 60.00 pays 50.00, voids 10.00 as bonus_cap_void, both flagged closes"                        -> Since002.WithdrawalPlans,
    "ACTIVE + cap together — the ACTIVE record forfeits, the cap draws down and voids the excess"                     -> Since002.WithdrawalPlans
  )

  protected def check(c: Case): Unit =
    given Case = c
    val real     = long(c.wallet, "real_balance").getOrElse(0L)
    val clawback = long(c.params, "clawback_amount").getOrElse(0L)
    val records  = c.recordTwins.map(record).map(a => a.copy(baseline = Some(baselineFor(real, clawback))))
    val kit      = FixtureRunner.kit(c, opening(c, records))
    val before   = kit.currentState
    val result   = kit.call(WalletEntity.hold)(Hold("fixture-request", long(c.params, "amount").get, playerConfirmed = true, c.at))
    val o        = result.replyValue
    c.error match
      case Some(error) => assertRefused(error, before, kit, result.events, o)
      case None =>
        accepted(o)
        val state  = kit.currentState
        val placed = result.events.collectFirst { case e: WalletEvent.HoldPlaced => e }.getOrElse(fail("no hold placed"))
        assertWalletAndRecords(state)
        assertParts("from", placed.paidParts)
        val closed = placed.forfeits.map(_.recordId) ++ placed.capDraws.filter(_.closes).map(_.recordId)
        c.transactions("withdrawal").foreach { t =>
          same("withdrawal amount", long(t, "amount"), o.paid.get)
          same("withdrawal balance_before", long(t, "balance_before"), o.balanceBefore.get)
          same("withdrawal balance_after", long(t, "balance_after"), o.balanceAfter.get)
        }
        c.transactions("bonus_cap_void").foreach { t =>
          same("cap void amount", long(t, "amount"), o.voided.get)
          same("cap void balance_before", long(t, "balance_before"), o.balanceBefore.get)
          same("cap void balance_after", long(t, "balance_after"), o.balanceAfter.get)
        }
        c.transactions("bonus_cancel").foreach { t =>
          same("forfeit amount", long(t, "amount"), placed.forfeits.map(_.amount).sum)
          same("forfeit clawback_amount", long(t, "clawback_amount"), o.clawbackApplied.get)
          same("forfeit real_balance_before", long(t, "real_balance_before"), before.realBalance)
          same("forfeit real_balance_after", long(t, "real_balance_after"), before.realBalance - o.clawbackApplied.get)
        }
        c.mutations.filter(m => m.op == "set_properties" && m.target.startsWith("withdrawal-request.")).map(_.properties).foreach { r =>
          same("request amount", long(r, "amount"), o.paid.get)
          sameText("request status", text(r, "status").map(_ => RequestStatus.Pending), state.withdrawals("fixture-request").status)
        }
        c.result.foreach { r =>
          same("result amount", long(r, "amount"), o.paid.get)
          same("result voidedAmount", long(r, "voidedAmount"), o.voided.get)
          sameText("result bonusForfeited", text(r, "bonusForfeited"), o.forfeited.nonEmpty.toString)
          same("result forfeitedAmount", long(r, "forfeitedAmount"), placed.forfeits.map(_.amount).sum)
          same("result clawbackAmount", long(r, "clawbackAmount"), o.clawbackApplied.get)
          sameSet("result forfeited_records", text(r, "forfeited_records"), o.forfeited)
          sameSet("result closed_records", text(r, "closed_records"), closed)
          sameSet("result cancel_grants", text(r, "cancel_grants"), o.cancelGrants)
          sameText("result bonus_closed", text(r, "bonus_closed"), closed.nonEmpty.toString)
          same("result restricted_balance", long(r, "restricted_balance"), o.restrictedBalance)
        }
        assertEquals(state.pendingRequestId, Some("fixture-request"))

/** The withdrawal lifecycle files: the request a case names, at the status its parameters give. */
private object Lifecycle:

  /** The original's request statuses as the wallet's hold knows them (approval is `payouts`', spec 007). */
  def status(original: Option[String]): String = original match
    case Some("COMPLETED") => RequestStatus.Confirmed
    case Some("REVERSED")  => RequestStatus.Reversed
    case _                 => RequestStatus.Pending

  def request(c: Case, statusField: String): WithdrawalRequest =
    val id = text(c.params, "request_id").get
    WithdrawalRequest(id, s"tx-$id", long(c.params, "amount").getOrElse(0L), status(text(c.params, statusField)), DefaultAt.minusSeconds(3600))

  def kit(c: Case, statusField: String): EventSourcedTestKit[WalletEntity, WalletState, WalletEvent] =
    FixtureRunner.kit(c, opening(c, Vector.empty, withdrawals = Vector(request(c, statusField))))

/** A release: cancelled, rejected or failed (FR-024): the held amount back to real, the request closed. */
abstract class ReleaseFixturesSuite(handler: String, releasedAs: String) extends FixtureSuite(handler):

  protected def check(c: Case): Unit =
    given Case = c
    val kit    = Lifecycle.kit(c, "request_status")
    val before = kit.currentState
    val id     = text(c.params, "request_id").get
    val result = kit.call(WalletEntity.release)(Release(s"fixture-release-$id", id, releasedAs, c.at))
    val o      = result.replyValue
    c.error match
      case Some(error) => assertRefused(error, before, kit, result.events, o)
      case None =>
        accepted(o)
        val state = kit.currentState
        assertWalletAndRecords(state)
        assertEquals(state.pendingRequestId, None, s"${c.name}: the pending request is not cleared")
        assertEquals(state.withdrawals(id).status, RequestStatus.Released)
        c.transactions("withdrawal_return").foreach { t =>
          same("return amount", long(t, "amount"), o.amount.get)
          same("return balance_before", long(t, "balance_before"), o.balanceBefore.get)
          same("return balance_after", long(t, "balance_after"), o.balanceAfter.get)
        }

class CancelWithdrawalRequestFixturesSuite extends ReleaseFixturesSuite("cancel-withdrawal-request", "cancelled"):
  override protected def ignored: Map[String, String] = Map(
    "error — WITHDRAWAL_REQUEST_NOT_CANCELLABLE when request is APPROVED" ->
      "approval is payouts' (spec 007): the wallet's hold stays pending until released or confirmed, so payouts refuses this cancel",
    "plan request — refunds requested_amount"                         -> Since002.WithdrawalPlans,
    "idempotent — already CANCELLED writes nothing"                   -> Since002.WithdrawalPlans,
    "legacy — refunds amount plus the bonus_cap_void transaction"     -> Since002.WithdrawalPlans
  )

class RejectWithdrawalRequestFixturesSuite extends ReleaseFixturesSuite("reject-withdrawal-request", "rejected"):
  override protected def ignored: Map[String, String] = Map(
    "planned request — refunds requested_amount, stamps resolved_by / failure_reason / tx reason"     -> Since002.WithdrawalPlans,
    "error — WITHDRAWAL_REQUEST_NOT_FOUND when the request twin is missing"                           -> Since002.WithdrawalPlans,
    "idempotent — an already REJECTED request refunds nothing (pending ref already cleared)"          -> Since002.WithdrawalPlans,
    "legacy request with a bonus_cap_void transaction — refunds amount + the void"                    -> Since002.WithdrawalPlans
  )

class FailWithdrawalRequestFixturesSuite extends ReleaseFixturesSuite("fail-withdrawal-request", "failed"):
  override protected def ignored: Map[String, String] = Map(
    "plan refund from APPROVED — full requested_amount returns, reason on the tx"  -> Since002.WithdrawalPlans,
    "error — COMPLETED request is WITHDRAWAL_NOT_RETURNABLE"                        -> Since002.WithdrawalPlans,
    "idempotent — already FAILED credits nothing and mutates nothing"               -> Since002.WithdrawalPlans,
    "legacy with a bonus_cap_void tx — refund is amount plus the void"              -> Since002.WithdrawalPlans
  )

/** A confirmation (FR-024): the pending request cleared, no money moved. */
class CompleteWithdrawalRequestFixturesSuite extends FixtureSuite("complete-withdrawal-request"):

  override protected def ignored: Map[String, String] = Map(
    "exhausting draw — closes the record, prunes the wallet refs, reports closed_records"         -> Since002.WithdrawalPlans,
    "partial draw — record stays open with reduced deposits and allowance; refs untouched"        -> Since002.WithdrawalPlans,
    "deposits made while pending are preserved — the draw subtracts from current values"          -> Since002.WithdrawalPlans,
    "idempotent — a COMPLETED request returns idempotent with zero mutations"                     -> Since002.WithdrawalPlans
  )

  protected def check(c: Case): Unit =
    given Case = c
    val kit    = Lifecycle.kit(c, "request_status")
    val before = kit.currentState
    val id     = text(c.params, "request_id").get
    val o      = kit.call(WalletEntity.confirm)(Confirm(s"fixture-confirm-$id", id, c.at)).replyValue
    accepted(o)
    val state = kit.currentState
    assertWalletAndRecords(state)
    assertEquals(state.pendingRequestId, None)
    assertEquals(state.withdrawals(id).status, RequestStatus.Confirmed)
    assertEquals(state.realBalance, before.realBalance, s"${c.name}: a confirmation moved money")

/** A reversal (FR-025): a confirmed withdrawal back to real; a reversed one answered as done, moving nothing. */
class ReverseWithdrawalRequestFixturesSuite extends FixtureSuite("reverse-withdrawal-request"):

  override protected def ignored: Map[String, String] = Map(
    "open cap record — the payout's draw is given back" -> Since002.WithdrawalPlans
  )

  protected def check(c: Case): Unit =
    given Case = c
    val kit    = Lifecycle.kit(c, "current_request_status")
    val before = kit.currentState
    val id     = text(c.params, "request_id").get
    val result = kit.call(WalletEntity.reverse)(Reverse(s"fixture-reverse-$id", id, c.at))
    val o      = result.replyValue
    c.error match
      case Some(error) => assertRefused(error, before, kit, result.events, o)
      case None =>
        accepted(o)
        val state = kit.currentState
        assertWalletAndRecords(state)
        assertEquals(state.withdrawals(id).status, RequestStatus.Reversed)
        c.transactions("withdrawal_reversal").foreach { t =>
          same("reversal amount", long(t, "amount"), o.amount.get)
          same("reversal balance_before", long(t, "balance_before"), o.balanceBefore.get)
          same("reversal balance_after", long(t, "balance_after"), o.balanceAfter.get)
        }
        c.result.flatMap(r => Option(r.get("idempotent"))).filter(_.asBoolean()).foreach { _ =>
          assertEquals(o.details.get("idempotent"), Some("true"))
          assert(result.events.isEmpty, s"${c.name}: an idempotent reversal persisted ${result.events}")
          assertEquals(state.realBalance, before.realBalance)
        }
