package io.eitheror.casino.backoffice.session

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.backoffice.{StateCodecs, StateRuntime}

import scala.concurrent.duration.*

/** Refreshes generation `generation` of session `session`. */
final case class StartRefresh(session: String, generation: Int)

/** A refresh's progress. Holds no token: the step reads the refresh token from the session and hands the new ones straight back. */
final case class RefreshFlow(status: String = RefreshFlow.New, session: String = "", generation: Int = 0, rolesChanged: Boolean = false):
  def finished: Boolean = RefreshFlow.Finished(status)

object RefreshFlow:
  val New        = "new"
  val Refreshing = "refreshing"
  /** Recorded, or found already moved on: the session answers what it now holds. */
  val Done       = "done"
  /** The realm refused the refresh: the session ended `revoked`. */
  val Revoked    = "revoked"
  /** The realm could not be asked: the session is as it was. */
  val Failed     = "failed"

  val Finished: Set[String] = Set(Done, Revoked, Failed)

/**
 * One refresh of one session's generation, its id `<session>/<generation>`: `start` is the atomic claim,
 * so of two callers near expiry the first starts the refresh and the second joins it (data-model.md: one
 * refresh at a time). The step asks the realm, then records the answer with the session, which takes it
 * only for the generation it is at; a refusal ends the session `revoked`. A realm that does not answer
 * is tried three times, then the flow ends `failed` and the session is left open.
 *
 * A retry, or a `failed` flow started again, asks with the same refresh token. That is safe only while
 * the `eitheror` realm does not rotate refresh tokens: under rotation, an answer lost on the way back
 * spends the token, the retry is refused, and the operator is signed out. The realm the modules tool
 * imports leaves rotation off, and the tool refuses to add a brand's backoffice client to a realm that
 * has it on (`Realm.ensure`, review of 2026-11-17).
 */
final class SessionRefresh(context: WorkflowContext) extends Workflow[RefreshFlow]:

  def emptyState: RefreshFlow = RefreshFlow()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(30.seconds)
      .stepRecovery(SessionRefresh.refresh, RecoverStrategy.maxRetries(2).failoverTo(SessionRefresh.failed))
      .build

  /**
   * True when this call started the refresh; false when it was started already (the caller joins it). A
   * refresh that failed is started again, so a realm back after an outage is asked once more.
   */
  def start(s: StartRefresh): Effect[Boolean] =
    if currentState.status != RefreshFlow.New && currentState.status != RefreshFlow.Failed then effects.reply(false)
    else
      effects.updateState(RefreshFlow(RefreshFlow.Refreshing, s.session, s.generation))
        .transitionTo(SessionRefresh.refresh.ref).thenReply(true)

  def refreshStep: StepEffect =
    val f       = currentState
    val session = context.componentClient.forEventSourcedEntity(EntityId(f.session))
    session.call(Session.refreshTokenFor).invoke(f.generation) match
      case None => stepEffects.updateState(f.copy(status = RefreshFlow.Done)).thenEnd
      case Some(token) =>
        val now = StateRuntime.now()
        StateRuntime.realm.refresh(token, now) match
          case Right(tokens) =>
            val o = session.call(Session.refreshed).invoke(Refresh(f.generation, tokens, now))
            stepEffects.updateState(f.copy(status = RefreshFlow.Done, rolesChanged = o.rolesChanged)).thenEnd
          case Left(RealmRefusal.Refused(_)) =>
            session.call(Session.end).invoke(End(EndReason.Revoked, now)): Unit
            stepEffects.updateState(f.copy(status = RefreshFlow.Revoked)).thenEnd
          case Left(RealmRefusal.Unavailable(why)) =>
            throw IllegalStateException(s"the realm did not answer the refresh: $why")

  def failedStep: StepEffect = stepEffects.updateState(currentState.copy(status = RefreshFlow.Failed)).thenEnd

  def status: ReadOnlyEffect[RefreshFlow] = effects.reply(currentState)

object SessionRefresh
    extends Workflow.Companion[SessionRefresh, RefreshFlow](
      componentId = ComponentId("session-refresh"),
      stateSerializer = StateCodecs.serializer[RefreshFlow]("refresh-flow")
    ):
  import com.thinkmorestupidless.ankka.core.Serializers.given

  given Serializer[StartRefresh] = StateCodecs.serializer[StartRefresh]("start-refresh")
  given Serializer[RefreshFlow]  = StateCodecs.serializer[RefreshFlow]("refresh-flow-reply")

  def create(context: WorkflowContext) = new SessionRefresh(context)

  def id(session: String, generation: Int): EntityId = EntityId(s"$session/$generation")

  val refresh = step("refresh")(_.refreshStep)
  val failed  = step("failed")(_.failedStep)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
