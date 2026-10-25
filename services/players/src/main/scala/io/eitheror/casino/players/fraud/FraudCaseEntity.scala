package io.eitheror.casino.players.fraud

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.PlayersCodecs

import java.time.Instant

/**
 * A suspected duplicate (FR-036): the players a registration matched and on what (`identity`,
 * `device`). Stored lower-case; the wire carries the original's upper-case statuses.
 */
final case class FraudCaseState(
    playerIds: Set[String] = Set.empty,
    signals: Set[String] = Set.empty,
    status: String = "",
    openedAt: Option[Instant] = None,
    reviewedBy: Option[String] = None,
    reviewedAt: Option[Instant] = None
):
  def exists: Boolean = status.nonEmpty

object FraudCaseStatus:
  val Open      = "open"
  val Reviewed  = "reviewed"
  val Dismissed = "dismissed"

  /** The original's wire names. */
  def wire(status: String): String = status.toUpperCase

sealed trait FraudCaseEvent
object FraudCaseEvent:
  final case class Opened(playerIds: Set[String], signals: Set[String], at: Instant) extends FraudCaseEvent
  final case class Reviewed(operatorId: String, at: Instant)                         extends FraudCaseEvent
  final case class Dismissed(operatorId: String, at: Instant)                        extends FraudCaseEvent

final case class OpenCase(playerIds: Set[String], signals: Set[String], at: Instant)
final case class DecideCase(operatorId: String, at: Instant)

/** Whether the call changed the case, a refusal, and the case as it then stands. */
final case class CaseAnswer(changed: Boolean, state: FraudCaseState, refusal: Option[String] = None)

final class FraudCaseEntity(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[FraudCaseState, FraudCaseEvent]:
  import FraudCaseEvent.*

  def emptyState: FraudCaseState = FraudCaseState()

  def applyEvent(event: FraudCaseEvent): FraudCaseState = event match
    case e: Opened    => FraudCaseState(e.playerIds, e.signals, FraudCaseStatus.Open, Some(e.at))
    case e: Reviewed  => currentState.copy(status = FraudCaseStatus.Reviewed, reviewedBy = Some(e.operatorId), reviewedAt = Some(e.at))
    case e: Dismissed => currentState.copy(status = FraudCaseStatus.Dismissed, reviewedBy = Some(e.operatorId), reviewedAt = Some(e.at))

  private def answer(refusal: Option[String] = None) = effects.reply(CaseAnswer(false, currentState, refusal))

  /** Opens the case once under its id; a repeat answers the first. */
  def open(c: OpenCase): Effect[CaseAnswer] =
    if currentState.exists then answer()
    else effects.persist(Opened(c.playerIds, c.signals, c.at)).thenReply(CaseAnswer(true, _))

  def review(c: DecideCase): Effect[CaseAnswer] =
    currentState.status match
      case ""                     => answer(Some("case_not_found"))
      case FraudCaseStatus.Open   => effects.persist(Reviewed(c.operatorId, c.at)).thenReply(CaseAnswer(true, _))
      case FraudCaseStatus.Reviewed => answer()
      case _                      => answer(Some("case_closed"))

  /** Dismisses the case; the caller clears the flag on every player it names. */
  def dismiss(c: DecideCase): Effect[CaseAnswer] =
    currentState.status match
      case ""                        => answer(Some("case_not_found"))
      case FraudCaseStatus.Dismissed => answer()
      case _                         => effects.persist(Dismissed(c.operatorId, c.at)).thenReply(CaseAnswer(true, _))

  def get: ReadOnlyEffect[FraudCaseState] = effects.reply(currentState)

object FraudCaseEntity
    extends EventSourcedEntity.Companion[FraudCaseEntity, FraudCaseState, FraudCaseEvent](
      componentId = ComponentId("fraud-case"),
      stateSerializer = PlayersCodecs.serializer[FraudCaseState]("fraud-case-state"),
      eventSerializer = PlayersCodecs.serializer[FraudCaseEvent]("fraud-case-event")
    ):

  given Serializer[FraudCaseState] = PlayersCodecs.serializer[FraudCaseState]("fraud-case-reply")
  given Serializer[OpenCase]       = PlayersCodecs.serializer[OpenCase]("open-case")
  given Serializer[DecideCase]     = PlayersCodecs.serializer[DecideCase]("decide-case")
  given Serializer[CaseAnswer]     = PlayersCodecs.serializer[CaseAnswer]("case-answer")

  def create(context: EventSourcedEntityContext) = new FraudCaseEntity(context)

  val open    = command("open")(_.open)
  val review  = command("review")(_.review)
  val dismiss = command("dismiss")(_.dismiss)
  val get     = query("get")(_.get)
