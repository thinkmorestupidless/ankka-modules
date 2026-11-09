package io.eitheror.casino.players.sessions

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.core.Serializers.string
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.PlayersCodecs

/**
 * The game session `support-agent` ended under an approval id (spec 017 amendment P1; FR-017): the approval is the
 * end's key, claimed once. The same session under it again is the same end; another session is `key_reused`.
 */
final case class EndKeyState(sessionId: String = "")

/** What a claim of a key came to. */
enum EndKeyClaim:
  case Fresh, Repeat, Reused

final class AgentEndKey(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[EndKeyState]:
  def emptyState: EndKeyState = EndKeyState()

  def claim(sessionId: String): Effect[EndKeyClaim] =
    if currentState.sessionId.isEmpty then effects.updateState(EndKeyState(sessionId)).thenReply(_ => EndKeyClaim.Fresh)
    else if currentState.sessionId == sessionId then effects.reply(EndKeyClaim.Repeat)
    else effects.reply(EndKeyClaim.Reused)

object AgentEndKey
    extends KeyValueEntity.Companion[AgentEndKey, EndKeyState](
      componentId = ComponentId("agent-end-key"),
      stateSerializer = PlayersCodecs.serializer[EndKeyState]("agent-end-key")
    ):

  given Serializer[EndKeyClaim] = PlayersCodecs.serializer[EndKeyClaim]("agent-end-claim")

  def create(context: KeyValueEntityContext) = new AgentEndKey(context)

  val claim = command("claim")(_.claim)
