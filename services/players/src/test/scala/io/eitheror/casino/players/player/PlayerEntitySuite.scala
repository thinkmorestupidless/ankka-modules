package io.eitheror.casino.players.player

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.player.PlayerEvent.*

import java.time.{Duration, Instant}

/** data-model.md's command table for `PlayerEntity`, row by row and in its order (T011). */
class PlayerEntitySuite extends munit.FunSuite:

  private val at  = Instant.parse("2026-10-25T10:00:00Z")
  private def later(minutes: Long) = at.plusSeconds(minutes * 60)
  private val fiveMinutes = Duration.ofMinutes(5)

  private val profile = Profile(Map(
    "firstName" -> Personal("Ada"), "lastName" -> Personal("Lovelace"), "dateOfBirth" -> Personal("1990-01-02"),
    "country" -> Personal("GB")))
  private val consents = Consents(email = Consent(true, Some(at), Some("registration")))
  private val registration = Registration(tag = Some("a_1b_2c_"), clickId = Some("c-1"), bonusCode = Some("WELCOME"),
    networkAddress = Some(Personal("192.0.2.44")), networkCountry = Some("GB"), deviceFingerprint = Some(Personal("fp-1")))

  private def fresh = EventSourcedTestKit.of(PlayerEntity, "p-1")

  private def registered =
    val kit = fresh
    kit.call(PlayerEntity.register)(Register("p-1", profile, " Ada@Example.test ", "EUR", consents, registration, at)): Unit
    kit

  private def active =
    val kit = registered
    kit.call(PlayerEntity.verifyEmail)(At(at)): Unit
    kit

  private def signedIn =
    val kit = active
    kit.call(PlayerEntity.sessionOpened)(SessionId("ps-1", at)): Unit
    kit

  private def code(d: Done)          = d.refusal.map(_.code)
  private def code(r: Option[Refusal]) = r.map(_.code)

  // ── Register, VerifyEmail, ResendVerification ─────────────────────────────

  test("Register records the profile, consents with time and source, the registration and pending_email_verification") {
    val kit = registered
    val s   = kit.currentState
    assertEquals(s.status, Status.Pending)
    assertEquals(s.profile, profile)
    assertEquals(s.consents.email, Consent(true, Some(at), Some("registration")))
    assertEquals(s.registration, registration)
    assertEquals(s.pendingBonusCode, Some("WELCOME"))
    assertEquals(s.emails.map(a => (a.address.reveal, a.verified, a.primary)), Vector(("ada@example.test", false, true)))
    assertEquals(s.locked, Set("dateOfBirth", "country"))
    assertEquals(s.trail.map(t => (t.changeType, t.newValue)), Vector(("registered", Status.Pending)))
  }

  test("a second Register answers the first and persists nothing") {
    val kit    = registered
    val second = kit.call(PlayerEntity.register)(Register("p-1", Profile(), "other@example.test", "USD", Consents(), Registration(), later(1)))
    assertEquals(second.replyValue, RegisterAnswer("p-1", Status.Pending))
    assert(!second.persisted)
    assertEquals(kit.currentState.currency, "EUR")
  }

  test("VerifyEmail makes the player active and verifies the primary; again is no event") {
    val kit = registered
    assertEquals(kit.call(PlayerEntity.verifyEmail)(At(at)).replyValue, Done.changed)
    assertEquals(kit.currentState.status, Status.Active)
    assert(kit.currentState.primary.exists(_.verified))
    assert(!kit.call(PlayerEntity.verifyEmail)(At(later(1))).persisted)
  }

  test("ResendVerification: already_verified once active; rate_limited within 5 minutes of the last send") {
    assertEquals(code(active.call(PlayerEntity.resendVerification)(Resend(at, fiveMinutes)).replyValue), Some("already_verified"))
    val kit = registered
    kit.call(PlayerEntity.verificationSent)(At(at)): Unit
    val limited = kit.call(PlayerEntity.resendVerification)(Resend(later(4), fiveMinutes))
    assertEquals(code(limited.replyValue), Some("rate_limited"))
    assertEquals(limited.replyValue.refusal.get.details("retry_after_seconds"), "60")
    assert(kit.call(PlayerEntity.resendVerification)(Resend(later(5), fiveMinutes)).persisted)
    assertEquals(kit.currentState.verificationEmail.lastSentAt, Some(later(5)))
  }

  test("a verification email that could not be sent leaves the registration standing and the resend open") {
    val kit = registered
    kit.call(PlayerEntity.verificationPending)(At(at)): Unit
    assert(kit.currentState.verificationEmail.pending)
    assert(kit.call(PlayerEntity.resendVerification)(Resend(later(1), fiveMinutes)).persisted)
  }

  // ── SignIn and sessions ───────────────────────────────────────────────────

  test("SignIn is refused email_not_verified while pending, recording nothing") {
    val r = registered.call(PlayerEntity.signIn)(SignIn(at))
    assertEquals(code(r.replyValue.refusal), Some("email_not_verified"))
    assert(!r.persisted)
  }

  test("SignIn is refused account_restricted naming each restricting status, stamping lastRefusedSignInAt") {
    def restrict(kit: EventSourcedTestKit[PlayerEntity, PlayerState, PlayerEvent], status: String): Unit = status match
      case Status.Suspended    => kit.call(PlayerEntity.suspend)(Suspend("review", Some("op-1"), at)): Unit
      case Status.SelfExcluded => kit.call(PlayerEntity.setSelfExcluded)(SetSelfExcluded(None, at)): Unit
      case Status.Closed       => kit.call(PlayerEntity.close)(Close(Actor.Player, Some("p-1"), "leaving", at)): Unit
      case other               => kit.replay(StatusImported(other, at))
    Seq(Status.Suspended, Status.SelfExcluded, Status.Blocked, Status.Closed).foreach { status =>
      val kit = active
      restrict(kit, status)
      val r = kit.call(PlayerEntity.signIn)(SignIn(later(1)))
      assertEquals(r.replyValue.refusal, Some(Refusal("account_restricted", Map("reason" -> status))), status)
      assertEquals(kit.currentState.lastRefusedSignInAt, Some(later(1)), status)
    }
  }

  test("a served SignIn stamps lastSignInAt and answers the player's details") {
    val kit = active
    val r   = kit.call(PlayerEntity.signIn)(SignIn(later(2)))
    assertEquals(r.replyValue, SignInAnswer(None, "EUR", Some("GB"), Some("WELCOME")))
    assertEquals(kit.currentState.lastSignInAt, Some(later(2)))
  }

  test("SessionOpened answers the other open sessions; SessionEnded removes it once") {
    val kit = active
    assertEquals(kit.call(PlayerEntity.sessionOpened)(SessionId("ps-1", at)).replyValue, OtherSessions(Set.empty))
    assertEquals(kit.call(PlayerEntity.sessionOpened)(SessionId("ps-2", at)).replyValue, OtherSessions(Set("ps-1")))
    assert(!kit.call(PlayerEntity.sessionOpened)(SessionId("ps-2", at)).persisted)
    assert(kit.call(PlayerEntity.sessionEnded)(SessionId("ps-1", at)).persisted)
    assert(!kit.call(PlayerEntity.sessionEnded)(SessionId("ps-1", at)).persisted)
    assertEquals(kit.currentState.openPlayerSessions, Set("ps-2"))
  }

  test("GameSessionOpened is refused account_restricted, then session_expired with no open player session") {
    val suspended = signedIn
    suspended.call(PlayerEntity.suspend)(Suspend("review", None, at)): Unit
    assertEquals(code(suspended.call(PlayerEntity.gameSessionOpened)(SessionId("gs-1", at)).replyValue.refusal), Some("account_restricted"))
    assertEquals(code(active.call(PlayerEntity.gameSessionOpened)(SessionId("gs-1", at)).replyValue.refusal), Some("session_expired"))
  }

  test("GameSessionOpened answers the minutes left, and refuses session_limit_reached counting open sessions to now") {
    val kit = signedIn
    kit.call(PlayerEntity.setSessionLimit)(SetSessionLimit(Some(60), at)): Unit
    assertEquals(kit.call(PlayerEntity.gameSessionOpened)(SessionId("gs-1", at)).replyValue, GameSessionAnswer(None, Some(60)))
    kit.call(PlayerEntity.gameSessionEnded)(SessionId("gs-1", later(40))): Unit
    assertEquals(kit.call(PlayerEntity.gameSessionOpened)(SessionId("gs-2", later(41))).replyValue, GameSessionAnswer(None, Some(20)))
    val refused = kit.call(PlayerEntity.gameSessionOpened)(SessionId("gs-3", later(61)))
    assertEquals(refused.replyValue.refusal, Some(Refusal("session_limit_reached", Map("limitMinutes" -> "60", "usedMinutes" -> "60"))))
    assert(!refused.persisted)
  }

  test("no limit answers no remainder") {
    assertEquals(signedIn.call(PlayerEntity.gameSessionOpened)(SessionId("gs-1", at)).replyValue, GameSessionAnswer())
  }

  test("the fold prunes yesterday's ended sessions, and yesterday's minutes do not count today") {
    val kit = signedIn
    kit.call(PlayerEntity.setSessionLimit)(SetSessionLimit(Some(30), at)): Unit
    kit.call(PlayerEntity.gameSessionOpened)(SessionId("gs-1", at)): Unit
    kit.call(PlayerEntity.gameSessionEnded)(SessionId("gs-1", later(29))): Unit
    val tomorrow = at.plus(Duration.ofDays(1))
    assertEquals(kit.call(PlayerEntity.gameSessionOpened)(SessionId("gs-2", tomorrow)).replyValue, GameSessionAnswer(None, Some(30)))
    assertEquals(kit.currentState.todaysGameSessions.map(_.sessionId), Vector("gs-2"))
  }

  test("SetSessionLimit answers the open game sessions and their remainders") {
    val kit = signedIn
    kit.call(PlayerEntity.gameSessionOpened)(SessionId("gs-1", at)): Unit
    val r = kit.call(PlayerEntity.setSessionLimit)(SetSessionLimit(Some(45), later(15)))
    assertEquals(r.replyValue, Remainders(Map("gs-1" -> 30), limited = true))
    assertEquals(kit.currentState.sessionLimitMinutes, Some(45))
    assertEquals(kit.call(PlayerEntity.setSessionLimit)(SetSessionLimit(None, later(16))).replyValue, Remainders())
  }

  // ── the profile ───────────────────────────────────────────────────────────

  test("EditProfile drops a locked field, sets the rest, and records the fields changed") {
    val kit = active
    val r   = kit.call(PlayerEntity.editProfile)(EditProfile(Map("dateOfBirth" -> Some("2000-01-01"), "city" -> Some("London")), Set.empty, at))
    assertEquals(r.replyValue, Edited(None, Set("city")))
    assertEquals(kit.currentState.profile.get("dateOfBirth"), Some("1990-01-02"))
    assertEquals(kit.currentState.locked, Set("dateOfBirth", "country", "city"))
    assert(!kit.call(PlayerEntity.editProfile)(EditProfile(Map("city" -> Some("Paris")), Set.empty, at)).persisted, "city is now locked")
  }

  test("EditProfile refuses no_fields_provided, and invalid_country outside the brand's allow-list") {
    assertEquals(code(active.call(PlayerEntity.editProfile)(EditProfile(Map("email" -> Some("x")), Set.empty, at)).replyValue.refusal),
      Some("no_fields_provided"))
    val kit = EventSourcedTestKit.of(PlayerEntity, "p-2")
    kit.call(PlayerEntity.register)(Register("p-2", Profile(Map("firstName" -> Personal("Bo"))), "bo@example.test", "EUR", Consents(), Registration(), at)): Unit
    assertEquals(kit.call(PlayerEntity.editProfile)(EditProfile(Map("country" -> Some("fr")), Set("GB", "IE"), at)).replyValue.refusal,
      Some(Refusal("invalid_country", Map("country" -> "FR"))))
    assertEquals(code(kit.call(PlayerEntity.editProfile)(EditProfile(Map("country" -> Some("ZZ")), Set.empty, at)).replyValue.refusal),
      Some("invalid_country"))
    assert(kit.call(PlayerEntity.editProfile)(EditProfile(Map("country" -> Some("ie")), Set("GB", "IE"), at)).persisted)
    assertEquals(kit.currentState.profile.get("country"), Some("IE"))
  }

  test("CorrectDateOfBirth refuses in order: reason, value, date, underage, kyc_locked; KycWithdrawn lifts the lock") {
    val kit = active
    def correct(value: String, reason: String) =
      code(kit.call(PlayerEntity.correctDateOfBirth)(CorrectDateOfBirth(value, reason, "op-1", at)).replyValue)
    assertEquals(correct("", " "), Some("reason_required"))
    assertEquals(correct(" ", "typo"), Some("date_of_birth_required"))
    assertEquals(correct("02/01/1990", "typo"), Some("invalid_date_of_birth"))
    assertEquals(correct("2010-01-01", "typo"), Some("underage"))
    kit.call(PlayerEntity.kycVerified)(At(at)): Unit
    assertEquals(correct("1991-03-04", "typo"), Some("kyc_locked"))
    kit.call(PlayerEntity.kycWithdrawn)(At(at)): Unit
    assertEquals(correct("1991-03-04", "typo"), None)
    assertEquals(kit.currentState.profile.get("dateOfBirth"), Some("1991-03-04"))
    assertEquals(kit.currentState.trail.last.copy(at = at),
      AccountEvent("date_of_birth_corrected", Status.Active, Status.Active, Actor.Operator, Some("op-1"), Some("typo"), at))
  }

  // ── addresses ─────────────────────────────────────────────────────────────

  test("every email command and its refusals") {
    val kit = active
    def run(handle: PlayerEntity.type => com.thinkmorestupidless.ankka.sdk.CommandHandle[PlayerEntity, Address, Done], address: String) =
      code(kit.call(handle(PlayerEntity))(Address(address, at)).replyValue)
    assertEquals(run(_.addEmail, "not-an-address"), Some("invalid_address"))
    assertEquals(run(_.addEmail, "ADA@example.test"), Some("address_already_in_list"))
    assertEquals(run(_.addEmail, "two@example.test"), None)
    assertEquals(run(_.promoteEmail, "nobody@example.test"), Some("address_not_found"))
    assertEquals(run(_.promoteEmail, "two@example.test"), Some("not_verified"))
    assertEquals(run(_.verifyAddedEmail, "nobody@example.test"), Some("address_not_found"))
    assertEquals(run(_.verifyAddedEmail, "two@example.test"), None)
    assertEquals(run(_.promoteEmail, "ada@example.test"), Some("already_primary"))
    assertEquals(run(_.removeEmail, "ada@example.test"), Some("cannot_remove_primary"))
    assertEquals(run(_.promoteEmail, "two@example.test"), None)
    assertEquals(kit.currentState.primary.map(_.address.reveal), Some("two@example.test"))
    assertEquals(run(_.removeEmail, "ada@example.test"), None)
    assertEquals(run(_.removeEmail, "ada@example.test"), Some("address_not_found"))
    assertEquals(run(_.changeEmail, "bad"), Some("invalid_address"))
    assertEquals(run(_.changeEmail, "three@example.test"), None)
    assertEquals(kit.currentState.emails.map(a => (a.address.reveal, a.verified, a.primary)), Vector(("three@example.test", true, true)))
  }

  // ── consents, cookies, favourites ─────────────────────────────────────────

  test("ChangeConsent refuses no_fields_provided, records each change with time and source, and a repeat is no event") {
    val kit = active
    assertEquals(code(kit.call(PlayerEntity.changeConsent)(ChangeConsent(None, None, "player", None, at)).replyValue), Some("no_fields_provided"))
    val r = kit.call(PlayerEntity.changeConsent)(ChangeConsent(Some(false), Some(true), "player", Some("p-1"), later(1)))
    assertEquals(r.events.size, 2)
    assertEquals(kit.currentState.consents.sms, Consent(true, Some(later(1)), Some("player")))
    assertEquals(kit.currentState.consents.email, Consent(false, Some(later(1)), Some("player")))
    assert(!kit.call(PlayerEntity.changeConsent)(ChangeConsent(Some(false), Some(true), "player", None, later(2))).persisted)
    assert(kit.call(PlayerEntity.setMessagesSuppressed)(SetMessagesSuppressed(true, "op-1", at)).persisted)
    assert(kit.currentState.consents.messagesSuppressed)
  }

  test("RecordCookieDecision refuses missing_device_id, then invalid_cookie_consent_body, and replaces per device") {
    val kit = active
    def decide(device: Option[String], analytics: Option[Boolean]) =
      kit.call(PlayerEntity.recordCookieDecision)(RecordCookieDecision(device, analytics, Some(false), Some("v1"), Some("2026-10-25T10:00:00Z"), at))
    assertEquals(code(decide(None, None).replyValue), Some("missing_device_id"))
    assertEquals(code(decide(Some(" "), Some(true)).replyValue), Some("missing_device_id"))
    assertEquals(code(decide(Some("d-1"), None).replyValue), Some("invalid_cookie_consent_body"))
    decide(Some("d-1"), Some(true)): Unit
    decide(Some("d-1"), Some(false)): Unit
    decide(Some("d-2"), Some(true)): Unit
    assertEquals(kit.currentState.cookieDecisions.view.mapValues(_.analytics).toMap, Map("d-1" -> false, "d-2" -> true))
  }

  test("favourites: added and removed once each") {
    val kit = active
    assert(kit.call(PlayerEntity.addFavourite)(Favourite("g-1", at)).persisted)
    assert(!kit.call(PlayerEntity.addFavourite)(Favourite("g-1", at)).persisted)
    assertEquals(kit.currentState.favourites, Set("g-1"))
    assert(kit.call(PlayerEntity.removeFavourite)(Favourite("g-1", at)).persisted)
    assert(!kit.call(PlayerEntity.removeFavourite)(Favourite("g-1", at)).persisted)
  }

  // ── the status ────────────────────────────────────────────────────────────

  test("Suspend twice is one event and keeps the first reason; Reinstate refuses not_suspended") {
    val kit = active
    assertEquals(code(kit.call(PlayerEntity.reinstate)(Reinstate(Some("op-1"), None, at)).replyValue), Some("not_suspended"))
    assert(kit.call(PlayerEntity.suspend)(Suspend("review", Some("op-1"), at)).persisted)
    assert(!kit.call(PlayerEntity.suspend)(Suspend("again", Some("op-2"), later(1))).persisted)
    assertEquals(kit.currentState.suspension, Some(Suspension("review", at)))
    assert(kit.call(PlayerEntity.reinstate)(Reinstate(Some("op-1"), Some("cleared"), later(2))).persisted)
    assertEquals(kit.currentState.status, Status.Active)
  }

  test("SetSelfExcluded once; LiftExclusion refuses not_excluded") {
    val kit = active
    assertEquals(code(kit.call(PlayerEntity.liftExclusion)(At(at)).replyValue), Some("not_excluded"))
    val until = Some(later(60 * 24))
    assert(kit.call(PlayerEntity.setSelfExcluded)(SetSelfExcluded(until, at)).persisted)
    assert(!kit.call(PlayerEntity.setSelfExcluded)(SetSelfExcluded(until, at)).persisted)
    assertEquals(kit.currentState.exclusionUntil, until)
    assert(kit.call(PlayerEntity.liftExclusion)(At(later(1))).persisted)
    assertEquals(kit.currentState.status, Status.Active)
  }

  test("Close is idempotent; Reopen refuses not_closed, then hold_lapsed once the retention has passed") {
    val kit       = active
    val retention = Duration.ofDays(30)
    assertEquals(code(kit.call(PlayerEntity.reopen)(Reopen("op-1", "asked", retention, at)).replyValue), Some("not_closed"))
    assert(kit.call(PlayerEntity.close)(Close(Actor.Player, Some("p-1"), "leaving", at)).persisted)
    assert(!kit.call(PlayerEntity.close)(Close(Actor.Player, Some("p-1"), "leaving", later(1))).persisted)
    assertEquals(code(kit.call(PlayerEntity.reopen)(Reopen("op-1", "asked", retention, at.plus(retention))).replyValue), Some("hold_lapsed"))
    assert(kit.call(PlayerEntity.reopen)(Reopen("op-1", "asked", retention, later(5))).persisted)
    assertEquals(kit.currentState.status, Status.Active)
  }

  test("a blocked player (the migration's) is refused sign-in and play and closes like any other; no command reaches blocked") {
    val kit = signedIn
    kit.replay(StatusImported(Status.Blocked, at))
    assertEquals(code(kit.call(PlayerEntity.signIn)(SignIn(at)).replyValue.refusal), Some("account_restricted"))
    assertEquals(kit.call(PlayerEntity.gameSessionOpened)(SessionId("gs-1", at)).replyValue.refusal,
      Some(Refusal("account_restricted", Map("reason" -> Status.Blocked))))
    assert(kit.call(PlayerEntity.close)(Close(Actor.Operator, Some("op-1"), "duplicate", at)).persisted)
    assertEquals(kit.currentState.status, Status.Closed)
    val reached = active
    reached.call(PlayerEntity.suspend)(Suspend("x", None, at)): Unit
    reached.call(PlayerEntity.reinstate)(Reinstate(None, None, at)): Unit
    reached.call(PlayerEntity.setSelfExcluded)(SetSelfExcluded(None, at)): Unit
    reached.call(PlayerEntity.close)(Close(Actor.Player, None, "x", at)): Unit
    assert(!reached.allEvents.exists { case StatusImported(_, _) => true; case _ => false })
    assert(!reached.currentState.trail.exists(_.newValue == Status.Blocked))
  }

  test("SetVipTier answers the previous tier and records a change only; SetPotVip once") {
    val kit = active
    assertEquals(kit.call(PlayerEntity.setVipTier)(SetVipTier("gold", Some("op-1"), at)).replyValue, VipAnswer(None, changed = true))
    assertEquals(kit.call(PlayerEntity.setVipTier)(SetVipTier("gold", Some("op-1"), at)).replyValue, VipAnswer(Some("gold"), changed = false))
    assertEquals(kit.call(PlayerEntity.setVipTier)(SetVipTier("platinum", Some("op-1"), at)).replyValue, VipAnswer(Some("gold"), changed = true))
    assert(kit.call(PlayerEntity.setPotVip)(SetPotVip(true, Some("op-1"), at)).persisted)
    assert(!kit.call(PlayerEntity.setPotVip)(SetPotVip(true, Some("op-1"), at)).persisted)
  }

  test("FlagDuplicate is idempotent under the case id; ClearDuplicate removes the flag with its last case") {
    val kit = active
    assert(kit.call(PlayerEntity.flagDuplicate)(FlagDuplicate("c-1", Set("p-1", "p-9"), Set("identity"), at)).persisted)
    assert(!kit.call(PlayerEntity.flagDuplicate)(FlagDuplicate("c-1", Set("p-9"), Set("identity"), at)).persisted)
    assertEquals(kit.currentState.fraud.map(_.linkedPlayerIds), Some(Set("p-9")))
    assert(kit.call(PlayerEntity.clearDuplicate)(ClearDuplicate("c-1", Some("op-1"), at)).persisted)
    assertEquals(kit.currentState.fraud, None)
    assert(!kit.call(PlayerEntity.clearDuplicate)(ClearDuplicate("c-1", Some("op-1"), at)).persisted)
  }

  // ── erasure ───────────────────────────────────────────────────────────────

  test("FileErasure refuses not_closed") {
    assertEquals(code(active.call(PlayerEntity.fileErasure)(FileErasure("corr-1", later(60), at)).replyValue), Some("not_closed"))
  }

  test("Erase leaves every Personal field empty and every status, time and count, and again is no event") {
    val kit = active
    kit.call(PlayerEntity.addEmail)(Address("two@example.test", at)): Unit
    kit.call(PlayerEntity.recordCookieDecision)(RecordCookieDecision(Some("d-1"), Some(true), Some(true), Some("v1"), Some("t"), at)): Unit
    kit.call(PlayerEntity.addFavourite)(Favourite("g-1", at)): Unit
    kit.call(PlayerEntity.close)(Close(Actor.Player, Some("p-1"), "leaving", at)): Unit
    kit.call(PlayerEntity.fileErasure)(FileErasure("corr-1", later(60), later(1))): Unit
    assertEquals(kit.currentState.status, Status.ErasurePending)
    val before = kit.currentState
    assert(kit.call(PlayerEntity.erase)(At(later(61))).persisted)
    val s = kit.currentState
    assertEquals(s.status, Status.Erased)
    assertEquals(s.profile.fields, Map.empty)
    assert(s.emails.forall(_.address.reveal.isEmpty))
    assertEquals(s.emails.size, before.emails.size)
    assertEquals(s.cookieDecisions, Map.empty)
    assertEquals((s.registration.networkAddress, s.registration.deviceFingerprint), (None, None))
    assertEquals((s.registeredAt, s.closedAt, s.favourites, s.currency, s.registration.tag),
      (before.registeredAt, before.closedAt, before.favourites, before.currency, before.registration.tag))
    assertEquals(s.trail.init.map(_.copy(reason = None)), before.trail.map(_.copy(reason = None)))
    assert(!kit.call(PlayerEntity.erase)(At(later(62))).persisted)
  }

  // ── the trail and what the state holds ────────────────────────────────────

  test("every transition appends one trail entry with actor, actor id, reason and time") {
    val kit = active
    kit.call(PlayerEntity.suspend)(Suspend("review", Some("op-1"), later(1))): Unit
    kit.call(PlayerEntity.reinstate)(Reinstate(Some("op-2"), Some("cleared"), later(2))): Unit
    kit.call(PlayerEntity.close)(Close(Actor.Player, Some("p-1"), "leaving", later(3))): Unit
    assertEquals(kit.currentState.trail.drop(2), Vector(
      AccountEvent("suspended", Status.Active, Status.Suspended, Actor.Operator, Some("op-1"), Some("review"), later(1)),
      AccountEvent("reinstated", Status.Suspended, Status.Active, Actor.Operator, Some("op-2"), Some("cleared"), later(2)),
      AccountEvent("closed", Status.Active, Status.Closed, Actor.Player, Some("p-1"), Some("leaving"), later(3))))
    assertEquals(kit.currentState.trail(1).changeType, "email_verified")
  }

  test("the state has no field for a password, a realm token or a lookup token (SC-006)") {
    val names = PlayerState().productElementNames.map(_.toLowerCase).toVector ++
      Registration().productElementNames.map(_.toLowerCase)
    assert(!names.exists(n => n.contains("password") || n.contains("token") || n.contains("lookup")), names.mkString(", "))
  }
