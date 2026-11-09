package io.eitheror.casino.support.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.agent.{AgentPart, forAgent}
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.support.Json
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.agent.{Motoko, Turn}
import io.eitheror.casino.support.config.{ConfigBody, ConfigVersion}
import io.eitheror.casino.support.conversation.{At, Conversation, ConversationState, Start}
import io.eitheror.casino.support.domain.{Frames, HandOff}
import org.apache.pekko.stream.scaladsl.Source

import scala.util.control.NonFatal

/**
 * The player's two routes (contracts/routes.md), under `/v1/casino/agent/<slug>`: the chat turn and the decision on
 * an awaited action, each a stream of the original's frames. The player is the sign-in's, never a path or a body
 * value (FR-004). Each refusal is made before the stream opens, in the table's order. ankka 0.16's body-taking stream
 * route takes one path parameter, so the chat's last segment is one, and any other value of it is 404.
 */
final class ChatEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/agent/${ctx.slug}"):
  val acl: Acl = Routes.player(ctx.slug, ctx.authenticate())

  sseEventsBody[String, String]("/run/{route}") { (route, body) =>
    if route != Chat.Route then throw Answers.notFound
    Chat.run(ctx, player(), body)
  }

  sseEventsBody[String, String]("/resume/{session}") { (session, body) =>
    Chat.resume(ctx, player(), session, body)
  }

  private def player(): String = Routes.playerId(principal).getOrElse(throw Answers.sessionNotYours)

object Chat:
  val Route = "chat_with_player"

  private def fields(body: String): JsonNode = Json.parseObject(body).getOrElse(throw Answers.missingFields(Seq("message", "session_id")))

  /** `POST /run/chat_with_player`: the checks before the stream, then the turn's frames. */
  def run(ctx: Ctx, playerId: String, text: String): Source[SseEvent, ?] =
    val b       = fields(text)
    val message = b.text("message").filter(_.trim.nonEmpty)
    val session = b.text("session_id").filter(_.trim.nonEmpty)
    val missing = Seq("message" -> message, "session_id" -> session).collect { case (f, None) => f }
    if missing.nonEmpty then throw Answers.missingFields(missing)
    val (m, s) = (message.get, session.get)

    val config = ctx.configuration
    if !config.current.enabled then throw Answers.agentUnavailable(config.current.unavailableMessage)
    val before = ctx.conversationState(s)
    if before.started && before.playerId != playerId then throw Answers.sessionNotYours
    if before.awaiting.isDefined then throw Answers.sessionActive
    val now = ctx.now()
    if before.endedAt(now) then throw Answers.conversationEnded(bodyOf(ctx, before).closingMessage)

    val bound = ctx.conversation(s).call(Conversation.start).invoke(Start(playerId, config.version, now))
    bound.refusal.foreach(_ => throw Answers.sessionNotYours)
    val body = bodyOf(ctx, bound.state)
    turn(ctx, s, Turn(playerId, m, bound.state.configVersion, first = !before.started), body)

  /** `POST /resume/{session}`: the checks before the stream; the decision is user story 2's (T033). */
  def resume(ctx: Ctx, playerId: String, session: String, text: String): Source[SseEvent, ?] =
    val action = Json.parseObject(text).flatMap(_.text("action"))
    if !action.exists(Set("approve", "reject")) then throw Answers.invalidAction
    val state = ctx.conversationState(session)
    if !state.started || state.playerId != playerId then throw Answers.sessionNotYours
    if state.awaiting.isEmpty then
      throw (if state.lastDecided.isDefined then Answers.alreadyDecided else Answers.noPendingAction)
    throw Answers.refuse(501, "not_implemented")

  private def bodyOf(ctx: Ctx, s: ConversationState): ConfigBody = ConfigVersion.body(ctx.components, ctx.slug, s.configVersion)

  /** The turn's frames: a `delta` per text part with the hand-off marker replaced, then `confirm_action` or not, then `done`. */
  private def turn(ctx: Ctx, session: String, t: Turn, body: ConfigBody): Source[SseEvent, ?] =
    val handOff = HandOff(body.handOff)
    ctx.components.forAgent(Motoko.session(session), Some(Motoko.subject(t.playerId))).streamParts(Motoko.chat)(t)
      .mapConcat {
        case AgentPart.Text(part) => Vector(handOff.feed(part)).filter(_.nonEmpty).map(Frames.delta)
        case AgentPart.AwaitingApproval(requests) =>
          Vector(handOff.finish()).filter(_.nonEmpty).map(Frames.delta) ++
            requests.headOption.map(r => Frames.confirmAction(r.tool, r.tool, session))
      }
      .concat(Source.lazySingle { () =>
        val rest = handOff.finish()
        if handOff.handedOff then ctx.conversation(session).call(Conversation.handedOff).invoke(At(ctx.now())): Unit
        rest
      }.filter(_.nonEmpty).map(Frames.delta))
      .concat(Source.single(Frames.done))
      .recover { case NonFatal(_) => Frames.error(body.unavailableMessage) }
