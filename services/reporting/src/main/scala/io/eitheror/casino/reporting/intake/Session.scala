package io.eitheror.casino.reporting.intake

import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Personal, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.{ReportingCodecs, Settings}

import java.time.Instant

/**
 * One session (data-model.md `Session`, key value, id `g<n>/<session id>`): a player's login session or a game
 * session within one (`kind`), its player, start and end, close reason, a game session's game and login session,
 * and a login's network address and user agent as `Personal` under `player/<id>` (FR-025). Each fact sets the
 * fields it carries, so a repeat writes the same state.
 */
final case class SessionState(
    sessionId: String = "",
    kind: String = "",
    player: String = "",
    startedAt: Option[Instant] = None,
    endedAt: Option[Instant] = None,
    closeReason: Option[String] = None,
    gameId: Option[String] = None,
    playerSessionId: Option[String] = None,
    durationMinutes: Option[Int] = None,
    networkAddress: Option[Personal[String]] = None,
    userAgent: Option[Personal[String]] = None
):
  def open: Boolean = startedAt.isDefined && endedAt.isEmpty

object Sessions:
  val Player = "player"
  val Game   = "game"

  def sessionId(fact: f.CasinoSessionsFact): String = fact match
    case x: f.GameSessionStarted   => x.sessionId
    case x: f.GameSessionEnded     => x.sessionId
    case x: f.PlayerSessionStarted => x.sessionId
    case x: f.PlayerSessionEnded   => x.sessionId

  def apply(s0: SessionState, fact: f.CasinoSessionsFact): SessionState =
    val s = s0.copy(sessionId = sessionId(fact), player = fact.playerId)
    fact match
      case x: f.GameSessionStarted =>
        s.copy(kind = Game, startedAt = Some(x.occurredAt), gameId = Some(x.gameId), playerSessionId = Some(x.playerSessionId))
      case x: f.GameSessionEnded =>
        s.copy(kind = Game, endedAt = Some(x.occurredAt), gameId = Some(x.gameId), durationMinutes = Some(x.durationMinutes),
          closeReason = x.closeReason.orElse(s.closeReason))
      case x: f.PlayerSessionStarted =>
        s.copy(kind = Player, startedAt = Some(x.occurredAt), networkAddress = Personals.of(x.playerId, x.networkAddress),
          userAgent = Personals.of(x.playerId, x.userAgent))
      case x: f.PlayerSessionEnded => s.copy(kind = Player, endedAt = Some(x.occurredAt), closeReason = Some(x.closeReason))

/** A sessions fact. The contract's codec; the entity stores only the state. */
final case class SessionFact(fact: f.CasinoSessionsFact)

final class Session(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[SessionState]:
  def emptyState: SessionState = SessionState()

  /** Whether the state changed. */
  def record(c: SessionFact): Effect[Boolean] =
    val next = Sessions.apply(currentState, c.fact)
    if next == currentState then effects.reply(false) else effects.updateState(next).thenReply(_ => true)

  def get: ReadOnlyEffect[SessionState] = effects.reply(currentState)

object Session extends KeyValueEntity.Companion[Session, SessionState](
      componentId = ComponentId("session"),
      stateSerializer = ReportingCodecs.serializer[SessionState]("session")
    ):
  given Serializer[SessionFact] = Serializer.json[SessionFact]("reporting.session-fact")(using JsonCodecMaker.make[SessionFact])
  given Serializer[SessionState] = ReportingCodecs.serializer[SessionState]("session-reply")
  import ReportingCodecs.given_Serializer_Boolean

  def id(generation: Int, sessionId: String): String = Generation.id(generation, sessionId)

  def create(context: KeyValueEntityContext) = new Session(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)

/** `casino.sessions` into each session's `Session`. */
final class SessionsFactsConsumer(client: ComponentClient, settings: Settings) extends Consumer[Option[f.CasinoSessionsFact], Nothing]:
  def onMessage(fact: Option[f.CasinoSessionsFact]): Effect = fact match
    case None =>
      Lenient.unreadable(f.CasinoSessions.name, messageContext.subject)
      effects.ignore()
    case Some(s) =>
      client.forKeyValueEntity(EntityId(Session.id(settings.generation, Sessions.sessionId(s)))).call(Session.record)
        .invoke(SessionFact(s)): Unit
      effects.done()

final class SessionsFacts(settings: Settings) extends Consumer.Companion[SessionsFactsConsumer, Option[f.CasinoSessionsFact], Nothing](
      Generation.componentId("sessions-facts", settings.generation), Lenient.from(f.CasinoSessions)):
  def create(ctx: ConsumerContext) = new SessionsFactsConsumer(ctx.componentClient, settings)
