package io.eitheror.casino.provider.core

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.provider.core.SessionEvent.*

/**
 * A session, keyed by its launch ticket. The rules about one session live here: a scenario is
 * chosen before the check and only once; nothing is planned on a session that was never launched.
 * Every generated id and time arrives in the command (research R6): `applyEvent` only folds.
 */
final class SessionEntity(context: EventSourcedEntityContext) extends EventSourcedEntity[Session, SessionEvent]:

  def emptyState: Session = Session.empty(context.entityId)

  def applyEvent(event: SessionEvent): Session = Session.applied(currentState, event)

  def launch(request: SessionEntity.Launch): Effect[Session] =
    effects
      .persist(Launched(request.brand, request.playerId, request.currency, request.gameCode, request.scenario, request.runId, request.seed, request.at,
        request.demo, request.language, request.owner))
      .thenReply(identity)

  /**
   * `launch` once (the house provider, spec 026): a launch repeating the one that made the session
   * answers it unchanged, so a brand's retried launch is harmless; a launch of the ticket with another
   * player, currency, game or mode is refused, since the session may already be checked and hold rounds.
   */
  def launchOnce(request: SessionEntity.Launch): Effect[Session] =
    val s = currentState
    if !s.launched then launch(request)
    else if (s.brand, s.playerId, s.currency, s.gameCode, s.demo) == (request.brand, request.playerId, request.currency, request.gameCode, request.demo)
    then effects.reply(s)
    else effects.error(s"${SessionEntity.TicketUsed}: the ticket was launched with other terms", ErrorCode.Conflict)

  def selectScenario(selection: SessionEntity.Selection): Effect[Done] =
    if currentState.checked then
      effects.error(s"scenario_locked: the session's game page has loaded; a scenario is chosen before launch", ErrorCode.Conflict)
    else if currentState.scenarioSelected then
      effects.error(s"scenario_already_selected: '${currentState.scenario}' stands for this session", ErrorCode.Conflict)
    else effects.persist(ScenarioSelected(selection.name, selection.seed)).thenReply(_ => Done)

  def check(walletToken: String): Effect[Session] =
    if !currentState.launched then effects.error("session not found", ErrorCode.NotFound)
    else effects.persist(Checked(walletToken)).thenReply(identity)

  def refuseCheck(word: String): Effect[Session] =
    if !currentState.launched then effects.error("session not found", ErrorCode.NotFound)
    else if currentState.refused then effects.reply(currentState)
    else effects.persist(CheckRefused(word)).thenReply(identity)

  def answered(answer: RoundAnswered): Effect[Session] =
    if !currentState.round(answer.roundId).exists(_.state == Round.Pending) then effects.reply(currentState)
    else effects.persist(answer).thenReply(identity)

  def plan(planned: CallbackPlanned): Effect[Session] =
    if !currentState.launched then effects.error("session not found", ErrorCode.NotFound)
    else effects.persist(planned).thenReply(identity)

  /**
   * `plan` under the round's rules (spec 026): the house provider's game calls. The simulator's
   * scenarios keep `plan`, which must be able to send what a broken provider would.
   */
  def planGuarded(planned: CallbackPlanned): Effect[Session] =
    if !currentState.launched then effects.error("session not found", ErrorCode.NotFound)
    else
      currentState.refusal(RoundRole.valueOf(planned.role), planned.roundId) match
        case Some(word) => effects.error(s"$word: round ${planned.roundId.getOrElse("")}", ErrorCode.Conflict)
        case None       => effects.persist(planned).thenReply(identity)

  def sawBalance(balance: String): Effect[Done] =
    if currentState.lastBalance.contains(balance) then effects.reply(Done)
    else effects.persist(BalanceSeen(balance)).thenReply(_ => Done)

  def judge(verdict: Verdict): Effect[Done] =
    if currentState.verdicts.contains(verdict) then effects.reply(Done)
    else effects.persist(StepJudged(verdict)).thenReply(_ => Done)

  def discard: Effect[Done] =
    if !currentState.launched && !currentState.scenarioSelected then effects.reply(Done)
    else effects.persist(Discarded).deleteEntity().thenReply(_ => Done)

  def get: ReadOnlyEffect[Session] =
    if !currentState.launched && !currentState.scenarioSelected then effects.error("session not found", ErrorCode.NotFound)
    else effects.reply(currentState)

object SessionEntity
    extends EventSourcedEntity.Companion[SessionEntity, Session, SessionEvent](
      componentId = ComponentId("session"),
      stateSerializer = Codecs.serializer[Session]("session"),
      eventSerializer = Codecs.serializer[SessionEvent]("session-event")
    ):

  /** A scenario for a session, and the seed its run draws from when the tester fixes one (SC-003). */
  final case class Selection(name: String, seed: Option[Long])

  final case class Launch(brand: String, playerId: String, currency: String, gameCode: String, scenario: String, runId: String, seed: Long, at: Long,
      demo: Boolean = false, language: String = "", owner: String = "")

  given Serializer[Launch]          = Codecs.serializer[Launch]("session-launch")
  given Serializer[Selection]       = Codecs.serializer[Selection]("session-selection")
  given Serializer[Session]         = Codecs.serializer[Session]("session")
  given Serializer[CallbackPlanned] = Codecs.serializer[CallbackPlanned]("session-callback-planned")
  given Serializer[Verdict]         = Codecs.serializer[Verdict]("session-verdict")
  given Serializer[RoundAnswered]   = Codecs.serializer[RoundAnswered]("session-round-answered")

  def create(context: EventSourcedEntityContext) = new SessionEntity(context)

  val launch         = command("launch")(_.launch)
  val launchOnce     = command("launch-once")(_.launchOnce)

  /** The refusal of a ticket launched again with other terms. */
  val TicketUsed = "ticket_used"
  val selectScenario = command("select-scenario")(_.selectScenario)
  val check          = command("record-check")(_.check)
  val plan           = command("plan")(_.plan)
  val planGuarded    = command("plan-guarded")(_.planGuarded)
  val refuseCheck    = command("refuse-check")(_.refuseCheck)
  val answered       = command("round-answered")(_.answered)
  val sawBalance     = command("saw-balance")(_.sawBalance)
  val judge          = command("judge")(_.judge)
  val discard        = command("discard")(_.discard)
  val get            = query("get")(_.get)
