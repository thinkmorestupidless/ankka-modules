package io.eitheror.casino.rewards.reward

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reward.RewardEvent.*
import io.eitheror.casino.rewards.reward.RewardStatus.{Active, Available, ConvertedPendingCap, Pending}

import java.time.Instant
import java.time.temporal.ChronoUnit.DAYS

/** data-model.md's `Reward` command table, row by row, through `Transitions`, with repeats and the clocks it sets (R10). */
class RewardSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-11-01T12:00:00Z")
  private val eur = Currency.unsafe("EUR")
  private type Kit = EventSourcedTestKit[Reward, RewardState, RewardEvent]

  private val decision = Decision(5000L, 175000L, 10000L, 35L, eur)
  private val funds = Opening("p-1", "o-1", "Welcome", "DEPOSIT_MATCH", PayloadType.BonusFunds, decision, Some(10L), Source.PlayerCode,
    Some("welcome"), Some("t-9"), RuleSnapshot(claimWindowDays = Some(5), wageringWindowDays = Some(14)))
  private val rounds = Opening("p-1", "o-2", "Spins", "FREE_SPINS", PayloadType.FreeSpins, Decision(0L, 0L, 0L, 0L, eur), None,
    Source.CustomerioWebhook, snapshot = RuleSnapshot(freeSpinsExpiryDays = Some(7)),
    freeRounds = Some(FreeRoundsTerms("g-1", "st8", 2, 10L, Destination.RealBalance)))
  private val award = WalletAward(Some("rec-1"), 175000L)

  private def fresh: Kit = EventSourcedTestKit.of(Reward, "r-1")
  private def opened(o: Opening = funds, initial: RewardStatus = Available): Kit =
    val kit = fresh
    val r   = kit.call(Reward.open)(Open(o, initial, at, Option.when(initial == Active)(award)))
    assertEquals(r.replyValue.refusal, None)
    kit
  private def active(o: Opening = funds): Kit =
    val kit = opened(o)
    kit.call(Reward.activate)(Activate(Some(award), at)): Unit
    kit
  private def converted: Kit =
    val kit = active()
    kit.call(Reward.convert)(Convert(at)): Unit
    kit
  private def status(kit: Kit) = kit.currentState.status
  private def refusal(a: RewardAnswer) = a.refusal.map(_.code)

  test("a reward not opened is CLAIM_NOT_FOUND"):
    assertEquals(refusal(fresh.call(Reward.select)(Select(at)).replyValue), Some("CLAIM_NOT_FOUND"))

  test("Open AVAILABLE is Targeted, holding the offer, the decision, source, code and tag; a repeat writes nothing"):
    val kit = fresh
    val r   = kit.call(Reward.open)(Open(funds, Available, at))
    assertEquals(r.events.toVector, Vector(Targeted("r-1", funds, at)))
    val s = kit.currentState
    assertEquals((s.rewardId, s.playerId, s.offerId, s.offerName, s.offerType, s.status, s.faceValue, s.wageringRequirement,
      s.maxConvertible, s.currency, s.priority, s.source, s.code, s.tag, s.openedAt),
      ("r-1", "p-1", "o-1", "Welcome", "DEPOSIT_MATCH", Available, 5000L, 175000L, 10000L, "EUR", Some(10L), Source.PlayerCode,
        Some("welcome"), Some("t-9"), at))
    assertEquals(kit.call(Reward.open)(Open(funds, Pending, at)).events.toVector, Vector.empty)

  test("Open PENDING is Targeted then Selected, its deadline the claim window days"):
    val kit = opened(initial = Pending)
    assertEquals((status(kit), kit.currentState.expiresAt), (Pending, Some(at.plus(5, DAYS))))

  test("Open ACTIVE is Targeted then Activated with the wallet's answer"):
    val kit = opened(initial = Active)
    val s   = kit.currentState
    assertEquals((s.status, s.recordId, s.grantedAt, s.expiresAt), (Active, Some("rec-1"), Some(at), Some(at.plus(14, DAYS))))

  test("Open in any other state is refused"):
    assertEquals(refusal(fresh.call(Reward.open)(Open(funds, RewardStatus.Closed, at)).replyValue), Some("INVALID_OPENING"))

  test("Select sets PENDING with the pending deadline (3 days when the offer sets no claim window); a repeat writes nothing"):
    val kit = opened(funds.copy(snapshot = RuleSnapshot()))
    assertEquals(kit.call(Reward.select)(Select(at)).events.toVector, Vector(Selected(at, at.plus(3, DAYS))))
    assertEquals(kit.call(Reward.select)(Select(at.plusSeconds(5))).events.toVector, Vector.empty)
    assertEquals(kit.currentState.selectedAt, Some(at))

  test("Deselect returns PENDING to AVAILABLE, clearing the deadline"):
    val kit = opened(initial = Pending)
    assertEquals(kit.call(Reward.deselect)(Deselect(at)).events.toVector, Vector(Deselected(at)))
    assertEquals((status(kit), kit.currentState.expiresAt), (Available, None))
    assertEquals(kit.call(Reward.deselect)(Deselect(at)).events.toVector, Vector.empty)

  test("Activated sets ACTIVE with the claim deadline (the wagering window days, else 30) and the wallet's requirement"):
    val kit = opened(funds.copy(snapshot = RuleSnapshot()), Pending)
    val r   = kit.call(Reward.activate)(Activate(Some(WalletAward(Some("rec-1"), 170000L)), at))
    assertEquals(r.events.toVector, Vector(Activated(Some(WalletAward(Some("rec-1"), 170000L)), at.plus(30, DAYS), None, at)))
    assertEquals((status(kit), kit.currentState.wageringRequirement, kit.currentState.recordId), (Active, 170000L, Some("rec-1")))
    assertEquals(kit.call(Reward.activate)(Activate(Some(award), at)).events.toVector, Vector.empty)

  test("Activated with the award's terms replaces the provisional amount, multiplier, maximum and currency it opened with"):
    val kit = opened(funds.copy(decision = Decision(0L, 0L, 0L, 35L, eur)), Pending)
    kit.call(Reward.activate)(Activate(Some(WalletAward(Some("rec-1"), 90000L, Some(3000L), Some(30L), Some(6000L), Some("EUR"))), at)): Unit
    val s = kit.currentState
    assertEquals((s.faceValue, s.wageringMultiplier, s.maxConvertible, s.currency, s.wageringRequirement), (3000L, 30L, 6000L, "EUR", 90000L))

  test("free rounds activate with their expiry (the offer's days, else 30)"):
    val kit = active(rounds)
    assertEquals(kit.currentState.expiresAt, Some(at.plus(7, DAYS)))

  test("AwardRefused records the wallet's code and stays as it was; a repeat, or once active, writes nothing"):
    val kit = opened(initial = Pending)
    assertEquals(kit.call(Reward.refuseAward)(RefuseAward("ACTIVE_BONUS_LIMIT", at)).events.toVector,
      Vector(AwardRefused("ACTIVE_BONUS_LIMIT", at)))
    assertEquals((status(kit), kit.currentState.lastDecline), (Pending, Some("ACTIVE_BONUS_LIMIT")))
    assertEquals(kit.call(Reward.refuseAward)(RefuseAward("ACTIVE_BONUS_LIMIT", at)).events.toVector, Vector.empty)
    assertEquals(active().call(Reward.refuseAward)(RefuseAward("X", at)).events.toVector, Vector.empty)

  test("Converted sets CONVERTED_PENDING_CAP and the cap's deadline"):
    val kit = active()
    assertEquals(kit.call(Reward.convert)(Convert(at)).events.toVector, Vector(Converted(at, at.plus(14, DAYS))))
    assertEquals((status(kit), kit.currentState.capExpiresAt, kit.currentState.convertedAt), (ConvertedPendingCap, Some(at.plus(14, DAYS)), Some(at)))

  test("Forfeited from ACTIVE and from a pending cap, with its reason"):
    for (kit, reason) <- Vector(active() -> ForfeitReason.BonusExhausted, converted -> ForfeitReason.AdminAdjustEmptied) do
      assertEquals(kit.call(Reward.forfeit)(Forfeit(reason, Some(1200L), at)).events.toVector,
        Vector(RewardEvent.Forfeited(reason, Some(1200L), at)))
      assertEquals((status(kit), kit.currentState.terminationReason, kit.currentState.terminatedAt), (RewardStatus.Forfeited, Some(reason.wire), Some(at)))

  test("Closed names whether it closed a pending cap"):
    val capped = converted
    assertEquals(capped.call(Reward.close)(Close(CloseReason.CapExpired, at)).events.toVector,
      Vector(RewardEvent.Closed(CloseReason.CapExpired, afterConversion = true, at)))
    val open = active()
    assertEquals(open.call(Reward.close)(Close(CloseReason.RealBalanceZero, at)).events.toVector,
      Vector(RewardEvent.Closed(CloseReason.RealBalanceZero, afterConversion = false, at)))
    for reason <- Vector(CloseReason.CapExhausted, CloseReason.FreeSpinsFinalised) do
      assertEquals(status(converted.tap(_.call(Reward.close)(Close(reason, at)))), RewardStatus.Closed)

  test("Expired from PENDING, AVAILABLE and ACTIVE names the state it left"):
    val pending = opened(initial = Pending)
    assertEquals(pending.call(Reward.expire)(Expire(ExpireReason.PendingExpired, at)).events.toVector,
      Vector(RewardEvent.Expired(ExpireReason.PendingExpired, Pending, at)))
    val open = active()
    assertEquals(open.call(Reward.expire)(Expire(ExpireReason.ClaimExpired, at)).events.toVector,
      Vector(RewardEvent.Expired(ExpireReason.ClaimExpired, Active, at)))
    assertEquals(opened().call(Reward.expire)(Expire(ExpireReason.WageringWindowExpired, at)).events.toVector,
      Vector(RewardEvent.Expired(ExpireReason.WageringWindowExpired, Available, at)))

  test("an Expire for a deadline the reward no longer holds is stale and writes nothing"):
    val kit = opened(initial = Pending)
    val a   = kit.call(Reward.expire)(Expire(ExpireReason.PendingExpired, at, Some(at.plus(1, DAYS))))
    assertEquals((a.events.toVector, a.replyValue.skipped, status(kit)), (Vector.empty, Some("stale_deadline"), Pending))
    kit.call(Reward.expire)(Expire(ExpireReason.PendingExpired, at, Some(at.plus(5, DAYS)))): Unit
    assertEquals(status(kit), RewardStatus.Expired)

  test("Cancelled from ACTIVE and PENDING with its clawback"):
    val kit = active()
    assertEquals(kit.call(Reward.cancel)(Cancel(CancelReason.PlayerCancel, 300L, at)).events.toVector,
      Vector(RewardEvent.Cancelled(CancelReason.PlayerCancel, 300L, at)))
    assertEquals(status(opened(initial = Pending).tap(_.call(Reward.cancel)(Cancel(CancelReason.ProviderFailed, 0L, at)))), RewardStatus.Cancelled)

  test("Revoked from ACTIVE and a pending cap, holding the operator and mode; the reason is the event's only"):
    val reason = Personal("the player asked by phone")
    for (kit, mode) <- Vector(active() -> RevokeMode.Forfeit, converted -> RevokeMode.Reclaim) do
      val r = kit.call(Reward.revoke)(Revoke(mode, "op-1", Some(reason), 0L, at))
      assertEquals(r.events.toVector, Vector(RewardEvent.Revoked(mode, "op-1", Some(reason), 0L, at)))
      assertEquals((status(kit), kit.currentState.revoke), (RewardStatus.Revoked, Some(Revocation("op-1", mode))))
      val text = String(Reward.given_Serializer_RewardState.toBytes(kit.currentState), java.nio.charset.StandardCharsets.UTF_8)
      assert(!text.contains("phone"), text)

  test("Switched records the step and the wallet's priority; a step already recorded, or a reward not open, does not"):
    val kit = active()
    assertEquals(kit.call(Reward.switch)(Switch(1, Some(99L), at)).events.toVector, Vector(Switched(1, Some(99L), at)))
    assertEquals(kit.currentState.priority, Some(99L))
    assertEquals(kit.call(Reward.switch)(Switch(1, Some(99L), at)).events.toVector, Vector.empty)
    assertEquals(refusal(opened().call(Reward.switch)(Switch(1, None, at)).replyValue), Some("NO_ACTIVE_BONUS"))

  test("ProviderStatus records the provider's word on free rounds; the same word writes nothing; funds are refused"):
    val kit = opened(rounds)
    assertEquals(kit.call(Reward.providerStatus)(ProviderStatus("processing", at)).events.toVector,
      Vector(ProviderStatusRecorded("processing", at)))
    assertEquals(kit.call(Reward.providerStatus)(ProviderStatus("processing", at)).events.toVector, Vector.empty)
    assertEquals(kit.currentState.freeRounds.flatMap(_.providerStatus), Some("processing"))
    assertEquals(refusal(active().call(Reward.providerStatus)(ProviderStatus("active", at)).replyValue), Some("NOT_FREE_ROUNDS"))

  test("RoundSettled counts once per round; the last round finalises, closes and answers the payment"):
    val kit = active(rounds)
    assertEquals(kit.call(Reward.roundSettled)(RoundSettled("rd-1", 150L, at)).events.toVector, Vector(RoundCounted("rd-1", 150L, 1, at)))
    val repeat = kit.call(Reward.roundSettled)(RoundSettled("rd-1", 150L, at))
    assertEquals((repeat.events.toVector, repeat.replyValue.skipped), (Vector.empty, Some("already_counted")))
    val last = kit.call(Reward.roundSettled)(RoundSettled("rd-2", 50L, at))
    assertEquals(last.events.toVector, Vector(RoundCounted("rd-2", 50L, 0, at), FreeRoundsFinalised(200L, at),
      RewardEvent.Closed(CloseReason.FreeSpinsFinalised, afterConversion = false, at)))
    assertEquals(last.replyValue.payment, Some(Payment(Destination.RealBalance, 200L)))
    assertEquals(status(kit), RewardStatus.Closed)

  test("the round that completed the count, delivered again, answers the finalisation's payment again"):
    val kit = active(rounds)
    kit.call(Reward.roundSettled)(RoundSettled("rd-1", 150L, at)): Unit
    assertEquals(kit.call(Reward.roundSettled)(RoundSettled("rd-1", 150L, at)).replyValue.payment, None)
    val last   = kit.call(Reward.roundSettled)(RoundSettled("rd-2", 50L, at)).replyValue.payment
    val repeat = kit.call(Reward.roundSettled)(RoundSettled("rd-2", 50L, at))
    assertEquals((repeat.events.toVector, repeat.replyValue.skipped), (Vector.empty, Some("already_counted")))
    assertEquals((last, repeat.replyValue.payment), (Some(Payment(Destination.RealBalance, 200L)), last))

  test("a round after finalisation is LatePaid under its own step; a repeat answers the same payment and writes nothing"):
    val kit = active(rounds)
    kit.call(Reward.roundSettled)(RoundSettled("rd-1", 0L, at)): Unit
    kit.call(Reward.roundSettled)(RoundSettled("rd-2", 0L, at)): Unit
    val late = kit.call(Reward.roundSettled)(RoundSettled("rd-3", 40L, at))
    assertEquals(late.events.toVector, Vector(LatePaid("rd-3", 40L, at)))
    val payment = Some(Payment(Destination.RealBalance, 40L, Some("rd-3")))
    assertEquals(late.replyValue.payment, payment)
    val again = kit.call(Reward.roundSettled)(RoundSettled("rd-3", 40L, at))
    assertEquals((again.events.toVector, again.replyValue.payment), (Vector.empty, payment))

  test("a round of free rounds not yet active, or of funds, does nothing"):
    assertEquals(opened(rounds).call(Reward.roundSettled)(RoundSettled("rd-1", 1L, at)).replyValue.skipped, Some("not_live"))
    assertEquals(refusal(active().call(Reward.roundSettled)(RoundSettled("rd-1", 1L, at)).replyValue), Some("NOT_FREE_ROUNDS"))

  test("Finalise at expiry: something won closes with the payment; nothing won expires; before expiry nothing; a repeat answers the payment"):
    val won = active(rounds)
    won.call(Reward.roundSettled)(RoundSettled("rd-1", 70L, at)): Unit
    assertEquals(won.call(Reward.finalise)(Finalise(at.plus(1, DAYS))).replyValue.skipped, Some("not_due"))
    val done = won.call(Reward.finalise)(Finalise(at.plus(7, DAYS)))
    assertEquals(done.events.toVector, Vector(FreeRoundsFinalised(70L, at.plus(7, DAYS)),
      RewardEvent.Closed(CloseReason.FreeSpinsFinalised, afterConversion = false, at.plus(7, DAYS))))
    assertEquals(done.replyValue.payment, Some(Payment(Destination.RealBalance, 70L)))
    val again = won.call(Reward.finalise)(Finalise(at.plus(8, DAYS)))
    assertEquals((again.events.toVector, again.replyValue.payment), (Vector.empty, Some(Payment(Destination.RealBalance, 70L))))
    val unused = active(rounds)
    assertEquals(unused.call(Reward.finalise)(Finalise(at.plus(7, DAYS))).events.toVector,
      Vector(FreeRoundsFinalised(0L, at.plus(7, DAYS)), RewardEvent.Expired(ExpireReason.FreeSpinsExpiredUnused, Active, at.plus(7, DAYS))))

  test("a move the table does not list is INVALID_TRANSITION naming both, and writes nothing"):
    val kit = opened()
    val r   = kit.call(Reward.convert)(Convert(at))
    assertEquals((r.events.toVector, r.replyValue.refusal),
      (Vector.empty, Some(Refused("INVALID_TRANSITION", "a reward cannot move from AVAILABLE to CONVERTED_PENDING_CAP"))))
    val closed = converted.tap(_.call(Reward.close)(Close(CloseReason.CapExpired, at)))
    for attempt <- Vector[Kit => RewardAnswer](
        _.call(Reward.activate)(Activate(None, at)).replyValue,
        _.call(Reward.cancel)(Cancel(CancelReason.PlayerCancel, 0L, at)).replyValue,
        _.call(Reward.revoke)(Revoke(RevokeMode.Reclaim, "op-1", None, 0L, at)).replyValue) do
      assertEquals(refusal(attempt(closed)), Some("INVALID_TRANSITION"))
    assertEquals(refusal(opened(initial = Pending).call(Reward.revoke)(Revoke(RevokeMode.Forfeit, "op-1", None, 0L, at)).replyValue),
      Some("INVALID_TRANSITION"))

  test("a terminal state repeated writes nothing"):
    val kit = active().tap(_.call(Reward.cancel)(Cancel(CancelReason.PlayerCancel, 0L, at)))
    assertEquals(kit.call(Reward.cancel)(Cancel(CancelReason.PlayerCancel, 0L, at)).events.toVector, Vector.empty)

  extension (k: Kit) private def tap(f: Kit => Any): Kit = { f(k); k }
