package io.eitheror.casino.simulator.core

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.simulator.core.SessionEvent.*

/**
 * A session, keyed by its launch ticket. The rules about one session live here: a scenario is
 * chosen before the check and only once; nothing is planned on a session that was never launched.
 * Every generated id and time arrives in the command (research R6): `applyEvent` only folds.
 */
final class SessionEntity(context: EventSourcedEntityContext) extends EventSourcedEntity[Session, SessionEvent]:

  def emptyState: Session = Session.empty(context.entityId)

  def applyEvent(event: SessionEvent): Session = event match
    case e: Launched         => currentState.onLaunched(e)
    case ScenarioSelected(n, seed) => currentState.copy(scenario = n, scenarioSelected = true, seed = seed.getOrElse(currentState.seed))
    case Checked(w)          => currentState.copy(walletToken = Some(w), state = Session.Checked)
    case e: CallbackPlanned  => currentState.onPlanned(e)
    case BalanceSeen(b)      => currentState.copy(lastBalance = Some(b))
    case StepJudged(v)       => currentState.copy(verdicts = currentState.verdicts.filterNot(_.step == v.step) :+ v)
    case Discarded           => currentState

  def launch(request: SessionEntity.Launch): Effect[Session] =
    effects
      .persist(Launched(request.brand, request.playerId, request.currency, request.gameCode, request.scenario, request.runId, request.seed, request.at))
      .thenReply(identity)

  def selectScenario(selection: SessionEntity.Selection): Effect[Done] =
    if currentState.checked then
      effects.error(s"scenario_locked: the session's game page has loaded; a scenario is chosen before launch", ErrorCode.Conflict)
    else if currentState.scenarioSelected then
      effects.error(s"scenario_already_selected: '${currentState.scenario}' stands for this session", ErrorCode.Conflict)
    else effects.persist(ScenarioSelected(selection.name, selection.seed)).thenReply(_ => Done)

  def check(walletToken: String): Effect[Session] =
    if !currentState.launched then effects.error("session not found", ErrorCode.NotFound)
    else effects.persist(Checked(walletToken)).thenReply(identity)

  def plan(planned: CallbackPlanned): Effect[Session] =
    if !currentState.launched then effects.error("session not found", ErrorCode.NotFound)
    else effects.persist(planned).thenReply(identity)

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

  final case class Launch(brand: String, playerId: String, currency: String, gameCode: String, scenario: String, runId: String, seed: Long, at: Long)

  given Serializer[Launch]          = Codecs.serializer[Launch]("session-launch")
  given Serializer[Selection]       = Codecs.serializer[Selection]("session-selection")
  given Serializer[Session]         = Codecs.serializer[Session]("session")
  given Serializer[CallbackPlanned] = Codecs.serializer[CallbackPlanned]("session-callback-planned")
  given Serializer[Verdict]         = Codecs.serializer[Verdict]("session-verdict")

  def create(context: EventSourcedEntityContext) = new SessionEntity(context)

  val launch         = command("launch")(_.launch)
  val selectScenario = command("select-scenario")(_.selectScenario)
  val check          = command("record-check")(_.check)
  val plan           = command("plan")(_.plan)
  val sawBalance     = command("saw-balance")(_.sawBalance)
  val judge          = command("judge")(_.judge)
  val discard        = command("discard")(_.discard)
  val get            = query("get")(_.get)
