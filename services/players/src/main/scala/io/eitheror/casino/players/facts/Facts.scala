package io.eitheror.casino.players.facts

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.fraud.FraudCaseEvent
import io.eitheror.casino.players.inbox.{MessageEvent, MessageOrigin, NoteState}
import io.eitheror.casino.players.player.{Actor, PlayerEvent, Profile, Status}
import io.eitheror.casino.players.player.PlayerEvent.*
import io.eitheror.casino.players.sessions.{EndReason, GameSessionEvent, PlayerSessionEvent}

import java.time.Instant

/**
 * This service's events as the contract's facts (contracts/facts.md), as pure functions so the mapping
 * is tested with nothing running, as the wallet's. Each fact names the id a reader deduplicates on, also
 * its `ce-id`: the source's name, the entity id and the event's sequence number, so a redelivered event
 * is the same fact. The journal's types never leave the service (constitution IV).
 */
object Facts:

  final case class Published[F](fact: F, id: String)

  /** The id of the `n`th event of `entity` of `component`. */
  def eventId(component: String, entity: String, sequence: Long): String = s"$component/$entity/$sequence"

  // ── casino.players ────────────────────────────────────────────────────────

  /** `casino.players` for one player event; the events contracts/facts.md does not publish yield nothing. */
  def players(playerId: String, sequence: Long, event: PlayerEvent): Option[Published[f.CasinoPlayersFact]] =
    val fact: Option[f.CasinoPlayersFact] = event match
      case e: Registered =>
        def personal(field: String) = e.profile.get(field).map(Personal(_))
        Some(f.PlayerRegistered(playerId, e.at, e.registration.tag, e.registration.clickId, e.registration.bonusCode,
          e.profile.get("country"), Some(e.currency).filter(_.nonEmpty), None, e.at, personal("firstName"), personal("lastName"),
          personal("dateOfBirth"), personal("phone"), personal("addressLine1"), personal("city"), personal("postalCode"),
          Some(e.address)))
      case e: Suspended =>
        Some(f.PlayerSuspended(playerId, e.previous, Status.Suspended, Actor.Operator, e.actorId, Some(e.reason), e.at))
      case e: Reinstated =>
        Some(f.PlayerReinstated(playerId, Status.Suspended, Status.Active, Actor.Operator, e.actorId, e.reason, e.at))
      case e: SelfExcluded =>
        Some(f.PlayerSelfExcluded(playerId, e.previous, Status.SelfExcluded, Actor.System, None, None, e.at, e.until, e.at))
      case e: Closed =>
        Some(f.PlayerClosed(playerId, e.previous, Status.Closed, e.actor, e.actorId, Some(e.reason), e.at))
      case e: Reopened =>
        Some(f.PlayerReopened(playerId, Status.Closed, Status.Active, Actor.Operator, Some(e.actorId), Some(e.reason), e.at))
      case e: VipTierChanged =>
        Some(f.VipTierChanged(playerId, e.previous, e.tier, e.actorId.getOrElse(""), e.at))
      case e: ProfileEdited        => Some(profileUpdated(playerId, e.profile, e.changed.toVector.sorted, e.at))
      case e: DateOfBirthCorrected => Some(profileUpdated(playerId, Profile(Map("dateOfBirth" -> e.value)), Vector("dateOfBirth"), e.at))
      case e: ConsentChanged       => Some(f.PlayerConsentChanged(playerId, e.channel, e.granted, e.source, e.at))
      // The primary address, for payouts (spec 007 R7): promoted from a verified one, or changed by its link.
      case e: EmailPromoted        => Some(f.PlayerEmailChanged(playerId, e.address, e.at))
      case e: EmailChanged         => Some(f.PlayerEmailChanged(playerId, e.address, e.at))
      case e: DuplicateFlagged     => Some(f.PlayerFraudFlagged(playerId, e.caseId, e.signals.toVector.sorted.mkString(","), e.at))
      case e: DuplicateCleared     => Some(f.PlayerFraudCleared(playerId, e.caseId, e.at))
      case _                       => None
    fact.map(Published(_, eventId("player", playerId, sequence)))

  /**
   * The profile as `player_profile_updated` carries it (spec 009 FR-016): the eight fields `kyc` reads,
   * personal ones as `Personal`; every one the player holds is sent, so `kyc` can judge completeness from the
   * fact alone, and `changed_fields` names what this change moved (profile.feature).
   */
  private def profileUpdated(playerId: String, p: Profile, changed: Vector[String], at: Instant): f.PlayerProfileUpdated =
    def personal(field: String) = p.get(field).map(Personal(_))
    f.PlayerProfileUpdated(playerId, changed, personal("firstName"), personal("lastName"), personal("dateOfBirth"),
      personal("phone"), personal("addressLine1"), personal("city"), personal("postalCode"),
      p.get("country"), at)

  /** `player_message_*` on `casino.players`, keyed by the player. */
  def messages(messageId: String, sequence: Long, event: MessageEvent): Option[Published[f.CasinoPlayersFact]] =
    val fact: Option[f.CasinoPlayersFact] = event match
      case e: MessageEvent.Created => Some(f.PlayerMessageCreated(e.playerId, messageId, e.category, e.at))
      case e: MessageEvent.Read    => Some(f.PlayerMessageRead(e.playerId, messageId, e.at))
      case e: MessageEvent.Deleted => Some(f.PlayerMessageDeleted(e.playerId, messageId, e.at))
      case _                       => None
    fact.filter(_.playerId.nonEmpty).map(Published(_, eventId("message", messageId, sequence)))

  /** `player_logged_in` and `player_logged_out` on `casino.players`, from the player session. */
  def logins(sessionId: String, sequence: Long, event: PlayerSessionEvent): Option[Published[f.CasinoPlayersFact]] =
    val fact: Option[f.CasinoPlayersFact] = event match
      case e: PlayerSessionEvent.Opened => Some(f.PlayerLoggedIn(e.playerId, sessionId, e.at))
      case e: PlayerSessionEvent.Ended  => Option.when(e.playerId.nonEmpty)(f.PlayerLoggedOut(e.playerId, sessionId, EndReason.closeReason(e.reason), e.at))
      case _                            => None
    fact.map(Published(_, eventId("player-session-login", sessionId, sequence)))

  // ── casino.sessions ───────────────────────────────────────────────────────

  def playerSessions(sessionId: String, sequence: Long, event: PlayerSessionEvent): Option[Published[f.CasinoSessionsFact]] =
    val fact: Option[f.CasinoSessionsFact] = event match
      case e: PlayerSessionEvent.Opened => Some(f.PlayerSessionStarted(e.playerId, sessionId, e.networkAddress, e.userAgent, e.at))
      case e: PlayerSessionEvent.Ended  => Option.when(e.playerId.nonEmpty)(f.PlayerSessionEnded(e.playerId, sessionId, EndReason.closeReason(e.reason), e.at))
      case _                            => None
    fact.map(Published(_, eventId("player-session", sessionId, sequence)))

  def gameSessions(sessionId: String, sequence: Long, event: GameSessionEvent): Option[Published[f.CasinoSessionsFact]] =
    val fact: Option[f.CasinoSessionsFact] = event match
      case e: GameSessionEvent.Opened => Some(f.GameSessionStarted(e.playerId, sessionId, e.gameId, e.playerSessionId, e.at))
      case e: GameSessionEvent.Ended =>
        Option.when(e.playerId.nonEmpty)(f.GameSessionEnded(e.playerId, sessionId, e.gameId, e.durationMinutes.toInt, e.at, Some(e.reason)))
    fact.map(Published(_, eventId("game-session", sessionId, sequence)))

  // ── platform.audit ────────────────────────────────────────────────────────

  /** What an operator did, before it is a record: never a personal value (the field is named instead). */
  final case class Action(action: String, actorId: String, subjectKind: String, subjectId: String, reason: Option[String],
      changes: Vector[f.AuditChange], at: Instant)

  private def record(brand: String, id: String, a: Action): Published[f.AuditRecord] =
    Published(f.AuditRecord(recordId = id, brand = brand, service = "players", action = a.action, actorKind = "operator",
      actorId = a.actorId, approverId = None, subjectKind = a.subjectKind, subjectId = a.subjectId, outcome = "accepted",
      reasonCode = a.reason, changes = a.changes, correlationId = id, causationId = id, recordClass = "operator",
      occurredAt = a.at), id)

  private def change(field: String, before: String, after: String) = f.AuditChange(field, Some(before), Some(after))

  /** A field whose value is personal: named, with no value (spec 018 FR-003). */
  private def named(field: String) = f.AuditChange(field, None, None)

  /** The operator's actions on a player (contracts/facts.md's `platform.audit`). */
  def playerAudit(brand: String, playerId: String, sequence: Long, event: PlayerEvent): Option[Published[f.AuditRecord]] =
    def op(action: String, actorId: Option[String], reason: Option[String], changes: Vector[f.AuditChange], at: Instant) =
      Some(Action(action, actorId.getOrElse(""), "player", playerId, reason, changes, at))
    val action: Option[Action] = event match
      case e: Suspended  => op("suspend", e.actorId, Some(e.reason), Vector(change("status", e.previous, Status.Suspended)), e.at)
      case e: SuspensionUpdated => op("suspend", e.actorId, Some(e.reason), Vector.empty, e.at)
      case e: Reinstated => op("reinstate", e.actorId, e.reason, Vector(change("status", Status.Suspended, Status.Active)), e.at)
      case e: Closed if e.actor == Actor.Operator =>
        op("close", e.actorId, Some(e.reason), Vector(change("status", e.previous, Status.Closed)), e.at)
      case e: Reopened => op("reopen", Some(e.actorId), Some(e.reason), Vector(change("status", Status.Closed, Status.Active)), e.at)
      case e: VipTierChanged =>
        op("vip-tier", e.actorId, None, Vector(f.AuditChange("vipTier", e.previous, Some(e.tier))), e.at)
      case e: PotVipSet => op("pot-vip", e.actorId, None, Vector(change("potVip", (!e.potVip).toString, e.potVip.toString)), e.at)
      case e: DateOfBirthCorrected => op("date-of-birth", Some(e.operatorId), Some(e.reason), Vector(named("dateOfBirth")), e.at)
      case e: ConsentChanged if e.source == "operator" =>
        op("consent", e.actorId, None, Vector(change(e.channel, (!e.granted).toString, e.granted.toString)), e.at)
      case e: DuplicateCleared if e.actorId.nonEmpty => op("fraud-clear", e.actorId, None, Vector.empty, e.at)
      case e: ResetLinkSent => op("reset-password", Some(e.operatorId), None, Vector(named("password")), e.at)
      case _ => None
    action.map(record(brand, eventId("audit/player", playerId, sequence), _))

  /** An operator's message to a player: the body is never on the record. */
  def messageAudit(brand: String, messageId: String, sequence: Long, event: MessageEvent): Option[Published[f.AuditRecord]] =
    event match
      case e: MessageEvent.Created if e.origin == MessageOrigin.Operator =>
        Some(record(brand, eventId("audit/message", messageId, sequence),
          Action("message", e.createdBy.getOrElse(""), "player", e.playerId, None, Vector(named("subject"), named("body")), e.at)))
      case _ => None

  /** An operator's note on a player, written or pinned: the text is never on the record. */
  def noteAudit(brand: String, noteId: String, revision: Long, state: NoteState): Option[Published[f.AuditRecord]] =
    Option.when(state.exists)(record(brand, eventId("audit/note", noteId, revision),
      Action(if revision <= 1 then "note" else "note-pin", state.author, "player", state.playerId, None,
        Vector(named("text"), f.AuditChange("pinned", None, Some(state.pinned.toString))),
        state.updatedAt.orElse(state.createdAt).getOrElse(Instant.EPOCH))))

  /**
   * A game session ended by force (FR-021): the operator, or the support agent's approval, that forced it.
   * The subject is the player; the session is named, it is not personal.
   */
  def gameSessionAudit(brand: String, sessionId: String, sequence: Long, event: GameSessionEvent): Option[Published[f.AuditRecord]] =
    event match
      case e: GameSessionEvent.Ended if e.reason == "forced" && e.actorId.nonEmpty =>
        Some(record(brand, eventId("audit/game-session", sessionId, sequence),
          Action("force-end", e.actorId.get, "player", e.playerId, Some("forced"),
            Vector(f.AuditChange("gameSession", Some(sessionId), None), change("status", "active", "ended")), e.at)))
      case _ => None

  /** An operator's review or dismissal of a suspected duplicate. */
  def fraudAudit(brand: String, caseId: String, sequence: Long, event: FraudCaseEvent): Option[Published[f.AuditRecord]] =
    val action = event match
      case e: FraudCaseEvent.Reviewed  => Some(Action("fraud-review", e.operatorId, "fraud-case", caseId, None, Vector.empty, e.at))
      case e: FraudCaseEvent.Dismissed => Some(Action("fraud-dismiss", e.operatorId, "fraud-case", caseId, None, Vector.empty, e.at))
      case _                           => None
    action.map(record(brand, eventId("audit/fraud-case", caseId, sequence), _))
