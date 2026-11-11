package io.eitheror.casino.support.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.agent.{AgentCalls, AgentOutcome, AgentPart, ApprovalRequest, Decision, SessionMemoryEntity, forAgent,
  forSessionMemory}
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.support.Json
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.agent.{Actions, Context, Motoko, Turn}
import io.eitheror.casino.support.config.{ConfigBody, ConfigVersion}
import io.eitheror.casino.support.conversation.*
import io.eitheror.casino.support.domain.{Frames, HandOff, Redaction}
import io.eitheror.casino.support.timers.Forget
import org.apache.pekko.stream.scaladsl.Source

import java.time.Instant
import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * The player's two routes (contracts/routes.md), under `/v1/casino/agent/<slug>`: the chat turn and the decision on
 * an awaited action, each a stream of the original's frames. The player is the sign-in's, never a path or a body
 * value (FR-004). Each refusal is made before the stream opens, in the table's order. ankka 0.16's body-taking stream
 * route takes one path parameter, so the chat's last segment is one, and any other value of it is 404.
 */
final class ChatEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/agent/${ctx.slug}"):
  val acl: Acl = Routes.player(ctx.slug, ctx.authenticate())

  sseEventsBody("/run/{route}") { (route: String, body: String) =>
    if route != Chat.Route then throw Answers.notFound
    Chat.run(ctx, player(), body)
  }

  sseEventsBody("/resume/{session}") { (session: String, body: String) =>
    Chat.resume(ctx, player(), session, body)
  }

  private def player(): String = Routes.playerId(principal).getOrElse(throw Answers.sessionNotYours)

object Chat:
  val Route = "chat_with_player"

  /** Who decides when the player's arguments cannot be put to them (FR-011). */
  val Service = "support-agent"

  /** The agent's calls block: they run here, never on the stream's own threads. */
  private given ExecutionContext = ExecutionContext.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor())

  private def agent(ctx: Ctx, session: String, playerId: String): AgentCalls =
    ctx.components.forAgent(Motoko.session(session), Some(Motoko.subject(playerId)))

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
    val before = settled(ctx, s, ctx.conversationState(s))
    if before.started && before.playerId != playerId then throw Answers.sessionNotYours
    before.awaiting.foreach((id, tool) => throw Answers.sessionActive(id, tool))
    val now = ctx.now()
    if before.endedAt(now) then throw Answers.conversationEnded(closing(bodyOf(ctx, before), before))

    val bound = ctx.conversation(s).call(Conversation.start).invoke(Start(playerId, config.version, now))
    bound.refusal.foreach(_ => throw Answers.sessionNotYours)
    val first = !before.started || before.status == Status.Forgotten
    // A conversation is forgotten after the retention even when its first turn's stream is cut before its end.
    if first then Forget.schedule(ctx.components, s, bound.state)
    val body = bodyOf(ctx, bound.state)
    turn(ctx, s, Turn(playerId, m, bound.state.configVersion, first), body)

  /**
   * An awaited approval ankka has decided itself (FR-016: its time limit passed) is recorded as decided by `ankka`,
   * so the next message is a new turn rather than a conflict.
   */
  private def settled(ctx: Ctx, session: String, s: ConversationState): ConversationState =
    s.awaiting match
      case Some((id, _)) if !agent(ctx, session, s.playerId).approvals().exists(_.id == id) =>
        ctx.conversation(session).call(Conversation.decided).invoke(Decide(id, approved = false, "ankka", expired = true, ctx.now())).state
      case _ => s

  /** `POST /resume/{session}`: the checks, the decision recorded, then ankka's decision and the turn's frames. */
  def resume(ctx: Ctx, playerId: String, session: String, text: String): Source[SseEvent, ?] =
    val b      = Json.parseObject(text)
    val action = b.flatMap(_.text("action")).filter(Set("approve", "reject"))
    if action.isEmpty then throw Answers.invalidAction
    val approve = action.contains("approve")
    val state   = ctx.conversationState(session)
    if !state.started || state.playerId != playerId then throw Answers.sessionNotYours
    val awaited = settled(ctx, session, state).awaiting.map(_._1)
    val id = awaited.orElse(state.lastDecided).getOrElse(throw Answers.noPendingAction)
    val by = s"player:$playerId"
    // Recorded before ankka is told, so the approved tool reads its key from the conversation (research).
    ctx.conversation(session).call(Conversation.decided).invoke(Decide(id, approve, by, expired = false, ctx.now())).refusal.foreach {
      case "already_decided" => throw Answers.alreadyDecided
      case _                 => throw Answers.noPendingAction
    }
    val body     = bodyOf(ctx, state)
    val decision = if approve then Decision.approved(id, by) else Decision.refused(id, by, b.flatMap(_.text("feedback")).getOrElse(""))
    val handOff  = HandOff(body.handOff)
    Source.lazyFuture { () =>
      Future {
        val outcome = agent(ctx, session, playerId).decide(Motoko.chat)(decision)
        val result =
          if !approve then Vector.empty
          else ctx.conversationState(session).lastAction.filter(_.approvalId == id).map(a => Frames.actionResult(a.tool, a.success, a.detail)).toVector
        // An exclusion this decision applied closes the conversation with the brand's closing message (FR-017).
        val closed = ctx.conversationState(session).status == Status.Ended(EndReason.SelfExcluded) && result.nonEmpty
        result ++ outcomeFrames(ctx, session, playerId, body, handOff, outcome) ++
          Option.when(closed)(Frames.delta(s"\n\n${body.closingMessage}")).toVector
      }
    }.mapConcat(identity).concat(ending(ctx, session, handOff)).recover { case NonFatal(_) => Frames.error(body.unavailableMessage) }

  private def bodyOf(ctx: Ctx, s: ConversationState): ConfigBody = ConfigVersion.body(ctx.components, ctx.slug, s.configVersion)

  /** What an ended conversation answers (FR-017): the brand's closing message, or, on a break, when it ends. */
  private def closing(body: ConfigBody, s: ConversationState): String =
    s.breakUntil.fold(body.closingMessage)(until => s"You are on a break until $until. This conversation is closed until then.")

  /** The turn's frames: a `delta` per text part with the hand-off marker replaced, then `confirm_action` or not, then `done`. */
  private def turn(ctx: Ctx, session: String, t: Turn, body: ConfigBody): Source[SseEvent, ?] =
    val handOff = HandOff(body.handOff)
    agent(ctx, session, t.playerId).streamParts(Motoko.chat)(t)
      .flatMapConcat {
        case AgentPart.Text(part) => Source(Vector(handOff.feed(part)).filter(_.nonEmpty).map(Frames.delta))
        case AgentPart.AwaitingApproval(requests) =>
          Source.lazyFuture(() => Future(awaiting(ctx, session, t.playerId, body, handOff, requests))).mapConcat(identity)
      }
      .concat(ending(ctx, session, handOff))
      .recover { case NonFatal(_) => Frames.error(body.unavailableMessage) }

  /**
   * What is held of the reply, then `done`; a reply that carried the hand-off marker is recorded as one (R12). The
   * turn is recorded with the session's tokens after it, and the forget timer set from it (FR-027).
   */
  private def ending(ctx: Ctx, session: String, handOff: HandOff): Source[SseEvent, ?] =
    Source.lazySingle { () =>
      val rest = handOff.finish()
      if handOff.handedOff then ctx.conversation(session).call(Conversation.handedOff).invoke(At(ctx.now())): Unit
      val tokens = ctx.components.forSessionMemory(Motoko.session(session)).call(SessionMemoryEntity.history).invoke().usage.total
      val after  = ctx.conversation(session).call(Conversation.turned).invoke(Turned(ctx.now(), tokens.toLong))
      Forget.schedule(ctx.components, session, after.state)
      rest
    }.filter(_.nonEmpty).map(Frames.delta).concat(Source.single(Frames.done))

  /** The model's answer after a decision: its text, or what it now awaits. */
  private def outcomeFrames(ctx: Ctx, session: String, playerId: String, body: ConfigBody, handOff: HandOff,
      outcome: AgentOutcome[String]): Vector[SseEvent] = outcome match
    case AgentOutcome.Answered(text)             => Vector(handOff.feed(text)).filter(_.nonEmpty).map(Frames.delta)
    case AgentOutcome.AwaitingApproval(requests) => awaiting(ctx, session, playerId, body, handOff, requests)

  /**
   * The turn waits for the player. One action is put to them at a time; another asked in the same response is
   * refused to the model. Arguments that cannot be put to them (FR-011) are refused at once, as the service, and
   * the model's answer follows; otherwise the request is recorded and `confirm_action` states its summary.
   */
  private def awaiting(ctx: Ctx, session: String, playerId: String, body: ConfigBody, handOff: HandOff,
      requests: Vector[ApprovalRequest]): Vector[SseEvent] =
    val held  = Vector(handOff.finish()).filter(_.nonEmpty).map(Frames.delta)
    val calls = agent(ctx, session, playerId)
    val now   = ctx.now()
    val r     = requests.head
    requests.tail.foreach(o => calls.decide(Motoko.chat)(Decision.refused(o.id, Service, "one action at a time; ask for this one next")): Unit)
    val args     = Json.parse(r.arguments.render).getOrElse(Json.obj())
    val n        = ctx.neighbours
    val currency = Context.playerState(n, playerId, body).toOption.flatMap(_.text("currency"))
    val redacted = Redaction.arguments(args)
    held ++ (Actions.check(r.tool, args, currency, now) match
      case Left(reason) =>
        ctx.conversation(session).call(Conversation.toolCalled)
          .invoke(ToolCall(r.callId, r.tool, redacted, Outcome.Refused("invalid_arguments"), None, Some(reason), now)): Unit
        outcomeFrames(ctx, session, playerId, body, handOff, calls.decide(Motoko.chat)(Decision.refused(r.id, Service, reason)))
      case Right(()) =>
        val summary = Actions.summary(n, playerId, r.tool, args, currency)
        val expires = r.expiresAt.map(Instant.ofEpochMilli).getOrElse(now.plusSeconds(body.approvalMinutes * 60L))
        ctx.conversation(session).call(Conversation.requested).invoke(Requested(r.id, r.tool, redacted, summary, expires, now)): Unit
        Vector(Frames.confirmAction(r.tool, summary, session)))
