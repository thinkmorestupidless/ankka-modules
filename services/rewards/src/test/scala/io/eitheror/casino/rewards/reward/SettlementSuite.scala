package io.eitheror.casino.rewards.reward

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import io.eitheror.casino.rewards.reward.RewardStatus.*
import io.eitheror.casino.rewards.reward.Settled.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}

import java.time.Instant

/**
 * Free-rounds settlement (FR-024, research R8): the cases of the original's `settle_free_spins_round` and
 * `finalize_free_spins_grant` handler tests, with the port's change for a round after finalisation.
 */
class SettlementSuite extends ScalaCheckSuite:

  private val real       = Destination.RealBalance
  private val restricted = Destination.RestrictedFunds
  private val expiresAt  = Instant.parse("2026-11-30T12:00:00Z")
  private val past       = expiresAt.plusSeconds(1)

  private def counted(o: Either[Skip, Settled]): (Tally, Option[Finalisation]) = o match
    case Right(Counted(t, f)) => (t, f)
    case other                => fail(s"expected the round counted, got $other")

  // settle_free_spins_round

  test("the first of two rounds accumulates without paying (TestFirstOfTwoRoundsAccumulatesWithoutAwarding)"):
    val (t, f) = counted(Settlement.round(Active, Tally(2), real, "r1", 1500))
    assertEquals(t, Tally(2, Set("r1"), 1, 1500))
    assertEquals(t.remaining, 1)
    assertEquals(f, None)

  test("the second of two pays the full accumulated amount, uncapped (TestSecondOfTwoRoundsAwardsFullAccumulatedUncapped)"):
    val (t1, _) = counted(Settlement.round(Active, Tally(2), restricted, "r1", 1500))
    val (t2, f) = counted(Settlement.round(Active, t1, restricted, "r2", 250_000))
    assertEquals(t2.accumulated, 251_500L)
    assertEquals(
      f,
      Some(Finalisation(251_500, Some(Payment(restricted, 251_500)), Closed, Settlement.Finalised))
    )

  test("the destination names the wallet step: real balance credits, restricted funds awards winnings (TestRealBalanceDestinationCredits)"):
    val (_, f) = counted(Settlement.round(Active, Tally(1), real, "r1", 700))
    val p      = f.flatMap(_.payment).getOrElse(fail("expected a payment"))
    assertEquals(p, Payment(real, 700))
    assertEquals(p.step, "credit")
    assertEquals(p.key("rw1"), "rw1/credit")
    assertEquals(Payment(restricted, 700).step, "winnings")
    assertEquals(Payment(restricted, 700).key("rw1"), "rw1/winnings")

  test("the last round with nothing won pays nothing and still closes (TestLastSpinWithNoWinningsFinalizesWithoutAwarding)"):
    val (_, f) = counted(Settlement.round(Active, Tally(1), restricted, "r1", 0))
    assertEquals(f, Some(Finalisation(0, None, Closed, Settlement.Finalised)))

  test("a round settling again changes nothing (TestRedispatchedRoundIsNotCountedTwice)"):
    val (t, _) = counted(Settlement.round(Active, Tally(3), restricted, "r1", 1000))
    for _ <- 1 to 3 do assertEquals(Settlement.round(Active, t, restricted, "r1", 1000), Left(Skip.AlreadyCounted))
    assertEquals(t.used, 1)
    assertEquals(t.accumulated, 1000L)

  test("completion is judged on the larger of rounds used and rounds seen (TestSettledRoundListCoversCrashBeforeIncrement)"):
    // A tally carried over with more rounds seen than counted as used, and the other way round.
    val (_, f1) = counted(Settlement.round(Active, Tally(3, Set("a", "b"), 1, 100), real, "c", 0))
    assert(f1.isDefined)
    val (_, f2) = counted(Settlement.round(Active, Tally(3, Set("a"), 2, 100), real, "c", 0))
    assert(f2.isDefined)
    assertEquals(Tally(3, Set("a"), 2).remaining, 1)

  test("a revoked, cancelled or forfeited reward neither counts nor pays a round (TestRevokedGrantIsSkippedWithoutWrites)"):
    for s <- Vector(Revoked, Cancelled, Forfeited) do
      assertEquals(Settlement.round(s, Tally(5, Set("a"), 1, 100), real, "r9", 1000), Left(Skip.NotLive), s.wire)

  // The late round (FR-024, a change from the original)

  test("a round after finalisation pays its own win, to the same destination under its own key"):
    val finalised = Tally(20, Set("a"), 1, 500).finalise(500)
    for s <- Vector(Closed, Expired) do
      Settlement.round(s, finalised, restricted, "late1", 3000) match
        case Right(Late(t, Some(p))) =>
          assertEquals(p, Payment(restricted, 3000, Some("late1")))
          assertEquals(p.step, "late-late1")
          assertEquals(p.key("rw1"), "rw1/late/late1")
          assertEquals(t.seen, Set("a", "late1"))
          assertEquals(t.accumulated, 500L, "the amount paid at finalisation is not changed")
          assertEquals(t.paid, Some(500L))
        case other => fail(s"${s.wire}: expected a late payment, got $other")

  test("a late round with nothing won pays nothing but is remembered; the same late round again is skipped"):
    val finalised = Tally(2, Set("a", "b"), 2, 0).finalise(0)
    Settlement.round(Closed, finalised, real, "c", 0) match
      case Right(Late(t, None)) =>
        assertEquals(Settlement.round(Closed, t, real, "c", 0), Left(Skip.AlreadyCounted))
      case other => fail(s"expected a late round with no payment, got $other")

  test("a late round once paid is not paid again"):
    Settlement.round(Expired, Tally(5).finalise(0), real, "x", 900) match
      case Right(Late(t, Some(_))) => assertEquals(Settlement.round(Expired, t, real, "x", 900), Left(Skip.AlreadyCounted))
      case other                   => fail(s"expected a late payment, got $other")

  test("a terminal reward never finalised as free rounds takes no round"):
    assertEquals(Settlement.round(Closed, Tally(5), real, "r1", 100), Left(Skip.NotLive))
    assertEquals(Settlement.round(Expired, Tally(5), real, "r1", 100), Left(Skip.NotLive))

  // finalize_free_spins_grant

  test("expiry with nothing played pays nothing and expires (TestZeroUsageExpiryNoAward)"):
    assertEquals(
      Settlement.expire(Active, Tally(20), real, expiresAt, past),
      Right(Finalisation(0, None, Expired, Settlement.ExpiredUnused))
    )

  test("expiry with rounds played and nothing won expires too, as the original"):
    assertEquals(
      Settlement.expire(Active, Tally(20, Set("a", "b"), 2, 0), real, expiresAt, past),
      Right(Finalisation(0, None, Expired, Settlement.ExpiredUnused))
    )

  test("expiry with something won pays it in full, uncapped, and closes (TestPartialUsageExpiryAwardsUncapped)"):
    assertEquals(
      Settlement.expire(Active, Tally(20, Set("a"), 1, 987_654), restricted, expiresAt, past),
      Right(Finalisation(987_654, Some(Payment(restricted, 987_654)), Closed, Settlement.Finalised))
    )

  test("expiry before the deadline is not due (TestFutureExpiryIsSkippedNotDue)"):
    assertEquals(Settlement.expire(Active, Tally(20), real, expiresAt, expiresAt.minusSeconds(1)), Left(Skip.NotDue))
    assert(Settlement.expire(Active, Tally(20), real, expiresAt, expiresAt).isRight, "due at the deadline itself")

  test("expiry of a finalised reward is skipped (TestAlreadyUsedIsSkippedAlreadyFinalized)"):
    val done = Tally(1, Set("a"), 1, 10).finalise(10)
    for s <- Vector(Active, Closed, Expired) do
      assertEquals(Settlement.expire(s, done, real, expiresAt, past), Left(Skip.AlreadyFinalised), s.wire)

  test("expiry of a revoked or cancelled reward is skipped (TestRevokedIsSkippedGrantRevoked)"):
    for s <- Vector(Revoked, Cancelled, Forfeited) do
      assertEquals(Settlement.expire(s, Tally(5, Set("a"), 1, 100), real, expiresAt, past), Left(Skip.NotLive), s.wire)

  test("the finalisation's state move is one the table allows from active"):
    for s <- Vector(Closed, Expired) do assert(Transitions.canMove(Active, s))

  // The destination

  test("the offer's destination: bonus_balance awards restricted funds, anything else (or none) credits"):
    assertEquals(Destination.from(Some("bonus_balance")), restricted)
    assertEquals(Destination.from(Some("real_balance")), real)
    assertEquals(Destination.from(None), real)
    for d <- Destination.values do assertEquals(readFromString[Destination](writeToString(d)), d)
    assertEquals(writeToString(restricted), "\"bonus_balance\"")

  // Properties

  private val rounds: Gen[Vector[(String, Long)]] =
    Gen.listOf(Gen.zip(Gen.oneOf("a", "b", "c", "d", "e", "f"), Gen.choose(0L, 5_000L))).map(_.toVector)

  property("each round counts once, the total is the sum of distinct rounds' wins, and it finalises once at the count"):
    Prop.forAll(Gen.choose(1, 6), rounds) { (count, plays) =>
      var status    = Active
      var tally     = Tally(count)
      var finalised = Vector.empty[Finalisation]
      var paidLate  = 0L
      var firstWins = Map.empty[String, Long]
      for (id, win) <- plays do
        if !firstWins.contains(id) then firstWins += id -> win
        Settlement.round(status, tally, real, id, win) match
          case Right(Counted(t, f)) =>
            tally = t
            f.foreach { fin =>
              finalised :+= fin
              tally = tally.finalise(fin.amount)
              status = fin.status
            }
          case Right(Late(t, p)) =>
            tally = t
            paidLate += p.fold(0L)(_.amount)
          case Left(_) => ()
      val total = firstWins.values.sum
      val paid  = finalised.map(_.amount).sum + paidLate
      finalised.size <= 1 &&
      (finalised.nonEmpty == (firstWins.size >= count)) &&
      (if finalised.nonEmpty then paid == total else tally.accumulated == total) &&
      tally.used <= count
    }
