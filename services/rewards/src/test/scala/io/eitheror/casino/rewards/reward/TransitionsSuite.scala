package io.eitheror.casino.rewards.reward

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import io.eitheror.casino.rewards.reward.RewardStatus.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}

/**
 * The original's table (`RewardStatus.scala:60-66`) and `RewardStatusSpec`'s cases: the allowed pairs exactly,
 * every other pair refused naming both, a repeat a no-op, no way out of a terminal state (FR-009).
 */
class TransitionsSuite extends ScalaCheckSuite:

  /** The original's `allowedTransitions`, written out again so a change to either side fails here. */
  private val original: Map[RewardStatus, Set[RewardStatus]] = Map(
    Available           -> Set(Pending, Expired, Active),
    Pending             -> Set(Active, Available, Expired, Cancelled),
    Active              -> Set(Paused, ConvertedPendingCap, Closed, Expired, Cancelled, Forfeited, Revoked),
    Paused              -> Set(Active, Cancelled, Revoked),
    ConvertedPendingCap -> Set(Closed, Forfeited, Revoked)
  )

  test("the ten states, with the original's names on the wire"):
    assertEquals(
      RewardStatus.values.toVector.map(_.wire),
      Vector("AVAILABLE", "PENDING", "ACTIVE", "PAUSED", "CONVERTED_PENDING_CAP", "CLOSED", "EXPIRED", "CANCELLED",
        "FORFEITED", "REVOKED")
    )

  test("the table is the original's"):
    assertEquals(Transitions.allowed, original)

  test("the terminal states are the five no transition leaves"):
    assertEquals(RewardStatus.values.filter(_.isTerminal).toSet, Set(Closed, Expired, Cancelled, Forfeited, Revoked))
    for s <- RewardStatus.values if s.isTerminal do assert(!Transitions.allowed.contains(s), s.wire)

  test("every pair: the table's moves, a repeat stays, anything else refused naming both"):
    for
      from <- RewardStatus.values
      to   <- RewardStatus.values
    do
      val clue = s"${from.wire} -> ${to.wire}"
      val expected =
        if from == to then Right(Move.Stay)
        else if original.getOrElse(from, Set.empty).contains(to) then Right(Move.To(to))
        else Left(InvalidTransition(from, to))
      assertEquals(Transitions.move(from, to), expected, clue)
      assertEquals(Transitions.canMove(from, to), expected.isRight, clue)

  test("a refusal is INVALID_TRANSITION and names from and to by their wire names"):
    val refusal = InvalidTransition(Closed, Active)
    assertEquals(refusal.code, "INVALID_TRANSITION")
    assertEquals(refusal.from, Closed)
    assertEquals(refusal.to, Active)
    assert(refusal.message.contains("CLOSED") && refusal.message.contains("ACTIVE"), refusal.message)

  test("the original's lifecycle paths, step by step (RewardStatusSpec's scenarios)"):
    val paths = Vector(
      Vector(Available, Pending, Active, ConvertedPendingCap, Closed),
      Vector(Available, Pending, Available, Pending, Expired),
      Vector(Available, Active, Forfeited),
      Vector(Pending, Cancelled),
      Vector(Active, Paused, Active, Revoked),
      Vector(Active, ConvertedPendingCap, Forfeited),
      Vector(Active, Closed),
      Vector(Active, Expired)
    )
    for path <- paths; (a, b) <- path.zip(path.tail) do
      assertEquals(Transitions.move(a, b), Right(Move.To(b)), s"${a.wire} -> ${b.wire}")

  property("a repeat of any state is a no-op, terminal or not"):
    Prop.forAll(Gen.oneOf(RewardStatus.values.toSeq)) { s =>
      Transitions.move(s, s) == Right(Move.Stay)
    }

  property("nothing leaves a terminal state but a repeat"):
    Prop.forAll(Gen.oneOf(RewardStatus.values.filter(_.isTerminal).toSeq), Gen.oneOf(RewardStatus.values.toSeq)) {
      (from, to) => from == to || Transitions.move(from, to) == Left(InvalidTransition(from, to))
    }

  test("the codec writes the wire name and reads it back; an unknown name fails"):
    for s <- RewardStatus.values do
      assertEquals(writeToString(s), s"\"${s.wire}\"")
      assertEquals(readFromString[RewardStatus](s"\"${s.wire}\""), s)
    intercept[Exception](readFromString[RewardStatus]("\"active\""))

  test("from reads the wire name only"):
    assertEquals(RewardStatus.from("CONVERTED_PENDING_CAP"), Some(ConvertedPendingCap))
    assertEquals(RewardStatus.from("ConvertedPendingCap"), None)
    assertEquals(RewardStatus.from("granted"), None)
