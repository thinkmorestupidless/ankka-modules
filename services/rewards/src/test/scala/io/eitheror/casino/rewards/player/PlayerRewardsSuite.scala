package io.eitheror.casino.rewards.player

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.offers.{Offer, OfferType}
import io.eitheror.casino.rewards.player.PlayerEvent.*
import io.eitheror.casino.rewards.reward.{RewardStatus, Source}

import java.time.Instant
import java.time.temporal.ChronoUnit.{DAYS, HOURS}

/**
 * data-model.md's `PlayerRewards`: each command evaluates over the record and reserves or declines in one event, a
 * repeat writes nothing, and two claims at once see each other.
 */
class PlayerRewardsSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-11-01T12:00:00Z")
  private val Eur = Currency.unsafe("EUR")
  private type Kit = EventSourcedTestKit[PlayerRewards, PlayerRewardsState, PlayerEvent]

  private def record: Kit = EventSourcedTestKit.of(PlayerRewards, "p-1")

  private val matchOffer = Offer(id = "o-match", name = "Welcome", offerType = OfferType.DepositMatch, bonusPercentage = 10000L,
    maxBonusValue = Some(50000L), minDepositAmount = Some(2000L), wageringMultiplier = 30L, claimWindowDays = Some(3), currency = Some(Eur),
    createdAt = at, updatedAt = at)
  private val freeBonus = Offer(id = "o-free", name = "Free", offerType = OfferType.NoDeposit, noDepositRequired = true,
    maxBonusValue = Some(1000L), wageringMultiplier = 10L, currency = Some(Eur), maxConcurrentGrants = Some(1), createdAt = at,
    updatedAt = at)

  private def target(kit: Kit, o: Offer, source: Source = Source.CustomerioWebhook): String =
    kit.call(PlayerRewards.target)(Target(o, source, at = at)).replyValue.outcome match
      case Outcome.Visible(id, _) => id
      case other                  => fail(s"not visible: $other")

  private def select(kit: Kit, o: Offer, open: Int = 0) = kit.call(PlayerRewards.select)(Select(o, open, Eur, at))

  private def pendingOn(kit: Kit, o: Offer = matchOffer): String =
    val id = target(kit, o)
    select(kit, o): Unit
    id

  private def deposit(amount: Long, ref: String = "pay-1", ordinal: Int = 1, when: Instant = at.plus(1, HOURS)) =
    DepositSeen(amount, "EUR", ordinal, ref, when)

  private def confirm(kit: Kit, d: DepositSeen, offer: Option[Offer] = Some(matchOffer)) =
    val pending = kit.currentState.pending.map((id, _) => PendingOffer(id, offer))
    kit.call(PlayerRewards.depositConfirmed)(DepositConfirmed(d, pending, 0, d.at))

  test("a target reserves a reward AVAILABLE with how it was given; a repeat answers the same reward and writes nothing"):
    val kit = record
    val r   = kit.call(PlayerRewards.target)(Target(matchOffer, Source.AffiliateCode, Some("spring"), Some("aff-1"), at))
    val Outcome.Visible(id, true) = r.replyValue.outcome: @unchecked
    assertEquals(r.events.toVector, Vector(OfferMadeVisible(id, "o-match", Source.AffiliateCode, Some("spring"), Some("aff-1"), at)))
    assertEquals(kit.currentState.rewards(id).status, RewardStatus.Available)
    val again = kit.call(PlayerRewards.target)(Target(matchOffer, Source.AffiliateCode, at = at))
    assertEquals((again.replyValue.outcome, again.events.toVector), (Outcome.Visible(id, fresh = false), Vector.empty))

  test("an offer the player holds no reward of is offer_not_visible"):
    assertEquals(select(record, matchOffer).replyValue.outcome, Outcome.Refused("offer_not_visible"))

  test("a deposit offer selected is pending, unevaluated; selected again it answers the same and writes nothing"):
    val kit = record
    val id  = target(kit, matchOffer)
    val r   = select(kit, matchOffer)
    assertEquals((r.replyValue.outcome, r.events.toVector), (Outcome.Selected(id), Vector(RewardSelected(id, at))))
    assertEquals(kit.currentState.rewards(id).selectedAt, Some(at))
    assertEquals(select(kit, matchOffer).events.toVector, Vector.empty)

  test("a no-deposit offer selected is claimed at once with its fixed amount, the source kept from targeting"):
    val kit = record
    val id  = target(kit, freeBonus, Source.PlayerCode)
    val r   = select(kit, freeBonus)
    val Outcome.Claimed(claim) = r.replyValue.outcome: @unchecked
    assertEquals((claim.rewardId, claim.decision.amount, claim.decision.wageringRequirement, claim.source, claim.paymentRef),
      (id, 1000L, 10000L, Source.PlayerCode, None))
    assertEquals(r.events.toVector, Vector(Claimed(claim)))
    assert(kit.currentState.rewards(id).claiming)
    assertEquals(select(kit, freeBonus).replyValue.outcome, Outcome.Refused("ALREADY_CLAIMED"))

  test("two selections at once: the second sees the first's claim as open and is ACTIVE_BONUS_CONFLICT"):
    val kit    = record
    val second = freeBonus.copy(id = "o-free-2")
    target(kit, freeBonus): Unit
    target(kit, second): Unit
    select(kit, freeBonus): Unit
    val r = select(kit, second)
    assertEquals((r.replyValue.outcome.getClass.getSimpleName, r.events.toVector), ("Declined", Vector.empty))
    assertEquals(r.replyValue.outcome.asInstanceOf[Outcome.Declined].reason, "ACTIVE_BONUS_CONFLICT")

  test("the wallet's open records count too (EE:164-165)"):
    val kit = record
    target(kit, freeBonus): Unit
    assertEquals(select(kit, freeBonus, open = 1).replyValue.outcome.asInstanceOf[Outcome.Declined].reason, "ACTIVE_BONUS_CONFLICT")

  test("a deselect returns every pending selection to available"):
    val kit = record
    val id  = pendingOn(kit)
    val r   = kit.call(PlayerRewards.deselect)(Deselect(at))
    assertEquals((r.replyValue.outcome, r.events.toVector), (Outcome.Deselected(Vector(id)), Vector(RewardsDeselected(Vector(id), at))))
    assertEquals(kit.currentState.rewards(id).status, RewardStatus.Available)
    assertEquals(kit.call(PlayerRewards.deselect)(Deselect(at)).events.toVector, Vector.empty)

  test("a confirming deposit claims the pending selection with the matched amount; reported again it writes nothing and answers the claim"):
    val kit = record
    val id  = pendingOn(kit)
    val r   = confirm(kit, deposit(5000L))
    val Outcome.Claimed(claim) = r.replyValue.outcome: @unchecked
    assertEquals((claim.rewardId, claim.decision.amount, claim.decision.wageringRequirement, claim.paymentRef),
      (id, 5000L, 150000L, Some("pay-1")))
    assertEquals(r.events.toVector, Vector(DepositRecorded(deposit(5000L)), Claimed(claim)))
    val again = confirm(kit, deposit(5000L))
    assertEquals((again.events.toVector, again.replyValue.outcome), (Vector.empty, Outcome.Claimed(claim)))

  test("a deposit below the minimum is recorded and the selection stays pending with the reason"):
    val kit = record
    val id  = pendingOn(kit)
    val r   = confirm(kit, deposit(1000L))
    assertEquals(r.replyValue.outcome, Outcome.Declined(Some(id), "BELOW_MIN_DEPOSIT"))
    assertEquals(r.events.toVector.last, DeclineRecorded(id, "BELOW_MIN_DEPOSIT", at.plus(1, HOURS)))
    assertEquals((kit.currentState.rewards(id).status, kit.currentState.pending.map(_._1)), (RewardStatus.Pending, Some(id)))
    val next = confirm(kit, deposit(3000L, "pay-2", 2))
    assertEquals(next.replyValue.outcome.asInstanceOf[Outcome.Claimed].claim.decision.amount, 3000L)

  test("a deposit past the selection's window, or for an offer no longer live, expires the selection"):
    val kit = record
    val id  = pendingOn(kit)
    val r   = confirm(kit, deposit(5000L, when = at.plus(3, DAYS).plus(1, HOURS)))
    assertEquals((r.replyValue.outcome, r.events.toVector.last), (Outcome.Expired(id), PendingExpired(id, at.plus(3, DAYS).plus(1, HOURS))))
    val other = record
    val id2   = pendingOn(other)
    assertEquals(confirm(other, deposit(5000L), Some(matchOffer.copy(active = false))).replyValue.outcome, Outcome.Expired(id2))
    val gone = record
    val id3  = pendingOn(gone)
    assertEquals(confirm(gone, deposit(5000L), None).replyValue.outcome, Outcome.Expired(id3))

  test("a deposit with nothing pending is recorded only, and sets the player's currency"):
    val kit = record
    val r   = confirm(kit, deposit(5000L))
    assertEquals((r.replyValue.outcome, r.events.toVector), (Outcome.Idle, Vector(DepositRecorded(deposit(5000L)))))
    assertEquals((kit.currentState.currency, kit.currentState.lastDeposit.map(_.amount)), (Some("EUR"), Some(5000L)))

  test("the reward's state reported: a claim ends when the reward moves or the wallet refuses; a report changing nothing writes nothing"):
    val kit = record
    val id  = target(kit, freeBonus)
    select(kit, freeBonus): Unit
    val refused = kit.call(PlayerRewards.rewardChanged)(RewardChanged(id, "o-free", RewardStatus.Available, false, awardRefused = true, at))
    assertEquals(refused.events.size, 1)
    assert(!kit.currentState.rewards(id).claiming)
    // Claimed again, under a fresh id (the wallet answers the refused key with its refusal).
    val again = select(kit, freeBonus).replyValue.outcome.asInstanceOf[Outcome.Claimed].claim.rewardId
    kit.call(PlayerRewards.rewardChanged)(RewardChanged(again, "o-free", RewardStatus.Active, false, false, at)): Unit
    assertEquals((kit.currentState.rewards(again).status, kit.currentState.rewards(again).claiming), (RewardStatus.Active, false))
    assertEquals(kit.call(PlayerRewards.rewardChanged)(RewardChanged(again, "o-free", RewardStatus.Active, false, false, at)).events.toVector,
      Vector.empty)

  test("a reward the record never made is added when reported"):
    val kit = record
    kit.call(PlayerRewards.rewardChanged)(RewardChanged("r-x", "o-x", RewardStatus.Active, false, false, at)): Unit
    assertEquals(kit.currentState.rewards("r-x").offerId, "o-x")
    assertEquals(kit.currentState.snapshot.rewards.map(_.offerId), Vector("o-x"))

  test("a claim on another path reserves a new reward with it, or claims the one the player may select"):
    val kit = record
    val r   = kit.call(PlayerRewards.claim)(ClaimOffer(freeBonus, operator = false, None, Source.CustomerioWebhook, None, None, 0, Eur, at))
    val Outcome.Claimed(claim) = r.replyValue.outcome: @unchecked
    assertEquals(r.events.toVector.map(_.getClass.getSimpleName), Vector("OfferMadeVisible", "Claimed"))
    assertEquals(kit.currentState.rewards(claim.rewardId).source, Source.CustomerioWebhook)
    val id = target(kit, matchOffer)
    val c2 = kit.call(PlayerRewards.claim)(ClaimOffer(matchOffer, operator = false,
      Some(io.eitheror.casino.rewards.eligibility.Deposit(3000L, Eur, 1)), Source.CustomerioWebhook, None, None, 0, Eur, at))
    assertEquals(c2.replyValue.outcome.asInstanceOf[Outcome.Claimed].claim.rewardId, id)
    assertEquals(c2.events.toVector.map(_.getClass.getSimpleName), Vector("Claimed"))

  test("a campaign's claim of an offer the player holds a claim of is ALREADY_CLAIMED and writes nothing; an operator's is not"):
    val kit       = record
    val freeBonus = this.freeBonus.copy(maxConcurrentGrants = None)
    val first = kit.call(PlayerRewards.claim)(ClaimOffer(freeBonus, operator = false, None, Source.CustomerioWebhook, None, None, 0, Eur, at))
    assert(first.replyValue.outcome.isInstanceOf[Outcome.Claimed])
    val again = kit.call(PlayerRewards.claim)(ClaimOffer(freeBonus, operator = false, None, Source.CustomerioWebhook, None, None, 0, Eur, at))
    assertEquals((again.replyValue.outcome, again.events.toVector), (Outcome.Refused(PlayerRewards.AlreadyClaimed), Vector.empty))
    val op = kit.call(PlayerRewards.claim)(ClaimOffer(freeBonus, operator = true, None, Source.OperatorManual, None, None, 0, Eur, at))
    assert(op.replyValue.outcome.isInstanceOf[Outcome.Claimed], op.replyValue.outcome.toString)

  test("an operator's claim is a reward of its own: the reward the player may select stays theirs"):
    val kit = record
    val id  = target(kit, matchOffer)
    val c   = kit.call(PlayerRewards.claim)(ClaimOffer(matchOffer, operator = true, None, Source.OperatorManual, None, None, 0, Eur, at))
    val Outcome.Claimed(claim) = c.replyValue.outcome: @unchecked
    assertNotEquals(claim.rewardId, id)
    assertEquals(c.events.toVector.map(_.getClass.getSimpleName), Vector("OfferMadeVisible", "Claimed"))
    assertEquals(kit.currentState.unclaimed(matchOffer.id).map(_._1), Some(id))

  test("a campaign's claim skips the code rule when told the code was verified"):
    val kit   = record
    val coded = freeBonus.copy(id = "o-coded", redemptionCodes = Vector(io.eitheror.casino.rewards.offers.RedemptionCode("SECRET")))
    val no    = kit.call(PlayerRewards.claim)(ClaimOffer(coded, operator = false, None, Source.CustomerioWebhook, None, None, 0, Eur, at))
    assertEquals(no.replyValue.outcome, Outcome.Declined(None, "INVALID_CODE"))
    val yes = kit.call(PlayerRewards.claim)(ClaimOffer(coded, operator = false, None, Source.CustomerioWebhook, None, None, 0, Eur, at,
      codeVerified = true))
    assert(yes.replyValue.outcome.isInstanceOf[Outcome.Claimed], yes.replyValue.outcome.toString)

  test("the provisional opening is the offer's fixed amount in the currency given"):
    val o = Openings.provisional(matchOffer, "p-1", Eur, Source.PlayerCode, None, None)
    assertEquals((o.decision.amount, o.decision.currency, o.offerId, o.playerId), (50000L, Eur, "o-match", "p-1"))

  // ── the player's standing from casino.players (T039, research R9) ─────────────────────────

  private def fact(kit: Kit, change: PlayerChange, detection: Option[Boolean] = None) =
    kit.call(PlayerRewards.playerFact)(PlayerFact(change, detection, at))

  test("the registration, the check and its block, the flag and the tier are recorded; a fact repeated writes nothing"):
    val kit = record
    val reg = at.minusSeconds(300)
    assertEquals(fact(kit, PlayerChange.Registered(reg, Some("EUR"))).events.toVector, Vector(RegistrationRecorded(reg, Some("EUR"), at)))
    assertEquals(fact(kit, PlayerChange.CheckStarted(reg)).events.toVector, Vector(CheckStarted(reg, at)))
    assertEquals(fact(kit, PlayerChange.CheckStarted(reg)).events.toVector, Vector.empty)
    assertEquals(fact(kit, PlayerChange.Blocked).events.toVector, Vector(NoDepositBlocked(at)))
    assertEquals(fact(kit, PlayerChange.Flag(true)).events.toVector, Vector(FlagChanged(true, at)))
    assertEquals(fact(kit, PlayerChange.Tier("gold")).events.toVector, Vector(TierChanged("gold", at)))
    val s = kit.currentState
    assertEquals((s.registeredAt, s.currency, s.checkInProgress, s.noDepositBlocked, s.suspectedDuplicate, s.tier),
      (Some(reg), Some("EUR"), Some(at), true, true, Some("gold")))
    assertEquals((s.snapshot.checkInProgress, s.snapshot.noDepositBlocked, s.snapshot.suspectedDuplicate), (true, true, true))
    assertEquals(fact(kit, PlayerChange.CheckEnded("weak")).events.toVector, Vector(CheckEnded("weak", at)))
    assertEquals(fact(kit, PlayerChange.CheckEnded("weak")).events.toVector, Vector.empty)
    assertEquals(fact(kit, PlayerChange.BlockCleared).events.toVector, Vector(BlockCleared(at)))
    assertEquals(fact(kit, PlayerChange.Flag(false)).events.toVector, Vector(FlagChanged(false, at)))
    assertEquals((kit.currentState.checkInProgress, kit.currentState.noDepositBlocked, kit.currentState.suspectedDuplicate),
      (None, false, false))

  test("a check started before the registration is seen sets the registration time; the registration's currency is the last choice"):
    val kit = record
    val reg = at.minusSeconds(60)
    fact(kit, PlayerChange.CheckStarted(reg)): Unit
    assertEquals(kit.currentState.registeredAt, Some(reg))
    confirm(kit, deposit(5000L)): Unit
    fact(kit, PlayerChange.Registered(reg, Some("GBP"))): Unit
    assertEquals(kit.currentState.currency, Some("EUR"))

  test("the detection switch a fact carries is kept; none carried leaves it"):
    val kit = record
    assertEquals(fact(kit, PlayerChange.Flag(true), Some(false)).events.toVector, Vector(DetectionChanged(false, at), FlagChanged(true, at)))
    assertEquals(fact(kit, PlayerChange.Tier("gold")).events.toVector, Vector(TierChanged("gold", at)))
    assert(!kit.currentState.detectionOn)
    target(kit, freeBonus): Unit
    val r = select(kit, freeBonus)
    assert(r.replyValue.outcome.isInstanceOf[Outcome.Claimed], r.replyValue.outcome.toString)
    fact(kit, PlayerChange.CheckEnded("clear"), Some(true)): Unit
    assert(kit.currentState.detectionOn)

  // ── two selections at once, and a claim after the wallet refused (T040) ───────────────────

  test("a second selection while one is pending is ACTIVE_BONUS_CONFLICT and writes nothing; the first may be selected again"):
    val kit    = record
    val other  = matchOffer.copy(id = "o-match-2")
    val first  = pendingOn(kit)
    target(kit, other): Unit
    target(kit, freeBonus.copy(maxConcurrentGrants = None)): Unit
    val r = select(kit, other)
    assertEquals((r.replyValue.outcome.asInstanceOf[Outcome.Declined].reason, r.events.toVector), ("ACTIVE_BONUS_CONFLICT", Vector.empty))
    assertEquals(select(kit, freeBonus.copy(maxConcurrentGrants = None)).replyValue.outcome.asInstanceOf[Outcome.Declined].reason,
      "ACTIVE_BONUS_CONFLICT")
    assertEquals(select(kit, matchOffer).replyValue.outcome, Outcome.Selected(first))
    kit.call(PlayerRewards.deselect)(Deselect(at)): Unit
    assert(select(kit, other).replyValue.outcome.isInstanceOf[Outcome.Selected])

  test("a claim after the wallet refused the award is made under a fresh id replacing the refused reward"):
    val kit = record
    val id  = target(kit, freeBonus, Source.PlayerCode)
    select(kit, freeBonus): Unit
    kit.call(PlayerRewards.rewardChanged)(RewardChanged(id, "o-free", RewardStatus.Available, false, awardRefused = true, at)): Unit
    val r = select(kit, freeBonus)
    val Outcome.Claimed(claim) = r.replyValue.outcome: @unchecked
    assertNotEquals(claim.rewardId, id)
    assertEquals((claim.replaces, claim.source), (Some(id), Source.PlayerCode))
    val s = kit.currentState
    assertEquals((s.rewards.keySet, s.retired), (Set(claim.rewardId), Set(id)))
    assert(s.rewards(claim.rewardId).claiming)
    // The retired reward's own reports (its expiry) are not read.
    assertEquals(kit.call(PlayerRewards.rewardChanged)(RewardChanged(id, "o-free", RewardStatus.Expired, false, false, at)).events.toVector,
      Vector.empty)
    assertEquals(kit.currentState.snapshot.rewards.size, 1)

  test("a deposit after the wallet refused the pending reward's award claims it under a fresh id, still pending until awarded"):
    val kit = record
    val id  = pendingOn(kit)
    confirm(kit, deposit(5000L)): Unit
    kit.call(PlayerRewards.rewardChanged)(RewardChanged(id, "o-match", RewardStatus.Pending, false, awardRefused = true, at)): Unit
    assertEquals(kit.currentState.pending.map(_._1), Some(id))
    val again = confirm(kit, deposit(4000L, "pay-2", 2))
    val Outcome.Claimed(claim) = again.replyValue.outcome: @unchecked
    assertEquals((claim.replaces, claim.decision.amount, claim.paymentRef), (Some(id), 4000L, Some("pay-2")))
    val held = kit.currentState.rewards(claim.rewardId)
    assertEquals((held.status, held.selectedAt), (RewardStatus.Pending, Some(at)))
    assertEquals(confirm(kit, deposit(4000L, "pay-2", 2)).replyValue.outcome, Outcome.Claimed(claim))

  // ── a code entered (FR-019) ─────────────────────────────────────────────

  private def enter(kit: Kit, code: String, found: Option[CodeFound], open: Int = 0) =
    kit.call(PlayerRewards.enterCode)(EnterCode(code, found, open, Eur, at))

  private val coded = matchOffer.copy(id = "o-coded", redemptionCodes = Vector(io.eitheror.casino.rewards.offers.RedemptionCode("WELCOME")))

  test("a code naming nothing live is invalid_code, recorded invalid and nothing made visible"):
    val kit = record
    val r   = enter(kit, " nope ", None)
    assertEquals(r.replyValue.outcome, Outcome.Refused("invalid_code"))
    assertEquals(r.events.toVector, Vector(CodeEntered("nope", CodeOutcome.Invalid, at)))
    assertEquals(kit.currentState.rewards, Map.empty)

  test("a brand code matched makes its offer visible with the code as entered, trimmed, in the same write"):
    val kit = record
    val r   = enter(kit, " welcome ", Some(CodeFound(coded, Source.PlayerCode)))
    val Outcome.Visible(id, true) = r.replyValue.outcome: @unchecked
    assertEquals(r.events.toVector, Vector(CodeEntered("welcome", CodeOutcome.Matched, at),
      OfferMadeVisible(id, "o-coded", Source.PlayerCode, Some("welcome"), None, at)))
    // Entered again while held: the same reward, recorded again.
    val again = enter(kit, "WELCOME", Some(CodeFound(coded, Source.PlayerCode)))
    assertEquals((again.replyValue.outcome, again.events.toVector),
      (Outcome.Visible(id, fresh = false), Vector(CodeEntered("WELCOME", CodeOutcome.Matched, at))))

  test("an affiliate code is verified by the programme's document: the offer need not carry it; the tag is kept"):
    val kit = record
    val r   = enter(kit, "AFF100", Some(CodeFound(coded, Source.AffiliateCode, Some("tag-1"))))
    val Outcome.Visible(id, true) = r.replyValue.outcome: @unchecked
    assertEquals(kit.currentState.rewards(id), HeldReward("o-coded", source = Source.AffiliateCode, code = Some("AFF100"),
      tag = Some("tag-1"), openedAt = at))

  test("a decline is ineligible, recorded so; another offer's selection pending is ACTIVE_BONUS_CONFLICT"):
    val kit = record
    val r   = enter(kit, "x", Some(CodeFound(freeBonus, Source.PlayerCode)), open = 1)
    assertEquals(r.replyValue.outcome, Outcome.Declined(None, "ACTIVE_BONUS_CONFLICT"))
    assertEquals(r.events.toVector, Vector(CodeEntered("x", CodeOutcome.Ineligible, at)))
    val other = record
    pendingOn(other): Unit
    assertEquals(enter(other, "welcome", Some(CodeFound(coded, Source.PlayerCode))).replyValue.outcome,
      Outcome.Declined(None, "ACTIVE_BONUS_CONFLICT"))

  test("no minimum deposit is asked of a code"):
    val kit = record
    assert(enter(kit, "welcome", Some(CodeFound(coded.copy(minDepositAmount = Some(1_000_000L)), Source.PlayerCode)))
      .replyValue.outcome.isInstanceOf[Outcome.Visible])

  test("bonus_code_entered from a code entry only, with its outcome"):
    import io.eitheror.casino.contract.facts.BonusCodeEntered
    import io.eitheror.casino.rewards.facts.CodesPublisher
    assertEquals(CodesPublisher.fact("p-1", CodeEntered("AFF100", CodeOutcome.Matched, at)),
      Some(BonusCodeEntered("p-1", "AFF100", at, Some("matched"))))
    assertEquals(CodesPublisher.fact("p-1", RewardSelected("r-1", at)), None)

  test("a round naming free rounds not held is recorded once per round id"):
    val kit   = record
    val round = UnmatchedRound("rd-1", "fr-unknown", 150L, at)
    val first = kit.call(PlayerRewards.roundUnmatched)(RecordUnmatchedRound(round))
    assertEquals(first.events.toVector, Vector(RoundUnmatched(round)))
    assertEquals(kit.call(PlayerRewards.roundUnmatched)(RecordUnmatchedRound(round.copy(winCents = 9L))).events.toVector, Vector.empty)
    assertEquals(kit.currentState.unmatchedRounds, Vector(round))
