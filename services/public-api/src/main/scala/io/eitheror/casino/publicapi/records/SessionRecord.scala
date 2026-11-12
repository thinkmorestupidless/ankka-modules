package io.eitheror.casino.publicapi.records

import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.publicapi.PublicApiCodecs
import io.eitheror.casino.publicapi.PublicApiCodecs.given_Serializer_Boolean

import java.time.Instant

/** A game session (data-model.md `SessionRecord`); its status is derived when served, CLOSED once it has ended. */
final case class SessionState(
    id: String = "",
    playerId: String = "",
    gameId: Option[String] = None,
    startedAt: Option[Instant] = None,
    endedAt: Option[Instant] = None,
    closeReason: Option[String] = None,
    applied: Vector[String] = Vector.empty
)

object SessionFold:
  /** Only game sessions are the contract's sessions; a login session is not one (contracts/fields.md). */
  def sessionId(fact: f.CasinoSessionsFact): Option[String] = fact match
    case x: f.GameSessionStarted => Some(x.sessionId)
    case x: f.GameSessionEnded   => Some(x.sessionId)
    case _                       => None

  def apply(s0: SessionState, fact: f.CasinoSessionsFact): SessionState =
    val s = s0.copy(playerId = fact.playerId)
    fact match
      case x: f.GameSessionStarted =>
        s.copy(id = x.sessionId, gameId = Option(x.gameId).filter(_.nonEmpty).orElse(s.gameId), startedAt = Some(x.occurredAt))
      case x: f.GameSessionEnded =>
        s.copy(id = x.sessionId, gameId = s.gameId.orElse(Option(x.gameId).filter(_.nonEmpty)), endedAt = Some(x.occurredAt),
          closeReason = x.closeReason.orElse(s.closeReason))
      case _ => s0

final case class SessionFact(factId: String, fact: f.CasinoSessionsFact)

final class SessionRecord(@scala.annotation.unused context: KeyValueEntityContext) extends RecordEntity[SessionState]:
  def emptyState: SessionState                                   = SessionState()
  protected def applied(s: SessionState): Vector[String]        = s.applied
  protected def remembered(s: SessionState, ids: Vector[String]) = s.copy(applied = ids)

  def record(c: SessionFact): Effect[Boolean] = once(c.factId)(SessionFold(_, c.fact))
  def get: ReadOnlyEffect[SessionState]       = effects.reply(currentState)

object SessionRecord extends KeyValueEntity.Companion[SessionRecord, SessionState](
      componentId = ComponentId("session-record"),
      stateSerializer = PublicApiCodecs.serializer[SessionState]("session-record")
    ):
  given Serializer[SessionFact]  = Serializer.json[SessionFact]("public-api.session-fact")(using JsonCodecMaker.make[SessionFact])
  given Serializer[SessionState] = PublicApiCodecs.serializer[SessionState]("session-reply")

  def create(context: KeyValueEntityContext) = new SessionRecord(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)
