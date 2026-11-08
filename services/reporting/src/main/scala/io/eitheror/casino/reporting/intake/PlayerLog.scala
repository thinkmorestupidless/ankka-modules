package io.eitheror.casino.reporting.intake

import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Personal, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.{ReportingCodecs, Settings}

import java.time.Instant

/** A KYC document submitted and not yet decided. */
final case class Submission(documentId: String, category: String, at: Instant)

/**
 * One player (data-model.md `PlayerLog`, id `g<n>/<player>`): when and where they registered, their currency, the
 * email (`Personal` under `player/<id>`), status, KYC status, last login, whether the account is a test account,
 * and the KYC documents submitted and not yet decided. `status` is the last `new_value` of the status families,
 * `active` with none; `kycStatus` the last `new_value` of `player_kyc_verified`, `_rejected` and `_pending`,
 * `unverified` with none (research R5). A status or KYC fact older than the one applied changes neither.
 */
final case class PlayerLogState(
    player: String = "",
    registeredAt: Option[Instant] = None,
    country: Option[String] = None,
    currency: Option[String] = None,
    email: Option[Personal[String]] = None,
    status: String = "active",
    statusAt: Option[Instant] = None,
    kycStatus: String = "unverified",
    kycAt: Option[Instant] = None,
    lastLoginAt: Option[Instant] = None,
    testAccount: Boolean = false,
    submissions: Map[String, Submission] = Map.empty,
    /** The last KYC submission's time, which a status decision is reported as submitted at (FR-020). */
    submittedAt: Option[Instant] = None,
    seen: Vector[String] = Vector.empty
)

/** Every event names the player. Only `Registered` and `EmailChanged` hold a personal value, as `Personal`. */
sealed trait PlayerLogEvent:
  def factId: String
  def player: String
  def at: Instant

object PlayerLogEvent:
  final case class Registered(factId: String, player: String, registeredAt: Instant, country: Option[String],
      currency: Option[String], email: Option[Personal[String]], testAccount: Boolean, at: Instant) extends PlayerLogEvent
  /** A status family's change: `family` is the fact's, `to` its `new_value`; `until` a self-exclusion's end. */
  final case class StatusChanged(factId: String, player: String, family: String, from: String, to: String,
      actor: String, reason: Option[String], at: Instant, until: Option[Instant] = None) extends PlayerLogEvent
  /** A KYC status decision; `reason` the fact's, which the compliance export shows (FR-020). */
  final case class KycChanged(factId: String, player: String, family: String, to: String, at: Instant,
      reason: Option[String] = None, submitted: Option[Instant] = None) extends PlayerLogEvent
  final case class KycSubmitted(factId: String, player: String, document: String, category: String, at: Instant)
      extends PlayerLogEvent
  /** A document's decision with its submission's time, when the submission was seen (FR-020). */
  final case class KycDocumentDecided(factId: String, player: String, document: String, category: String,
      submitted: Option[Instant], decided: Instant, decision: String, reason: Option[String]) extends PlayerLogEvent:
    def at: Instant = decided
  final case class LoggedIn(factId: String, player: String, session: String, at: Instant) extends PlayerLogEvent
  final case class TestAccountChanged(factId: String, player: String, testAccount: Boolean, actor: String, at: Instant)
      extends PlayerLogEvent
  final case class EmailChanged(factId: String, player: String, email: Option[Personal[String]], at: Instant)
      extends PlayerLogEvent
  final case class RgCaseOpened(factId: String, player: String, rgCase: String, caseType: String, trigger: String,
      closesAt: Option[Instant], permanent: Boolean, at: Instant) extends PlayerLogEvent
  final case class RgCaseClosed(factId: String, player: String, rgCase: String, caseType: String, closure: String,
      at: Instant) extends PlayerLogEvent
  final case class RgLimitApplied(factId: String, player: String, limitType: String, period: Option[String],
      value: Option[Long], causedBy: String, currency: Option[String], at: Instant) extends PlayerLogEvent

object PlayerLogs:
  import PlayerLogEvent.*

  private def status(id: String, family: String, p: String, prev: String, next: String, actor: String,
      reason: Option[String], at: Instant, until: Option[Instant] = None) =
    StatusChanged(id, p, family, prev, next, actor, reason, at, until)

  def event(s: PlayerLogState, id: String, fact: f.CasinoPlayersFact): Option[PlayerLogEvent] =
    if s.seen.contains(id) then None
    else fact match
      case x: f.PlayerRegistered =>
        Some(Registered(id, x.playerId, x.registeredAt, x.country, x.currency, Personals.of(x.playerId, x.email),
          x.testAccount.getOrElse(false), x.occurredAt))
      case x: f.PlayerEmailChanged       => Some(EmailChanged(id, x.playerId, Personals.of(x.playerId, x.email), x.occurredAt))
      case x: f.PlayerTestAccountChanged => Some(TestAccountChanged(id, x.playerId, x.testAccount, x.actorId, x.occurredAt))
      case x: f.PlayerLoggedIn           => Some(LoggedIn(id, x.playerId, x.sessionId, x.occurredAt))
      case x: f.PlayerSuspended =>
        Some(status(id, "player_suspended", x.playerId, x.previousValue, x.newValue, x.actor, x.reason, x.occurredAt))
      case x: f.PlayerReinstated =>
        Some(status(id, "player_reinstated", x.playerId, x.previousValue, x.newValue, x.actor, x.reason, x.occurredAt))
      case x: f.PlayerSelfExcluded =>
        Some(status(id, "player_self_excluded", x.playerId, x.previousValue, x.newValue, x.actor, x.reason, x.occurredAt, x.until))
      case x: f.PlayerClosed =>
        Some(status(id, "player_closed", x.playerId, x.previousValue, x.newValue, x.actor, x.reason, x.occurredAt))
      case x: f.PlayerReopened =>
        Some(status(id, "player_reopened", x.playerId, x.previousValue, x.newValue, x.actor, x.reason, x.occurredAt))
      case x: f.PlayerKycVerified => Some(KycChanged(id, x.playerId, "player_kyc_verified", x.newValue, x.occurredAt, x.reason, s.submittedAt))
      case x: f.PlayerKycRejected => Some(KycChanged(id, x.playerId, "player_kyc_rejected", x.newValue, x.occurredAt, x.reason, s.submittedAt))
      case x: f.PlayerKycPending  => Some(KycChanged(id, x.playerId, "player_kyc_pending", x.newValue, x.occurredAt, x.reason, s.submittedAt))
      case x: f.PlayerKycSubmitted => Some(KycSubmitted(id, x.playerId, x.documentId, x.category, x.occurredAt))
      case x: f.PlayerKycDocumentReviewed =>
        Some(KycDocumentDecided(id, x.playerId, x.documentId, x.category, s.submissions.get(x.documentId).map(_.at),
          x.occurredAt, x.decision, x.reasonCode))
      case x: f.RgCaseOpened =>
        Some(RgCaseOpened(id, x.playerId, x.rgCaseId, x.caseType, x.trigger, x.closesAt, x.permanent.getOrElse(false), x.occurredAt))
      case x: f.RgCaseClosed => Some(RgCaseClosed(id, x.playerId, x.rgCaseId, x.caseType, x.closure, x.occurredAt))
      case x: f.RgLimitApplied =>
        Some(RgLimitApplied(id, x.playerId, x.limitType, x.period, x.value, x.causedBy, x.currency, x.occurredAt))
      case _ => None

  private def newer(last: Option[Instant], at: Instant): Boolean = last.forall(l => !at.isBefore(l))

  def apply(s0: PlayerLogState, e: PlayerLogEvent): PlayerLogState =
    val s = s0.copy(player = e.player, seen = FactId.remember(s0.seen, e.factId))
    e match
      case r: Registered =>
        s.copy(registeredAt = Some(r.registeredAt), country = r.country.orElse(s.country), currency = r.currency.orElse(s.currency),
          email = r.email.orElse(s.email), testAccount = r.testAccount || s.testAccount)
      case c: StatusChanged      => if newer(s.statusAt, c.at) then s.copy(status = c.to, statusAt = Some(c.at)) else s
      case k: KycChanged         => if newer(s.kycAt, k.at) then s.copy(kycStatus = k.to, kycAt = Some(k.at)) else s
      case k: KycSubmitted       =>
        s.copy(submissions = s.submissions.updated(k.document, Submission(k.document, k.category, k.at)),
          submittedAt = Some(s.submittedAt.filter(_.isAfter(k.at)).getOrElse(k.at)))
      case d: KycDocumentDecided => s.copy(submissions = s.submissions - d.document)
      case l: LoggedIn           => s.copy(lastLoginAt = Some(s.lastLoginAt.filter(_.isAfter(l.at)).getOrElse(l.at)))
      case t: TestAccountChanged => s.copy(testAccount = t.testAccount)
      case m: EmailChanged       => s.copy(email = m.email.orElse(s.email))
      case _: RgCaseOpened | _: RgCaseClosed | _: RgLimitApplied => s

/** A players fact. The contract's codec; the entity stores only its events. */
final case class PlayerFact(factId: String, fact: f.CasinoPlayersFact)

final class PlayerLog(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[PlayerLogState, PlayerLogEvent]:
  def emptyState: PlayerLogState = PlayerLogState()

  def applyEvent(event: PlayerLogEvent): PlayerLogState = PlayerLogs.apply(currentState, event)

  /** Whether the fact was new. */
  def record(c: PlayerFact): Effect[Boolean] =
    PlayerLogs.event(currentState, c.factId, c.fact).fold(effects.reply(false))(e => effects.persist(e).thenReply(_ => true))

  /** Whether the player is a test account now, which the day's fold reads (research R4); no personal value. */
  def testAccount: ReadOnlyEffect[Boolean] = effects.reply(currentState.testAccount)

  def get: ReadOnlyEffect[PlayerLogState] = effects.reply(currentState)

object PlayerLog extends EventSourcedEntity.Companion[PlayerLog, PlayerLogState, PlayerLogEvent](
      componentId = ComponentId("player-log"),
      stateSerializer = ReportingCodecs.serializer[PlayerLogState]("player-log"),
      eventSerializer = ReportingCodecs.serializer[PlayerLogEvent]("player-log-event")
    ):
  given Serializer[PlayerFact] = Serializer.json[PlayerFact]("reporting.player-fact")(using JsonCodecMaker.make[PlayerFact])
  import ReportingCodecs.given_Serializer_Boolean

  def id(generation: Int, player: String): String = Generation.id(generation, player)

  def create(context: EventSourcedEntityContext) = new PlayerLog(context)

  val record      = command("record")(_.record)
  val testAccount = query("test-account")(_.testAccount)
  val get         = query("get")(_.get)

/** `casino.players` into each player's `PlayerLog`. */
final class PlayersFactsConsumer(client: ComponentClient, settings: Settings) extends Consumer[Option[f.CasinoPlayersFact], Nothing]:
  def onMessage(fact: Option[f.CasinoPlayersFact]): Effect = fact match
    case None =>
      Lenient.unreadable(f.CasinoPlayers.name, messageContext.subject)
      effects.ignore()
    case Some(p) =>
      client.forEventSourcedEntity(EntityId(PlayerLog.id(settings.generation, p.playerId))).call(PlayerLog.record)
        .invoke(PlayerFact(FactId.of(f.CasinoPlayers, p), p)): Unit
      Coverage.observed(client, settings, f.CasinoPlayers.name, p.occurredAt)
      effects.done()

final class PlayersFacts(settings: Settings) extends Consumer.Companion[PlayersFactsConsumer, Option[f.CasinoPlayersFact], Nothing](
      Generation.componentId("players-facts", settings.generation), Lenient.from(f.CasinoPlayers)):
  def create(ctx: ConsumerContext) = new PlayersFactsConsumer(ctx.componentClient, settings)
