package io.eitheror.casino.support.agent

import com.thinkmorestupidless.ankka.agent.{Agent, AgentContext}
import com.thinkmorestupidless.ankka.core.{ComponentId, DataSubject, Serializer, SessionId}
import io.eitheror.casino.support.{SupportCodecs, SupportRuntime}
import io.eitheror.casino.support.config.ConfigVersion

/**
 * One chat turn, as the endpoint asks it: the player the session is bound to, what they said, the configuration
 * version the conversation reads (FR-029), and whether it is the conversation's first turn (FR-007).
 */
final case class Turn(playerId: String, message: String, configVersion: Int, first: Boolean)

/**
 * Motoko (plan.md): one agent, sharded by session, its `chat` stream answering a turn. The prompt and the model
 * name are the conversation's configuration version's (FR-008, FR-029); the tools are built here, over the
 * service client, for the bound player, because a tool runs after the handler returns and cannot read the session.
 * On resume ankka runs the handler again with the turn's own input, so the same tools are built for it.
 * Nothing here logs a message, a reply or a tool's arguments (FR-020).
 */
final class Motoko(context: AgentContext) extends Agent:
  def chat(turn: Turn): StreamEffect =
    val s      = SupportRuntime.settings
    val body   = ConfigVersion.body(componentClient, s.brand, turn.configVersion)
    val reads  = SupportRuntime.neighbours(context.services)
    val tools  = Tools(reads, componentClient, sessionId.toString, turn.playerId, body)
    val loaded = if turn.first then Context.first(reads, turn.playerId, body) else None
    val asked  = effects.systemMessage(body.prompt).model(SupportRuntime.model(body.model)).userMessage(turn.message)
    loaded.fold(asked)(asked.withContext).tools(tools.all*).thenStream()

object Motoko extends Agent.Companion[Motoko](ComponentId("motoko")):
  given Serializer[Turn] = SupportCodecs.serializer[Turn]("turn")

  def create(ctx: AgentContext) = new Motoko(ctx)

  val chat = stream("chat")(_.chat)

  /** A session's data subject (feature 042): its memory is sealed under the player's key and erased with them. */
  def subject(playerId: String): DataSubject = DataSubject(s"player/$playerId")

  def session(id: String): SessionId = SessionId(id)
