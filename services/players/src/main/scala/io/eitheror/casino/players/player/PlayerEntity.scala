package io.eitheror.casino.players.player

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.PlayersCodecs
import io.eitheror.casino.players.player.PlayerEvent.*

import java.time.{Duration, Instant, LocalDate, ZoneOffset}
import java.util.Locale

/**
 * A player (data-model.md): every rule of the account is a check here followed by the event it guards,
 * in data-model.md's order of refusals. Handlers call nothing and read no clock: every command carries
 * its time. A status transition is one event and one trail entry, and its repeat is no event.
 */
final class PlayerEntity(context: EventSourcedEntityContext) extends EventSourcedEntity[PlayerState, PlayerEvent]:
  import PlayerEntity.*

  def emptyState: PlayerState = PlayerState(playerId = context.entityId)

  // ── the fold ──────────────────────────────────────────────────────────────

  def applyEvent(event: PlayerEvent): PlayerState =
    val s = currentState
    def trail(changeType: String, next: String, actor: String, actorId: Option[String], reason: Option[String]) =
      s.trail :+ AccountEvent(changeType, s.status, next, actor, actorId, reason, event.at)
    def status(next: String, changeType: String, actor: String, actorId: Option[String] = None, reason: Option[String] = None) =
      s.copy(status = next, trail = trail(changeType, next, actor, actorId, reason))
    event match
      case e: Registered =>
        s.copy(playerId = e.playerId, status = Status.Pending, profile = e.profile, locked = Profile.lockedIn(e.profile),
          currency = e.currency, emails = Vector(EmailAddress(e.address, verified = false, primary = true, addedAt = e.at)),
          consents = e.consents, registration = e.registration, pendingBonusCode = e.registration.bonusCode,
          registeredAt = Some(e.at),
          trail = Vector(AccountEvent("registered", "", Status.Pending, Actor.Player, Some(e.playerId), None, e.at)))
      case _: EmailVerified =>
        status(Status.Active, "email_verified", Actor.Player).copy(
          emails = s.emails.map(a => if a.primary then a.copy(verified = true) else a),
          verificationEmail = s.verificationEmail.copy(pending = false))
      case e: VerificationEmailSent    => s.copy(verificationEmail = VerificationEmail(Some(e.at), pending = false))
      case _: VerificationEmailPending => s.copy(verificationEmail = s.verificationEmail.copy(pending = true))
      case e: SignedIn                 => s.copy(lastSignInAt = Some(e.at))
      case e: SignInRefused            => s.copy(lastRefusedSignInAt = Some(e.at))
      case e: SessionOpened            => s.copy(openPlayerSessions = s.openPlayerSessions + e.sessionId)
      case e: SessionEnded             => s.copy(openPlayerSessions = s.openPlayerSessions - e.sessionId)
      case e: GameSessionOpened        => s.copy(todaysGameSessions = today(s.todaysGameSessions, e.at) :+ DaySession(e.sessionId, e.at))
      case e: GameSessionEnded =>
        s.copy(todaysGameSessions = today(s.todaysGameSessions.map(d =>
          if d.sessionId == e.sessionId && d.endedAt.isEmpty then d.copy(endedAt = Some(e.at)) else d), e.at))
      case e: SessionLimitSet => s.copy(sessionLimitMinutes = e.minutes)
      case e: ProfileEdited   => s.copy(profile = e.profile, locked = s.locked ++ Profile.lockedIn(e.profile))
      case e: DateOfBirthCorrected =>
        s.copy(profile = Profile(s.profile.fields.updated("dateOfBirth", e.value)), locked = s.locked + "dateOfBirth",
          trail = trail("date_of_birth_corrected", s.status, Actor.Operator, Some(e.operatorId), Some(e.reason)))
      case e: EmailAdded           => s.copy(emails = s.emails :+ EmailAddress(e.address, addedAt = e.at))
      case e: EmailAddressVerified => s.copy(emails = s.emails.map(a => if same(a, e.address) then a.copy(verified = true) else a))
      case e: EmailPromoted        => s.copy(emails = s.emails.map(a => a.copy(primary = same(a, e.address))))
      case e: EmailRemoved         => s.copy(emails = s.emails.filterNot(a => same(a, e.address)))
      case e: EmailChanged =>
        s.copy(emails = s.emails.filterNot(a => a.primary || same(a, e.address)) :+
          EmailAddress(e.address, verified = true, primary = true, addedAt = e.at))
      case e: ConsentChanged =>
        val consent = Consent(e.granted, Some(e.at), Some(e.source))
        e.channel match
          case "email" => s.copy(consents = s.consents.copy(email = consent))
          case "sms"   => s.copy(consents = s.consents.copy(sms = consent))
          case _       => s.copy(consents = s.consents.copy(messagesSuppressed = !e.granted))
      case e: CookieDecisionRecorded => s.copy(cookieDecisions = s.cookieDecisions.updated(e.deviceId.reveal, e.decision))
      case e: FavouriteAdded         => s.copy(favourites = s.favourites + e.gameId)
      case e: FavouriteRemoved       => s.copy(favourites = s.favourites - e.gameId)
      case e: Suspended =>
        status(Status.Suspended, "suspended", Actor.Operator, e.actorId, Some(e.reason))
          .copy(suspension = Some(Suspension(e.reason, e.at)), kycDeadline = e.kycDeadline.orElse(s.kycDeadline))
      case e: SuspensionUpdated =>
        status(Status.Suspended, "suspension_updated", Actor.Operator, e.actorId, Some(e.reason))
          .copy(suspension = Some(Suspension(e.reason, e.at)), kycDeadline = e.kycDeadline.orElse(s.kycDeadline))
      case e: Reinstated => status(Status.Active, "reinstated", Actor.Operator, e.actorId, e.reason).copy(suspension = None)
      case e: SelfExcluded =>
        status(Status.SelfExcluded, "self_excluded", Actor.System).copy(exclusionAt = Some(e.at), exclusionUntil = e.until)
      case _: ExclusionLifted => status(Status.Active, "exclusion_lifted", Actor.System).copy(exclusionUntil = None)
      case e: Closed          => status(Status.Closed, "closed", e.actor, e.actorId, Some(e.reason)).copy(closedAt = Some(e.at))
      case e: Reopened        => status(Status.Active, "reopened", Actor.Operator, Some(e.actorId), Some(e.reason)).copy(closedAt = None)
      case e: VipTierChanged =>
        s.copy(vipTier = Some(e.tier), trail = s.trail :+ AccountEvent("vip_tier", e.previous.getOrElse(""), e.tier,
          Actor.Operator, e.actorId, None, e.at))
      case e: PotVipSet => s.copy(potVip = e.potVip)
      case e: DuplicateFlagged =>
        val f = s.fraud.getOrElse(FraudFlag(flaggedAt = e.at))
        s.copy(fraud = Some(f.copy(caseIds = f.caseIds :+ e.caseId, linkedPlayerIds = f.linkedPlayerIds ++ e.linked,
          signals = f.signals ++ e.signals)))
      case e: DuplicateCleared =>
        s.copy(fraud = s.fraud.map(f => f.copy(caseIds = f.caseIds.filterNot(_ == e.caseId))).filter(_.caseIds.nonEmpty))
      case _: KycVerified             => s.copy(kycVerified = true)
      case _: KycWithdrawn            => s.copy(kycVerified = false)
      case e: WithdrawalOpened        => s.copy(withdrawalsOpen = s.withdrawalsOpen + e.withdrawalId)
      case e: WithdrawalSettled       => s.copy(withdrawalsOpen = s.withdrawalsOpen - e.withdrawalId)
      case _: PendingBonusCodeCleared => s.copy(pendingBonusCode = None)
      case e: StatusImported          => status(e.status, "imported", Actor.System)
      case _: ResetLinkSent           => s
      case e: ErasureFiled =>
        status(Status.ErasurePending, "erasure_filed", Actor.System).copy(erasure = Some(ErasureFiling(e.correlationId, e.holdUntil)))
      case _: Erased =>
        status(Status.Erased, "erased", Actor.System).copy(
          profile = s.profile.erased,
          emails = s.emails.map(_.copy(address = Personal(""))),
          cookieDecisions = Map.empty,
          registration = s.registration.copy(networkAddress = None, deviceFingerprint = None),
          suspension = s.suspension.map(_.copy(reason = "")),
          trail = s.trail.map(_.copy(reason = None)) :+ AccountEvent("erased", s.status, Status.Erased, Actor.System, None, None, event.at))

  // ── registration and verification ─────────────────────────────────────────

  def register(c: Register): Effect[RegisterAnswer] =
    val s = currentState
    if s.exists then effects.reply(RegisterAnswer(s.playerId, s.status))
    else
      effects.persist(PlayerEvent.Registered(c.playerId, c.profile, Personal(c.address.trim.toLowerCase(Locale.ROOT)), c.currency,
        c.consents, c.registration, c.at)).thenReply(n => RegisterAnswer(n.playerId, n.status))

  def verifyEmail(c: At): Effect[Done] =
    if currentState.status == Status.Pending then effects.persist(EmailVerified(c.at)).thenReply(_ => Done.changed)
    else effects.reply(Done.unchanged)

  def resendVerification(c: Resend): Effect[Done] =
    val s = currentState
    if s.status != Status.Pending then effects.reply(Done.refused("already_verified"))
    else
      s.verificationEmail.lastSentAt.filter(t => t.plus(c.interval).isAfter(c.at)) match
        case Some(last) =>
          val wait = Duration.between(c.at, last.plus(c.interval)).toSeconds.max(1)
          effects.reply(Done.refused("rate_limited", "retry_after_seconds" -> wait.toString))
        case None => effects.persist(VerificationEmailSent(c.at)).thenReply(_ => Done.changed)

  /** The verification email went out (R16): its time is the resend rate's start. */
  def verificationSent(c: At): Effect[Done] = effects.persist(VerificationEmailSent(c.at)).thenReply(_ => Done.changed)

  /** The verification email could not be sent: the registration stands, and a resend is not rate-limited. */
  def verificationPending(c: At): Effect[Done] = effects.persist(VerificationEmailPending(c.at)).thenReply(_ => Done.changed)

  // ── sign-in and sessions ──────────────────────────────────────────────────

  def signIn(c: SignIn): Effect[SignInAnswer] =
    val s = currentState
    if !s.exists then effects.reply(SignInAnswer(Some(Refusal("invalid_credentials"))))
    else if s.status == Status.Pending then effects.reply(SignInAnswer(Some(Refusal("email_not_verified"))))
    else if s.status != Status.Active then
      effects.persist(SignInRefused(s.status, c.at)).thenReply(_ => SignInAnswer(Some(restricted(s.status))))
    else
      effects.persist(SignedIn(c.at)).thenReply(n => SignInAnswer(None, n.currency, n.profile.get("country"), n.pendingBonusCode))

  /** A refusal after the entity served the sign-in (`may-log-in`, R4): stamps the refused attempt. */
  def signInRefused(c: RefuseSignIn): Effect[Done] = effects.persist(SignInRefused(c.reason, c.at)).thenReply(_ => Done.changed)

  def sessionOpened(c: SessionId): Effect[OtherSessions] =
    val others = currentState.openPlayerSessions - c.sessionId
    if currentState.openPlayerSessions(c.sessionId) then effects.reply(OtherSessions(others))
    else effects.persist(SessionOpened(c.sessionId, c.at)).thenReply(_ => OtherSessions(others))

  def sessionEnded(c: SessionId): Effect[Done] =
    if currentState.openPlayerSessions(c.sessionId) then effects.persist(SessionEnded(c.sessionId, c.at)).thenReply(_ => Done.changed)
    else effects.reply(Done.unchanged)

  def gameSessionOpened(c: SessionId): Effect[GameSessionAnswer] =
    val s = currentState
    if s.status != Status.Active then effects.reply(GameSessionAnswer(Some(restricted(s.status))))
    else if s.todaysGameSessions.exists(_.sessionId == c.sessionId) then
      effects.reply(GameSessionAnswer(None, s.sessionLimitMinutes.map(m => m - used(s.todaysGameSessions, c.at))))
    else if s.openPlayerSessions.isEmpty then effects.reply(GameSessionAnswer(Some(Refusal("session_expired"))))
    else
      s.sessionLimitMinutes match
        case Some(limit) =>
          val spent = used(s.todaysGameSessions, c.at)
          if spent >= limit then
            effects.reply(GameSessionAnswer(Some(Refusal("session_limit_reached", Map("limitMinutes" -> limit.toString, "usedMinutes" -> spent.toString)))))
          else effects.persist(GameSessionOpened(c.sessionId, c.at)).thenReply(_ => GameSessionAnswer(None, Some(limit - spent)))
        case None => effects.persist(GameSessionOpened(c.sessionId, c.at)).thenReply(_ => GameSessionAnswer())

  def gameSessionEnded(c: SessionId): Effect[Done] =
    if currentState.todaysGameSessions.exists(d => d.sessionId == c.sessionId && d.endedAt.isEmpty) then
      effects.persist(GameSessionEnded(c.sessionId, c.at)).thenReply(_ => Done.changed)
    else effects.reply(Done.unchanged)

  def setSessionLimit(c: SetSessionLimit): Effect[Remainders] =
    val s    = currentState
    val open = today(s.todaysGameSessions, c.at).filter(_.endedAt.isEmpty).map(_.sessionId)
    def remainders = c.minutes.fold(Remainders()) { limit =>
      val left = limit - used(s.todaysGameSessions, c.at)
      Remainders(open.map(_ -> left).toMap, limited = true)
    }
    if s.sessionLimitMinutes == c.minutes then effects.reply(remainders)
    else effects.persist(SessionLimitSet(c.minutes, c.at)).thenReply(_ => remainders)

  // ── the profile ───────────────────────────────────────────────────────────

  def editProfile(c: EditProfile): Effect[Edited] =
    val s       = currentState
    val asked   = c.edit.filter((k, _) => Profile.editable(k))
    val country = asked.get("country").flatten.map(_.trim.toUpperCase(Locale.ROOT)).filter(_.nonEmpty)
    if asked.isEmpty then effects.reply(Edited(Some(Refusal("no_fields_provided"))))
    else if country.exists(cc => !s.locked("country") && !validCountry(cc, c.allowList)) then
      effects.reply(Edited(Some(Refusal("invalid_country", Map("country" -> country.get)))))
    else
      val edit   = Profile(s.profile, asked ++ country.map(cc => "country" -> Some(cc)), s.locked)
      if edit.changed.isEmpty then effects.reply(Edited())
      else effects.persist(ProfileEdited(edit.profile, edit.changed, c.at)).thenReply(_ => Edited(None, edit.changed))

  def correctDateOfBirth(c: CorrectDateOfBirth): Effect[Done] =
    val today = LocalDate.ofInstant(c.at, ZoneOffset.UTC)
    if c.reason.trim.isEmpty then effects.reply(Done.refused("reason_required"))
    else if c.value.trim.isEmpty then effects.reply(Done.refused("date_of_birth_required"))
    else
      Profile.date(c.value) match
        case None                                     => effects.reply(Done.refused("invalid_date_of_birth"))
        case Some(dob) if Profile.underage(dob, today) => effects.reply(Done.refused("underage"))
        case Some(_) if currentState.kycVerified      => effects.reply(Done.refused("kyc_locked"))
        case Some(dob) =>
          effects.persist(DateOfBirthCorrected(Personal(dob.toString), c.operatorId, c.reason.trim, c.at)).thenReply(_ => Done.changed)

  // ── addresses ─────────────────────────────────────────────────────────────

  def addEmail(c: Address): Effect[Done] =
    val text = c.address.trim.toLowerCase(Locale.ROOT)
    if !EmailShape.matches(text) then effects.reply(Done.refused("invalid_address"))
    else if currentState.address(text).nonEmpty then effects.reply(Done.refused("address_already_in_list"))
    else effects.persist(EmailAdded(Personal(text), c.at)).thenReply(_ => Done.changed)

  def verifyAddedEmail(c: Address): Effect[Done] =
    currentState.address(c.address) match
      case None                   => effects.reply(Done.refused("address_not_found"))
      case Some(a) if a.verified  => effects.reply(Done.unchanged)
      case Some(a)                => effects.persist(EmailAddressVerified(a.address, c.at)).thenReply(_ => Done.changed)

  def promoteEmail(c: Address): Effect[Done] =
    currentState.address(c.address) match
      case None                  => effects.reply(Done.refused("address_not_found"))
      case Some(a) if !a.verified => effects.reply(Done.refused("not_verified"))
      case Some(a) if a.primary  => effects.reply(Done.refused("already_primary"))
      case Some(a)               => effects.persist(EmailPromoted(a.address, c.at)).thenReply(_ => Done.changed)

  def removeEmail(c: Address): Effect[Done] =
    currentState.address(c.address) match
      case None                 => effects.reply(Done.refused("address_not_found"))
      case Some(a) if a.primary => effects.reply(Done.refused("cannot_remove_primary"))
      case Some(a)              => effects.persist(EmailRemoved(a.address, c.at)).thenReply(_ => Done.changed)

  /** The email-change link followed: the new address, verified, replaces the primary (FR-026). */
  def changeEmail(c: Address): Effect[Done] =
    val text = c.address.trim.toLowerCase(Locale.ROOT)
    if !EmailShape.matches(text) then effects.reply(Done.refused("invalid_address"))
    else if currentState.primary.exists(p => same(p, Personal(text))) then effects.reply(Done.unchanged)
    else effects.persist(EmailChanged(Personal(text), c.at)).thenReply(_ => Done.changed)

  // ── consents and favourites ───────────────────────────────────────────────

  def changeConsent(c: ChangeConsent): Effect[Done] =
    val s = currentState
    if c.email.isEmpty && c.sms.isEmpty then effects.reply(Done.refused("no_fields_provided"))
    else
      val events = Vector(
        c.email.filter(_ != s.consents.email.granted).map(g => ConsentChanged("email", g, c.source, c.actorId, c.at)),
        c.sms.filter(_ != s.consents.sms.granted).map(g => ConsentChanged("sms", g, c.source, c.actorId, c.at))
      ).flatten
      if events.isEmpty then effects.reply(Done.unchanged) else effects.persistAll(events).thenReply(_ => Done.changed)

  /** The operator's flag that stops every message (FR-030), as the `messages` channel's consent. */
  def setMessagesSuppressed(c: SetMessagesSuppressed): Effect[Done] =
    if currentState.consents.messagesSuppressed == c.suppressed then effects.reply(Done.unchanged)
    else effects.persist(ConsentChanged("messages", !c.suppressed, "operator", Some(c.actorId), c.at)).thenReply(_ => Done.changed)

  def recordCookieDecision(c: RecordCookieDecision): Effect[Done] =
    c.deviceId.map(_.trim).filter(_.nonEmpty) match
      case None => effects.reply(Done.refused("missing_device_id"))
      case Some(device) =>
        (c.analytics, c.marketing, c.version.filter(_.nonEmpty), c.timestamp.filter(_.nonEmpty)) match
          case (Some(a), Some(m), Some(v), Some(t)) =>
            effects.persist(CookieDecisionRecorded(Personal(device), CookieDecision(a, m, v, t, c.at), c.at)).thenReply(_ => Done.changed)
          case _ => effects.reply(Done.refused("invalid_cookie_consent_body"))

  def addFavourite(c: Favourite): Effect[Done] =
    if currentState.favourites(c.gameId) then effects.reply(Done.unchanged)
    else effects.persist(FavouriteAdded(c.gameId, c.at)).thenReply(_ => Done.changed)

  def removeFavourite(c: Favourite): Effect[Done] =
    if !currentState.favourites(c.gameId) then effects.reply(Done.unchanged)
    else effects.persist(FavouriteRemoved(c.gameId, c.at)).thenReply(_ => Done.changed)

  // ── the status ────────────────────────────────────────────────────────────

  def suspend(c: Suspend): Effect[Done] =
    val s = currentState
    // A window `kyc` has already suspended for is not suspended for again, though an operator reinstated since.
    if c.kycDeadline.nonEmpty && c.kycDeadline == s.kycDeadline then effects.reply(Done.unchanged)
    else if Status.ended(s.status) then effects.reply(Done.refused("account_closed"))
    else if s.status == Status.Suspended then
      if s.suspension.exists(_.reason == c.reason.trim) && c.kycDeadline.isEmpty then effects.reply(Done.unchanged)
      else effects.persist(SuspensionUpdated(c.reason.trim, c.actorId, c.at, c.kycDeadline)).thenReply(_ => Done.unchanged)
    else effects.persist(Suspended(s.status, c.reason.trim, c.actorId, c.at, c.kycDeadline)).thenReply(_ => Done.changed)

  def reinstate(c: Reinstate): Effect[Done] =
    if currentState.status != Status.Suspended then effects.reply(Done.refused("not_suspended"))
    else effects.persist(Reinstated(c.actorId, c.reason, c.at)).thenReply(_ => Done.changed)

  def setSelfExcluded(c: SetSelfExcluded): Effect[Done] =
    val s = currentState
    if Status.ended(s.status) then effects.reply(Done.refused("account_closed"))
    else if s.status == Status.SelfExcluded then effects.reply(Done.unchanged)
    else effects.persist(SelfExcluded(s.status, c.until, c.at)).thenReply(_ => Done.changed)

  def liftExclusion(c: At): Effect[Done] =
    if currentState.status != Status.SelfExcluded then effects.reply(Done.refused("not_excluded"))
    else effects.persist(ExclusionLifted(c.at)).thenReply(_ => Done.changed)

  def close(c: Close): Effect[Done] =
    val s = currentState
    if !s.exists then effects.reply(Done.refused("player_not_found"))
    else if Status.ended(s.status) then effects.reply(Done.unchanged)
    else effects.persist(Closed(s.status, c.actor, c.actorId, c.reason.trim, c.at)).thenReply(_ => Done.changed)

  def reopen(c: Reopen): Effect[Done] =
    val s = currentState
    if s.status != Status.Closed then effects.reply(Done.refused("not_closed"))
    else if s.closedAt.exists(t => !t.plus(c.retention).isAfter(c.at)) then effects.reply(Done.refused("hold_lapsed"))
    else effects.persist(Reopened(c.operatorId, c.reason.trim, c.at)).thenReply(_ => Done.changed)

  def setVipTier(c: SetVipTier): Effect[VipAnswer] =
    val previous = currentState.vipTier
    if previous.contains(c.tier) then effects.reply(VipAnswer(previous, changed = false))
    else effects.persist(VipTierChanged(previous, c.tier, c.actorId, c.at)).thenReply(_ => VipAnswer(previous, changed = true))

  def setPotVip(c: SetPotVip): Effect[Done] =
    if currentState.potVip == c.potVip then effects.reply(Done.unchanged)
    else effects.persist(PotVipSet(c.potVip, c.actorId, c.at)).thenReply(_ => Done.changed)

  // ── what other services and the duplicates find ───────────────────────────

  def flagDuplicate(c: FlagDuplicate): Effect[Done] =
    if currentState.fraud.exists(_.caseIds.contains(c.caseId)) then effects.reply(Done.unchanged)
    else effects.persist(DuplicateFlagged(c.caseId, c.linked - currentState.playerId, c.signals, c.at)).thenReply(_ => Done.changed)

  def clearDuplicate(c: ClearDuplicate): Effect[Done] =
    if !currentState.fraud.exists(_.caseIds.contains(c.caseId)) then effects.reply(Done.unchanged)
    else effects.persist(DuplicateCleared(c.caseId, c.actorId, c.at)).thenReply(_ => Done.changed)

  def kycVerified(c: At): Effect[Done] =
    if currentState.kycVerified then effects.reply(Done.unchanged) else effects.persist(KycVerified(c.at)).thenReply(_ => Done.changed)

  def kycWithdrawn(c: At): Effect[Done] =
    if !currentState.kycVerified then effects.reply(Done.unchanged) else effects.persist(KycWithdrawn(c.at)).thenReply(_ => Done.changed)

  def withdrawalOpened(c: Withdrawal): Effect[Done] =
    if currentState.withdrawalsOpen(c.withdrawalId) then effects.reply(Done.unchanged)
    else effects.persist(WithdrawalOpened(c.withdrawalId, c.at)).thenReply(_ => Done.changed)

  def withdrawalSettled(c: Withdrawal): Effect[Done] =
    if !currentState.withdrawalsOpen(c.withdrawalId) then effects.reply(Done.unchanged)
    else effects.persist(WithdrawalSettled(c.withdrawalId, c.at)).thenReply(_ => Done.changed)

  def clearPendingBonusCode(c: At): Effect[Done] =
    if currentState.pendingBonusCode.isEmpty then effects.reply(Done.unchanged)
    else effects.persist(PendingBonusCodeCleared(c.at)).thenReply(_ => Done.changed)

  // ── erasure ───────────────────────────────────────────────────────────────

  /** The operator's reset link, once the realm has sent it: recorded for the audit record only. */
  def resetLinkSent(c: SendResetLink): Effect[Done] = effects.persist(ResetLinkSent(c.operatorId, c.at)).thenReply(_ => Done.changed)

  def fileErasure(c: FileErasure): Effect[Done] =
    val s = currentState
    if s.status == Status.ErasurePending || s.status == Status.Erased then effects.reply(Done.unchanged)
    else if s.status != Status.Closed then effects.reply(Done.refused("not_closed"))
    else effects.persist(ErasureFiled(c.correlationId, c.holdUntil, c.at)).thenReply(_ => Done.changed)

  def erase(c: At): Effect[Done] =
    if currentState.status == Status.Erased then effects.reply(Done.unchanged)
    else effects.persist(Erased(c.at)).thenReply(_ => Done.changed)

  // ── reads ─────────────────────────────────────────────────────────────────

  def get: ReadOnlyEffect[PlayerState] = effects.reply(currentState)

object PlayerEntity
    extends EventSourcedEntity.Companion[PlayerEntity, PlayerState, PlayerEvent](
      componentId = ComponentId("player"),
      stateSerializer = PlayersCodecs.serializer[PlayerState]("player-state"),
      eventSerializer = PlayersCodecs.serializer[PlayerEvent]("player-event")
    ):

  given Serializer[Register]              = PlayersCodecs.serializer[Register]("register")
  given Serializer[RegisterAnswer]        = PlayersCodecs.serializer[RegisterAnswer]("register-answer")
  given Serializer[At]                    = PlayersCodecs.serializer[At]("at")
  given Serializer[RefuseSignIn]          = PlayersCodecs.serializer[RefuseSignIn]("refuse-sign-in")
  given Serializer[Resend]                = PlayersCodecs.serializer[Resend]("resend")
  given Serializer[Done]                  = PlayersCodecs.serializer[Done]("done")
  given Serializer[SignIn]                = PlayersCodecs.serializer[SignIn]("sign-in")
  given Serializer[SignInAnswer]          = PlayersCodecs.serializer[SignInAnswer]("sign-in-answer")
  given Serializer[SessionId]             = PlayersCodecs.serializer[SessionId]("session-id")
  given Serializer[OtherSessions]         = PlayersCodecs.serializer[OtherSessions]("other-sessions")
  given Serializer[GameSessionAnswer]     = PlayersCodecs.serializer[GameSessionAnswer]("game-session-answer")
  given Serializer[SetSessionLimit]       = PlayersCodecs.serializer[SetSessionLimit]("set-session-limit")
  given Serializer[Remainders]            = PlayersCodecs.serializer[Remainders]("remainders")
  given Serializer[EditProfile]           = PlayersCodecs.serializer[EditProfile]("edit-profile")
  given Serializer[Edited]                = PlayersCodecs.serializer[Edited]("edited")
  given Serializer[CorrectDateOfBirth]    = PlayersCodecs.serializer[CorrectDateOfBirth]("correct-date-of-birth")
  given Serializer[Address]               = PlayersCodecs.serializer[Address]("address")
  given Serializer[ChangeConsent]         = PlayersCodecs.serializer[ChangeConsent]("change-consent")
  given Serializer[SetMessagesSuppressed] = PlayersCodecs.serializer[SetMessagesSuppressed]("set-messages-suppressed")
  given Serializer[RecordCookieDecision]  = PlayersCodecs.serializer[RecordCookieDecision]("record-cookie-decision")
  given Serializer[Favourite]             = PlayersCodecs.serializer[Favourite]("favourite")
  given Serializer[Suspend]               = PlayersCodecs.serializer[Suspend]("suspend")
  given Serializer[Reinstate]             = PlayersCodecs.serializer[Reinstate]("reinstate")
  given Serializer[SetSelfExcluded]       = PlayersCodecs.serializer[SetSelfExcluded]("set-self-excluded")
  given Serializer[Close]                 = PlayersCodecs.serializer[Close]("close")
  given Serializer[Reopen]                = PlayersCodecs.serializer[Reopen]("reopen")
  given Serializer[SetVipTier]            = PlayersCodecs.serializer[SetVipTier]("set-vip-tier")
  given Serializer[VipAnswer]             = PlayersCodecs.serializer[VipAnswer]("vip-answer")
  given Serializer[SetPotVip]             = PlayersCodecs.serializer[SetPotVip]("set-pot-vip")
  given Serializer[FlagDuplicate]         = PlayersCodecs.serializer[FlagDuplicate]("flag-duplicate")
  given Serializer[ClearDuplicate]        = PlayersCodecs.serializer[ClearDuplicate]("clear-duplicate")
  given Serializer[Withdrawal]            = PlayersCodecs.serializer[Withdrawal]("withdrawal")
  given Serializer[FileErasure]           = PlayersCodecs.serializer[FileErasure]("file-erasure")
  given Serializer[SendResetLink]         = PlayersCodecs.serializer[SendResetLink]("send-reset-link")
  given Serializer[PlayerState]           = PlayersCodecs.serializer[PlayerState]("player-state-reply")

  override def snapshotEvery: Option[Int] = Some(100)

  def create(context: EventSourcedEntityContext) = new PlayerEntity(context)

  def id(playerId: String): EntityId = EntityId(playerId)

  val register              = command("register")(_.register)
  val verifyEmail           = command("verify-email")(_.verifyEmail)
  val resendVerification    = command("resend-verification")(_.resendVerification)
  val verificationSent      = command("verification-sent")(_.verificationSent)
  val verificationPending   = command("verification-pending")(_.verificationPending)
  val signIn                = command("sign-in")(_.signIn)
  val signInRefused         = command("sign-in-refused")(_.signInRefused)
  val sessionOpened         = command("session-opened")(_.sessionOpened)
  val sessionEnded          = command("session-ended")(_.sessionEnded)
  val gameSessionOpened     = command("game-session-opened")(_.gameSessionOpened)
  val gameSessionEnded      = command("game-session-ended")(_.gameSessionEnded)
  val setSessionLimit       = command("set-session-limit")(_.setSessionLimit)
  val editProfile           = command("edit-profile")(_.editProfile)
  val correctDateOfBirth    = command("correct-date-of-birth")(_.correctDateOfBirth)
  val addEmail              = command("add-email")(_.addEmail)
  val verifyAddedEmail      = command("verify-added-email")(_.verifyAddedEmail)
  val promoteEmail          = command("promote-email")(_.promoteEmail)
  val removeEmail           = command("remove-email")(_.removeEmail)
  val changeEmail           = command("change-email")(_.changeEmail)
  val changeConsent         = command("change-consent")(_.changeConsent)
  val setMessagesSuppressed = command("set-messages-suppressed")(_.setMessagesSuppressed)
  val recordCookieDecision  = command("record-cookie-decision")(_.recordCookieDecision)
  val addFavourite          = command("add-favourite")(_.addFavourite)
  val removeFavourite       = command("remove-favourite")(_.removeFavourite)
  val suspend               = command("suspend")(_.suspend)
  val reinstate             = command("reinstate")(_.reinstate)
  val setSelfExcluded       = command("set-self-excluded")(_.setSelfExcluded)
  val liftExclusion         = command("lift-exclusion")(_.liftExclusion)
  val close                 = command("close")(_.close)
  val reopen                = command("reopen")(_.reopen)
  val setVipTier            = command("set-vip-tier")(_.setVipTier)
  val setPotVip             = command("set-pot-vip")(_.setPotVip)
  val flagDuplicate         = command("flag-duplicate")(_.flagDuplicate)
  val clearDuplicate        = command("clear-duplicate")(_.clearDuplicate)
  val kycVerified           = command("kyc-verified")(_.kycVerified)
  val kycWithdrawn          = command("kyc-withdrawn")(_.kycWithdrawn)
  val withdrawalOpened      = command("withdrawal-opened")(_.withdrawalOpened)
  val withdrawalSettled     = command("withdrawal-settled")(_.withdrawalSettled)
  val clearPendingBonusCode = command("clear-pending-bonus-code")(_.clearPendingBonusCode)
  val fileErasure           = command("file-erasure")(_.fileErasure)
  val resetLinkSent         = command("reset-link-sent")(_.resetLinkSent)
  val erase                 = command("erase")(_.erase)
  val get                   = query("get")(_.get)

  /** `account_restricted` naming the status as the wire names it. */
  def restricted(status: String): Refusal = Refusal("account_restricted", Map("reason" -> status))

  private val EmailShape = """[^@\s]+@[^@\s]+\.[^@\s]+""".r

  private val IsoCountries: Set[String] = Locale.getISOCountries.toSet

  /** An ISO 3166-1 alpha-2 code, and in the brand's allow-list when it has one (the original's `isValidCountry`). */
  def validCountry(code: String, allowList: Set[String]): Boolean =
    IsoCountries(code) && (allowList.isEmpty || allowList(code))

  private def same(a: EmailAddress, address: Personal[String]): Boolean = a.address.reveal.equalsIgnoreCase(address.reveal)

  /** The day's sessions as of `at`: those of `at`'s UTC day, and any still open (R5). */
  private[player] def today(sessions: Vector[DaySession], at: Instant): Vector[DaySession] =
    val day = LocalDate.ofInstant(at, ZoneOffset.UTC)
    sessions.filter(d => d.endedAt.forall(e => !LocalDate.ofInstant(e, ZoneOffset.UTC).isBefore(day)))

  /** Whole minutes played on `at`'s UTC day, open sessions counted to `at`. */
  private[player] def used(sessions: Vector[DaySession], at: Instant): Int =
    val dayStart = LocalDate.ofInstant(at, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant
    today(sessions, at).map { d =>
      val from = if d.startedAt.isBefore(dayStart) then dayStart else d.startedAt
      val to   = d.endedAt.getOrElse(at)
      math.max(0L, Duration.between(from, to).toSeconds)
    }.sum.toInt / 60
