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

  test("winnings are their own reward's award: keyed by that reward, activated as an award is, ended when refused"):
    val own = req(WalletCall.Winnings(terms)).copy(rewardId = "r-1/winnings")
    assertEquals((own.key, own.id), ("r-1/winnings", "r-1/winnings/winnings"))
    assertEquals(Wiring.move(own, WalletAnswer.Awarded(clients.Awarded(Some("r-1/winnings"), 90000L, None, money)), at),
      Landing.Activate(reward.Activate(Some(WalletAward(Some("r-1/winnings"), 90000L, Some(3000L), Some(30L), Some(6000L), Some("EUR"))), at)))
    assertEquals(Wiring.move(own, WalletAnswer.Refused(409, "MAX_CONCURRENT_GRANTS", ""), at),
      Landing.Expire(reward.Expire(ExpireReason.WinningsRefused, at)))

  test("the winnings' reward opens from the free rounds': their reward as its offer, bonus funds, the system's"):
    val rounds = RewardState(rewardId = "r-1", opened = true, playerId = "p-1", offerId = "o-1", offerName = "Spins", offerType = "FREE_SPINS",
      wageringMultiplier = 10L, maxConvertible = 5000L, currency = "EUR")
    val o = Wiring.winningsOpening(rounds, 350L)
    // The free-rounds reward, not the offer: the offer's claim limit, conflicts and visible offers never count the winnings.
    assertEquals((o.offerId, o.offerName, o.payloadType, o.source, o.freeRounds), ("r-1", "Spins", io.eitheror.casino.rewards.offers.PayloadType.BonusFunds,
      Source.System, None))
    assertEquals((o.decision.amount, o.decision.wageringRequirement, o.decision.maxConvertible), (350L, 3500L, 5000L))
    assertEquals(Wiring.winnings(rounds, 350L, at).expiresAt, Some(at.plusSeconds(30L * 86400)))

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

  test("a revoke's answer moves nothing on delivery: the route records it, with the clawback and the reason"):
    val reclaim = req(WalletCall.Revoke(RevokeMode.Reclaim))
    assertEquals(reclaim.id, "r-1/revoke-reclaim")
    val answer = WalletAnswer.Cancelled(clients.Cancelled(250L, Vector("r-1"), Balances(900L, 0L)))
    assert(Wiring.move(reclaim, answer, at).isInstanceOf[Landing.Idle])
    val reason = Some(io.eitheror.casino.contract.personal.Personal("why"))
    assertEquals(Wiring.revoked(RevokeMode.Reclaim, "op-1", reason, answer, at),
      Right(reward.Revoke(RevokeMode.Reclaim, "op-1", reason, 250L, at) -> 900L))
    assertEquals(Wiring.revoked(RevokeMode.Forfeit, "op-1", None, WalletAnswer.Forfeited(clients.Forfeited(Vector("r-1"), money)), at),
      Right(reward.Revoke(RevokeMode.Forfeit, "op-1", None, 0L, at) -> 0L))
    assertEquals(Wiring.revoked(RevokeMode.Reclaim, "op-1", None, WalletAnswer.Refused(409, "MISSING_BASELINE", ""), at),
      Left("MISSING_BASELINE"))
