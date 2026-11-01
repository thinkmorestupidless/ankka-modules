package io.eitheror.casino.rewards.steps

import io.eitheror.casino.rewards.clients
import io.eitheror.casino.rewards.clients.{AwardTerms, Balances}
import io.eitheror.casino.rewards.reward
import io.eitheror.casino.rewards.reward.*

import java.time.Instant

/** What each wallet answer does to its reward (research R5's "On answer" column), pure. */
class WiringSuite extends munit.FunSuite:
  private val at    = Instant.parse("2026-11-01T12:00:00Z")
  private val terms = AwardTerms("o-1", 3000L, 30L, 6000L, Some(5L), None)
  private val money = Balances(0L, 0L)
  private def req(call: WalletCall, ending: Option[Ending] = None) =
    StepRequest("r-1", "p-1", "EUR", call, byTimer = ending.isDefined, ending = ending)

  test("an award activates with the wallet's record and requirement and the terms awarded"):
    assertEquals(Wiring.move(req(WalletCall.Award(terms)), WalletAnswer.Awarded(clients.Awarded(Some("rec-1"), 90000L, None, money)), at),
      Landing.Activate(reward.Activate(Some(WalletAward(Some("rec-1"), 90000L, Some(3000L), Some(30L), Some(6000L), Some("EUR"))), at)))

  test("an award refused is recorded with the wallet's code"):
    assertEquals(Wiring.move(req(WalletCall.Award(terms)), WalletAnswer.Refused(409, "BONUS_ALREADY_ACTIVE", ""), at),
      Landing.RefuseAward(reward.RefuseAward("BONUS_ALREADY_ACTIVE", at)))

  test("a timer's step ends the reward as the timer decided, the record closed or not open alike"):
    val forfeit = req(WalletCall.Forfeit, Some(Ending.Expire(ExpireReason.WageringWindowExpired)))
    val expired = Landing.Expire(reward.Expire(ExpireReason.WageringWindowExpired, at))
    assertEquals(Wiring.move(forfeit, WalletAnswer.Forfeited(clients.Forfeited(Vector("r-1"), money)), at), expired)
    assertEquals(Wiring.move(forfeit, WalletAnswer.NotOpen("NO_ACTIVE_BONUS"), at), expired)
    val cap = req(WalletCall.CloseCap, Some(Ending.Close(CloseReason.CapExpired)))
    assertEquals(Wiring.move(cap, WalletAnswer.NotOpen("NOT_PENDING_CAP"), at), Landing.Close(reward.Close(CloseReason.CapExpired, at)))

  test("a player's cancel with its clawback; a switch with the wallet's priority"):
    assertEquals(Wiring.move(req(WalletCall.Cancel()), WalletAnswer.Cancelled(clients.Cancelled(250L, Vector("r-1"), money)), at),
      Landing.Cancel(reward.Cancel(CancelReason.PlayerCancel, 250L, at)))
    assertEquals(Wiring.move(req(WalletCall.Switch(2)), WalletAnswer.Switched(clients.Switched(Some(9L), false)), at),
      Landing.Switch(reward.Switch(2, Some(9L), at)))

  test("a payment's answer, a forfeit not asked by a timer, and a refusal of anything but the award move nothing"):
    assert(Wiring.move(req(WalletCall.Credit(10L, "x", "y")), WalletAnswer.Credited(clients.Credited(None, None, money)), at)
      .isInstanceOf[Landing.Idle])
    assert(Wiring.move(req(WalletCall.Forfeit), WalletAnswer.Forfeited(clients.Forfeited(Vector.empty, money)), at).isInstanceOf[Landing.Idle])
    assert(Wiring.move(req(WalletCall.Switch(1)), WalletAnswer.Refused(404, "GRANT_NOT_FOUND", ""), at).isInstanceOf[Landing.Idle])
