package io.eitheror.casino.players.registration

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.PlayersCodecs

import java.time.{Duration, Instant}

/**
 * The players under one lookup token (FR-036, R9), keyed by the token: a keyed digest, so the index holds
 * nothing a reader could recover a name, a date of birth, a fingerprint, an email or an address from. An
 * address token's entry keeps no player for good, only the registrations of its last 24 hours with their
 * times (spec 011 FR-035): the rate and the same-address pair read nothing older.
 */
final case class LookupIndexState(playerIds: Set[String] = Set.empty, recent: Map[String, Instant] = Map.empty):
  def isEmpty: Boolean = playerIds.isEmpty && recent.isEmpty

final case class LookupPlayer(playerId: String)

/** A registration from the token's address, and when. */
final case class Arrival(playerId: String, at: Instant)

/** The other players already under the token. */
final case class Others(playerIds: Set[String])

final class LookupIndexEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[LookupIndexState]:
  def emptyState: LookupIndexState = LookupIndexState()

  /** Adds the player and answers the others already held; a repeat adds nothing. */
  def add(c: LookupPlayer): Effect[Others] =
    val others = Others(currentState.playerIds - c.playerId)
    if currentState.playerIds(c.playerId) then effects.reply(others)
    else effects.updateState(currentState.copy(playerIds = currentState.playerIds + c.playerId)).thenReply(_ => others)

  /**
   * Records a registration from the address and answers the others of the 24 hours before it, dropping
   * those older. A repeat keeps the first time.
   */
  def arrive(c: Arrival): Effect[Others] =
    val since  = c.at.minus(LookupIndexEntity.Window)
    val kept   = currentState.recent.filter((_, at) => at.isAfter(since))
    val others = Others((kept - c.playerId).keySet)
    val next   = currentState.copy(recent = kept.updated(c.playerId, currentState.recent.getOrElse(c.playerId, c.at)))
    if next == currentState then effects.reply(others) else effects.updateState(next).thenReply(_ => others)

  def remove(c: LookupPlayer): Effect[Others] =
    val rest = currentState.playerIds - c.playerId
    val next = LookupIndexState(rest, currentState.recent - c.playerId)
    if next == currentState then effects.reply(Others(rest))
    else if next.isEmpty then effects.deleteEntity().thenReply(_ => Others(rest))
    else effects.updateState(next).thenReply(_ => Others(rest))

  def get: ReadOnlyEffect[LookupIndexState] = effects.reply(currentState)

object LookupIndexEntity
    extends KeyValueEntity.Companion[LookupIndexEntity, LookupIndexState](
      componentId = ComponentId("lookup-index"),
      stateSerializer = PlayersCodecs.serializer[LookupIndexState]("lookup-index-state")
    ):

  given Serializer[LookupIndexState] = PlayersCodecs.serializer[LookupIndexState]("lookup-index-reply")
  given Serializer[LookupPlayer]     = PlayersCodecs.serializer[LookupPlayer]("lookup-player")
  given Serializer[Arrival]          = PlayersCodecs.serializer[Arrival]("lookup-arrival")
  given Serializer[Others]           = PlayersCodecs.serializer[Others]("lookup-others")

  def create(context: KeyValueEntityContext) = new LookupIndexEntity(context)

  /** How far back an address's registrations count (spec 011 FR-035). */
  val Window: Duration = Duration.ofHours(24)

  val add    = command("add")(_.add)
  val arrive = command("arrive")(_.arrive)
  val remove = command("remove")(_.remove)
  val get    = query("get")(_.get)
