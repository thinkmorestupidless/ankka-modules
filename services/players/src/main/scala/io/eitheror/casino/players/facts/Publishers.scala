package io.eitheror.casino.players.facts

import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.audit.{AuditCompanion, AuditConsumer}
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.players.PlayersRuntime
import io.eitheror.casino.players.fraud.{FraudCaseEntity, FraudCaseEvent}
import io.eitheror.casino.players.inbox.{MessageEntity, MessageEvent, NoteEntity, NoteState}
import io.eitheror.casino.players.player.{PlayerEntity, PlayerEvent}
import io.eitheror.casino.players.sessions.{GameSessionEntity, GameSessionEvent, PlayerSessionEntity, PlayerSessionEvent}

/**
 * The service's topics, each fact produced by a consumer from an entity's change in the contract's shape
 * (constitution IV). A consumer reads one source and writes one topic, so `casino.players` has three
 * (the player's, the messages', the logins') and `casino.sessions` two. Delivery is at least once:
 * `ce-id` is the event's id, so a redelivered event is the same fact; the record key is the player.
 */
private[facts] object Publish:
  def one[F <: Fact](effects: com.thinkmorestupidless.ankka.core.effect.ConsumerEffects[F], p: Option[Facts.Published[F]]) =
    p.fold(effects.ignore())(x =>
      effects.produceAll(Seq(effects.message(x.fact).withKey(x.fact.key).withMetadata(Metadata.empty.set("ce-id", x.id)))))

/** A publisher's companion: one source, one topic, under the topic's contract. */
abstract class PublisherCompanion[C <: Consumer[E, F], E, F <: Fact](id: String, source: ChangeSource[E], topic: Topic[F])
    extends Consumer.Companion[C, E, F](ComponentId(id), source):
  override val outputSerializer: Option[Serializer[F]] = Some(topic.serializer)
  override val produceTo: Option[String]               = Some(topic.name)
  // The topic's contract, which the project declares: ankka refuses a publisher that names none.
  override val produces: Option[Publication] = Some(Publication(topic.name, Some(topic.contract), None))

// ── casino.players ─────────────────────────────────────────────────────────

final class PlayersPublisher extends Consumer[PlayerEvent, CasinoPlayersFact]:
  def onMessage(event: PlayerEvent): Effect =
    Publish.one(effects, Facts.players(messageContext.subject, messageContext.sequenceNumber, event))

object PlayersPublisher
    extends PublisherCompanion[PlayersPublisher, PlayerEvent, CasinoPlayersFact]("players-publisher",
      ChangeSource.eventsOf(PlayerEntity), CasinoPlayers):
  def create(ctx: ConsumerContext) = new PlayersPublisher

final class MessagesPublisher extends Consumer[MessageEvent, CasinoPlayersFact]:
  def onMessage(event: MessageEvent): Effect =
    Publish.one(effects, Facts.messages(messageContext.subject, messageContext.sequenceNumber, event))

object MessagesPublisher
    extends PublisherCompanion[MessagesPublisher, MessageEvent, CasinoPlayersFact]("messages-publisher",
      ChangeSource.eventsOf(MessageEntity), CasinoPlayers):
  def create(ctx: ConsumerContext) = new MessagesPublisher

/** `player_logged_in` and `player_logged_out`, from the player session the sign-in opened. */
final class LoginsPublisher extends Consumer[PlayerSessionEvent, CasinoPlayersFact]:
  def onMessage(event: PlayerSessionEvent): Effect =
    Publish.one(effects, Facts.logins(messageContext.subject, messageContext.sequenceNumber, event))

object LoginsPublisher
    extends PublisherCompanion[LoginsPublisher, PlayerSessionEvent, CasinoPlayersFact]("logins-publisher",
      ChangeSource.eventsOf(PlayerSessionEntity), CasinoPlayers):
  def create(ctx: ConsumerContext) = new LoginsPublisher

// ── casino.sessions ────────────────────────────────────────────────────────

final class PlayerSessionsPublisher extends Consumer[PlayerSessionEvent, CasinoSessionsFact]:
  def onMessage(event: PlayerSessionEvent): Effect =
    Publish.one(effects, Facts.playerSessions(messageContext.subject, messageContext.sequenceNumber, event))

object PlayerSessionsPublisher
    extends PublisherCompanion[PlayerSessionsPublisher, PlayerSessionEvent, CasinoSessionsFact]("player-sessions-publisher",
      ChangeSource.eventsOf(PlayerSessionEntity), CasinoSessions):
  def create(ctx: ConsumerContext) = new PlayerSessionsPublisher

final class GameSessionsPublisher extends Consumer[GameSessionEvent, CasinoSessionsFact]:
  def onMessage(event: GameSessionEvent): Effect =
    Publish.one(effects, Facts.gameSessions(messageContext.subject, messageContext.sequenceNumber, event))

object GameSessionsPublisher
    extends PublisherCompanion[GameSessionsPublisher, GameSessionEvent, CasinoSessionsFact]("game-sessions-publisher",
      ChangeSource.eventsOf(GameSessionEntity), CasinoSessions):
  def create(ctx: ConsumerContext) = new GameSessionsPublisher

// ── platform.audit ─────────────────────────────────────────────────────────

/** The operator's actions on a player. Actions a workflow or route makes (force-end, reset, export, erasure, config) are audited where they are made. */
final class AuditPublisher extends AuditConsumer[PlayerEvent]:
  protected def audit(change: PlayerEvent): Option[AuditRecord] =
    Facts.playerAudit(PlayersRuntime.settings.brand, messageContext.subject, messageContext.sequenceNumber, change)
      .map(_.fact)

object AuditPublisher extends AuditCompanion[AuditPublisher, PlayerEvent]("audit-publisher", ChangeSource.eventsOf(PlayerEntity)):
  def create(ctx: ConsumerContext) = new AuditPublisher

final class MessageAudit extends AuditConsumer[MessageEvent]:
  protected def audit(change: MessageEvent): Option[AuditRecord] =
    Facts.messageAudit(PlayersRuntime.settings.brand, messageContext.subject, messageContext.sequenceNumber, change)
      .map(_.fact)

object MessageAudit extends AuditCompanion[MessageAudit, MessageEvent]("message-audit", ChangeSource.eventsOf(MessageEntity)):
  def create(ctx: ConsumerContext) = new MessageAudit

final class NoteAudit extends AuditConsumer[NoteState]:
  protected def audit(change: NoteState): Option[AuditRecord] =
    Facts.noteAudit(PlayersRuntime.settings.brand, messageContext.subject, messageContext.sequenceNumber, change)
      .map(_.fact)

object NoteAudit extends AuditCompanion[NoteAudit, NoteState]("note-audit", ChangeSource.stateOf(NoteEntity)):
  def create(ctx: ConsumerContext) = new NoteAudit

final class FraudCaseAudit extends AuditConsumer[FraudCaseEvent]:
  protected def audit(change: FraudCaseEvent): Option[AuditRecord] =
    Facts.fraudAudit(PlayersRuntime.settings.brand, messageContext.subject, messageContext.sequenceNumber, change)
      .map(_.fact)

object FraudCaseAudit extends AuditCompanion[FraudCaseAudit, FraudCaseEvent]("fraud-case-audit", ChangeSource.eventsOf(FraudCaseEntity)):
  def create(ctx: ConsumerContext) = new FraudCaseAudit
