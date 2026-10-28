package io.eitheror.casino.players.registration

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.PlayersCodecs

/**
 * The players under one lookup token (FR-036, R9), keyed by the token: a keyed digest, so the index holds
 * nothing a reader could recover a name, a date of birth or a fingerprint from.
 */
final case class LookupIndexState(playerIds: Set[String] = Set.empty)

final case class LookupPlayer(playerId: String)

/** The other players already under the token. */
final case class Others(playerIds: Set[String])

final class LookupIndexEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[LookupIndexState]:
  def emptyState: LookupIndexState = LookupIndexState()

  /** Adds the player and answers the others already held; a repeat adds nothing. */
  def add(c: LookupPlayer): Effect[Others] =
    val others = Others(currentState.playerIds - c.playerId)
    if currentState.playerIds(c.playerId) then effects.reply(others)
    else effects.updateState(LookupIndexState(currentState.playerIds + c.playerId)).thenReply(_ => others)

  def remove(c: LookupPlayer): Effect[Others] =
    val rest = currentState.playerIds - c.playerId
    if !currentState.playerIds(c.playerId) then effects.reply(Others(rest))
    else if rest.isEmpty then effects.deleteEntity().thenReply(_ => Others(rest))
    else effects.updateState(LookupIndexState(rest)).thenReply(_ => Others(rest))

  def get: ReadOnlyEffect[LookupIndexState] = effects.reply(currentState)

object LookupIndexEntity
    extends KeyValueEntity.Companion[LookupIndexEntity, LookupIndexState](
      componentId = ComponentId("lookup-index"),
      stateSerializer = PlayersCodecs.serializer[LookupIndexState]("lookup-index-state")
    ):

  given Serializer[LookupIndexState] = PlayersCodecs.serializer[LookupIndexState]("lookup-index-reply")
  given Serializer[LookupPlayer]     = PlayersCodecs.serializer[LookupPlayer]("lookup-player")
  given Serializer[Others]           = PlayersCodecs.serializer[Others]("lookup-others")

  def create(context: KeyValueEntityContext) = new LookupIndexEntity(context)

  val add    = command("add")(_.add)
  val remove = command("remove")(_.remove)
  val get    = query("get")(_.get)
