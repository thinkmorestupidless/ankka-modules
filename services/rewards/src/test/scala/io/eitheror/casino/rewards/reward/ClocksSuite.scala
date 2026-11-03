package io.eitheror.casino.rewards.reward

import io.eitheror.casino.rewards.reward.RewardEvent.*
import io.eitheror.casino.rewards.reward.RewardStatus.{Active, ConvertedPendingCap, Pending}
import io.eitheror.casino.rewards.reward.TimerChange.{Cancel as Off, Set as On}

import java.time.Instant

/** Research R10: the timers each event sets and cancels, and what each does when due. */
class ClocksSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-11-01T12:00:00Z")
  private val due = at.plusSeconds(86400)

  test("a selection sets the pending timer; a deselection cancels it"):
    assertEquals(Clocks.changes(Selected(at, due), freeRounds = false), Vector(On(Clock.Pending, due)))
    assertEquals(Clocks.changes(Deselected(at), freeRounds = false), Vector(Off(Clock.Pending)))

  test("activation cancels the pending timer and sets the claim timer, or the rounds timer for free rounds"):
    assertEquals(Clocks.changes(Activated(None, due, None, at), freeRounds = false), Vector(Off(Clock.Pending), On(Clock.Claim, due)))
    assertEquals(Clocks.changes(Activated(None, due, None, at), freeRounds = true), Vector(Off(Clock.Pending), On(Clock.Rounds, due)))

  test("a wagering deadline apart from the claim deadline sets its own timer"):
    val later = due.plusSeconds(60)
    assertEquals(Clocks.changes(Activated(None, due, Some(later), at), freeRounds = false),
      Vector(Off(Clock.Pending), On(Clock.Claim, due), On(Clock.Wagering, later)))
    assertEquals(Clocks.changes(Activated(None, due, Some(due), at), freeRounds = false), Vector(Off(Clock.Pending), On(Clock.Claim, due)))

  test("conversion swaps the claim and wagering timers for the cap's"):
    assertEquals(Clocks.changes(Converted(at, due), freeRounds = false),
      Vector(Off(Clock.Claim), Off(Clock.Wagering), On(Clock.Cap, due)))

  test("every terminal event cancels every timer; finalisation the rounds timer; the rest none"):
    val terminal = Vector(
      RewardEvent.Forfeited(ForfeitReason.BonusExhausted, None, at),
      RewardEvent.Closed(CloseReason.CapExpired, true, at),
      RewardEvent.Expired(ExpireReason.ClaimExpired, Active, at),
      RewardEvent.Cancelled(CancelReason.PlayerCancel, 0L, at),
      RewardEvent.Revoked(RevokeMode.Forfeit, "op-1", None, 0L, at))
    terminal.foreach(e => assertEquals(Clocks.changes(e, freeRounds = false), Clock.values.toVector.map(Off(_))))
    assertEquals(Clocks.changes(FreeRoundsFinalised(0L, at), freeRounds = true), Vector(Off(Clock.Rounds)))
    assertEquals(Clocks.changes(AwardRefused("X", at), freeRounds = false), Vector.empty)
    assertEquals(Clocks.changes(RoundCounted("r", 1L, 0, at), freeRounds = true), Vector.empty)

  private val state = RewardState(opened = true, rewardId = "r-1", status = Active, expiresAt = Some(due))
  private val rounds = state.copy(freeRounds = Some(FreeRoundsState(FreeRoundsTerms("g", "st8", 1, 1L, Destination.RealBalance), Tally(1))))

  test("each timer when due does its own move, and nothing once the reward left the state it watched"):
    assertEquals(Clocks.onDue(state.copy(status = Pending), Clock.Pending, due), Fire.Expire(ExpireReason.PendingExpired))
    assertEquals(Clocks.onDue(state, Clock.Claim, due), Fire.Wallet("forfeit", Ending.Expire(ExpireReason.ClaimExpired)))
    assertEquals(Clocks.onDue(state.copy(status = ConvertedPendingCap, capExpiresAt = Some(due)), Clock.Cap, due),
      Fire.Wallet("close-cap", Ending.Close(CloseReason.CapExpired)))
    assertEquals(Clocks.onDue(rounds, Clock.Rounds, due), Fire.Finalise)
    assertEquals(Clocks.onDue(state.copy(status = RewardStatus.Closed), Clock.Claim, due), Fire.Idle)
    assertEquals(Clocks.onDue(state, Clock.Pending, due), Fire.Idle)
    assertEquals(Clocks.onDue(rounds, Clock.Claim, due), Fire.Idle)

  test("a timer for a deadline the reward no longer holds does nothing"):
    assertEquals(Clocks.onDue(state, Clock.Claim, at), Fire.Idle)

  test("the wagering window expires as such, unless the claim deadline is due too, which wins"):
    val later = due.plusSeconds(60)
    assertEquals(Clocks.onDue(state.copy(expiresAt = Some(later.plusSeconds(1)), wageringExpiresAt = Some(later)), Clock.Wagering, later),
      Fire.Wallet("forfeit", Ending.Expire(ExpireReason.WageringWindowExpired)))
    assertEquals(Clocks.onDue(state.copy(wageringExpiresAt = Some(later)), Clock.Wagering, later),
      Fire.Wallet("forfeit", Ending.Expire(ExpireReason.ClaimExpired)))

  test("timers are named by the reward"):
    assertEquals(Clock.values.toVector.map(_.name("r-1")), Vector("pending-r-1", "claim-r-1", "wagering-r-1", "cap-r-1", "rounds-r-1"))
