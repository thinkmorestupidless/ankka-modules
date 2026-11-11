package io.eitheror.casino.support.conversation

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.support.{Stored, SupportCodecs}

/**
 * One session's trail (data-model.md `Conversation`, id = session id; research R6): who it is bound to, the
 * configuration version it reads, whether it awaits a decision or has ended, and what each tool, approval,
 * decision, guardrail and hand-off came to. It holds no message text: ankka's session memory has the transcript.
 * Handlers read no clock: each command carries its time.
 */
final class Conversation(@scala.annotation.unused context: EventSourcedEntityContext)
    extends EventSourcedEntity[ConversationState, ConversationEvent]:
  import ConversationEvent.*

  def emptyState: ConversationState = ConversationState()

  def applyEvent(event: ConversationEvent): ConversationState =
    val s = currentState.copy(lastActivity = event.at)
    event match
      // A forgotten conversation's next turn starts a new one (FR-027): nothing of the old trail is kept.
      case e: Started if s.status == Status.Forgotten =>
        ConversationState(playerId = e.playerId, configVersion = e.configVersion, startedAt = Some(e.at), lastActivity = e.at)
      case e: Started           => s.copy(playerId = e.playerId, configVersion = e.configVersion, startedAt = Some(e.at))
      case e: TurnTaken         => s.copy(turns = s.turns + 1, tokensCounted = math.max(s.tokensCounted, e.tokensAfter))
      case e: ToolCalled =>
        e.approvalId.fold(s) { id =>
          val done = Some(ActionDone(id, e.tool, e.outcome == Outcome.Ok, e.detail.getOrElse("")))
          if s.approved.contains(id) then s.copy(approved = None, lastAction = done) else s.copy(lastAction = done)
        }
      case e: ApprovalRequested => s.copy(status = Status.Awaiting(e.approvalId, e.tool), namedForfeit = e.forfeit)
      case e: Decided           => s.copy(status = Status.Open, approved = Option.when(e.approved)(e.approvalId), lastDecided = Some(e.approvalId),
        decidedTool = Some(e.tool))
      case _: GuardrailRejected => s
      case _: HandedOff         => s
      case e: Ended =>
        val until = e.reason match
          case EndReason.OnBreak(u) => Some(u)
          case EndReason.SelfExcluded => None
        s.copy(status = Status.Ended(e.reason), breakUntil = until)
      // Forgetting clears the transcript, never an end: a break or an exclusion still closes the conversation.
      case _: Forgotten =>
        s.copy(status = s.status match { case e: Status.Ended => e; case _ => Status.Forgotten }, approved = None)

  private def ok(s: ConversationState) = Answer(s)
  private def refuse(code: String)     = effects.reply(Answer(currentState, Some(code)))

  /**
   * Binds the session to its player on the first turn, or on the first after it was forgotten (FR-027); again for the
   * same player is the same; another is refused (FR-004).
   */
  def start(c: Start): Effect[Answer] =
    if !currentState.started || (currentState.status == Status.Forgotten && currentState.playerId == c.playerId) then effects.persist(Started(c.playerId, c.configVersion, c.at)).thenReply(ok)
    else if currentState.playerId == c.playerId then effects.reply(ok(currentState))
    else refuse("session_not_yours")

  def turned(c: Turned): Effect[Answer] =
    effects.persist(TurnTaken(c.at, currentState.tokensCounted, c.tokensAfter)).thenReply(ok)

  def toolCalled(c: ToolCall): Effect[Answer] =
    effects.persist(ToolCalled(c.callId, c.tool, c.arguments, c.outcome, c.detail, c.approvalId, c.at)).thenReply(ok)

  def requested(c: Requested): Effect[Answer] =
    effects.persist(ApprovalRequested(c.approvalId, c.tool, c.arguments, c.summary, c.expiresAt, c.at, c.forfeit)).thenReply(ok)

  /** The decision on the approval awaited; another id is `no_pending_action`, a second decision `already_decided`. */
  def decided(c: Decide): Effect[Answer] =
    currentState.status match
      case Status.Awaiting(id, tool) if id == c.approvalId =>
        effects.persist(Decided(c.approvalId, tool, c.approved, c.by, c.expired, c.at)).thenReply(ok)
      case Status.Awaiting(_, _) => refuse("no_pending_action")
      case _ if currentState.lastDecided.contains(c.approvalId) => refuse("already_decided")
      case _ => refuse("no_pending_action")

  /**
   * The player's decision again on an approval decided here but never told to ankka (the endpoint checks ankka still
   * awaits it): it replaces the one recorded, so the decision ankka is told is the player's latest. Once the approved
   * tool has answered, or for another id, `already_decided`.
   */
  def redecided(c: Decide): Effect[Answer] =
    val s = currentState
    if s.awaiting.isEmpty && s.lastDecided.contains(c.approvalId) && !s.lastAction.exists(_.approvalId == c.approvalId) then
      effects.persist(Decided(c.approvalId, s.decidedTool.getOrElse(""), c.approved, c.by, c.expired, c.at)).thenReply(ok)
    else refuse("already_decided")

  def rejected(c: Rejected): Effect[Answer] =
    effects.persist(GuardrailRejected(c.guardrail, c.side, c.retried, c.at)).thenReply(ok)

  def handedOff(c: At): Effect[Answer] = effects.persist(HandedOff(c.at)).thenReply(ok)

  def end(c: End): Effect[Answer] = effects.persist(Ended(c.reason, c.at)).thenReply(ok)

  /** The forget timer cleared the session's memory (FR-027); once is enough. */
  def forget(c: At): Effect[Answer] =
    if currentState.status == Status.Forgotten then effects.reply(ok(currentState))
    else effects.persist(Forgotten(c.at)).thenReply(ok)

  def get: ReadOnlyEffect[ConversationState] = effects.reply(currentState)

object Conversation
    extends EventSourcedEntity.Companion[Conversation, ConversationState, ConversationEvent](
      componentId = ComponentId("support-conversation"),
      stateSerializer = Stored.conversation,
      eventSerializer = Stored.conversationEvent
    ):

  given Serializer[ConversationState] = SupportCodecs.serializer[ConversationState]("conversation-reply")
  given Serializer[Answer]            = SupportCodecs.serializer[Answer]("conversation-answer")
  given Serializer[Start]             = SupportCodecs.serializer[Start]("conversation-start")
  given Serializer[Turned]            = SupportCodecs.serializer[Turned]("conversation-turned")
  given Serializer[ToolCall]          = SupportCodecs.serializer[ToolCall]("conversation-tool-call")
  given Serializer[Requested]         = SupportCodecs.serializer[Requested]("conversation-requested")
  given Serializer[Decide]            = SupportCodecs.serializer[Decide]("conversation-decide")
  given Serializer[Rejected]          = SupportCodecs.serializer[Rejected]("conversation-rejected")
  given Serializer[At]                = SupportCodecs.serializer[At]("conversation-at")
  given Serializer[End]               = SupportCodecs.serializer[End]("conversation-end")

  def create(context: EventSourcedEntityContext) = new Conversation(context)

  val start      = command("start")(_.start)
  val turned     = command("turned")(_.turned)
  val toolCalled = command("tool-called")(_.toolCalled)
  val requested  = command("requested")(_.requested)
  val decided    = command("decided")(_.decided)
  val redecided  = command("redecided")(_.redecided)
  val rejected   = command("rejected")(_.rejected)
  val handedOff  = command("handed-off")(_.handedOff)
  val end        = command("end")(_.end)
  val forget     = command("forget")(_.forget)
  val get        = query("get")(_.get)
