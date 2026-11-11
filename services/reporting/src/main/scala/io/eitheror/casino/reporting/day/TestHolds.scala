package io.eitheror.casino.reporting.day

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.intake.Generation

/** A fold of a player not yet registered: the source and sequence it was folded under, and what it moved on each day. */
final case class HeldFold(source: String, seq: Long, days: Map[String, Delta])

/**
 * A player's folds made before the day consumers knew the player (research R4): whether the registration has been folded,
 * its test flag, and the folds held meanwhile, kept so a redelivered fold is known as held.
 */
final case class TestHoldsState(registered: Boolean = false, test: Boolean = false, held: Vector[HeldFold] = Vector.empty):
  def holds(source: String, seq: Long): Boolean = held.exists(h => h.source == source && h.seq == seq)

/** A fold whose player's `PlayerLog` has no registration yet. */
final case class HoldFold(source: String, seq: Long, days: Map[String, Delta])

/** The player's registration, folded by `to-days-players`, with its test flag. */
final case class Register(test: Boolean)

/**
 * Key value, id `g<n>/<player>`: what makes "excluding test accounts" independent of whether a player's money or their
 * registration is folded first (review, should fix 2). A fold of a player `PlayerLog` does not know yet is counted as a
 * real player's and held here; the registration, when `to-days-players` folds it, answers the held folds, and a test
 * account's are folded again into the days' test sections alone (`Fold.testOnly`). Both ends meet in this entity, so a
 * fold either is held before the registration or is answered the registration's flag after it.
 */
final class TestHolds(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[TestHoldsState]:
  def emptyState: TestHoldsState = TestHoldsState()

  /** The flag to fold with: the registration's once it is folded, else `false` with the fold held. A held fold again `false`. */
  def hold(h: HoldFold): Effect[Boolean] =
    val s = currentState
    if s.holds(h.source, h.seq) then effects.reply(false)
    else if s.registered then effects.reply(s.test)
    else effects.updateState(s.copy(held = s.held :+ HeldFold(h.source, h.seq, h.days))).thenReply(_ => false)

  /** The registration folded: the held folds to fold again as a test account's, none for a real player. */
  def register(r: Register): Effect[Vector[HeldFold]] =
    val s    = currentState
    val next = s.copy(registered = true, test = r.test)
    val out  = if r.test then s.held else Vector.empty
    if next == s then effects.reply(out) else effects.updateState(next).thenReply(_ => out)

  def get: ReadOnlyEffect[TestHoldsState] = effects.reply(currentState)

object TestHolds extends KeyValueEntity.Companion[TestHolds, TestHoldsState](
      componentId = ComponentId("test-holds"),
      stateSerializer = ReportingCodecs.serializer[TestHoldsState]("test-holds")
    ):
  given Serializer[HoldFold]       = ReportingCodecs.serializer[HoldFold]("test-holds-hold")
  given Serializer[Register]           = ReportingCodecs.serializer[Register]("test-holds-register")
  given Serializer[Vector[HeldFold]]   = ReportingCodecs.serializer[Vector[HeldFold]]("test-holds-held")
  given Serializer[TestHoldsState]     = ReportingCodecs.serializer[TestHoldsState]("test-holds-reply")
  import ReportingCodecs.given_Serializer_Boolean

  def id(generation: Int, player: String): String = Generation.id(generation, player)

  /** The source a held fold is folded again under, into the test sections alone. */
  def source(held: String): String = s"test/$held"

  def create(context: KeyValueEntityContext) = new TestHolds(context)

  val hold     = command("hold")(_.hold)
  val register = command("register")(_.register)
  val get      = query("get")(_.get)
