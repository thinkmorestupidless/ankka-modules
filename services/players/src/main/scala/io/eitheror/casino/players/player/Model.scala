package io.eitheror.casino.players.player

import io.eitheror.casino.contract.personal.Personal

import java.time.Instant

/** A player's status (FR-031). `blocked` is the migration's: nothing here sets it. */
object Status:
  val Pending        = "pending_email_verification"
  val Active         = "active"
  val Suspended      = "suspended"
  val SelfExcluded   = "self_excluded"
  val Blocked        = "blocked"
  val Closed         = "closed"
  val ErasurePending = "erasure_pending"
  val Erased         = "erased"

  val all: Set[String] = Set(Pending, Active, Suspended, SelfExcluded, Blocked, Closed, ErasurePending, Erased)

  /** Closed for good or on the way to it: no operator action but reopen and erasure reaches it. */
  def ended(status: String): Boolean = status == Closed || status == ErasurePending || status == Erased

/** One entry of the account trail: every status transition, with who made it and why (FR-031). */
final case class AccountEvent(
    changeType: String,
    previousValue: String,
    newValue: String,
    actor: String,
    actorId: Option[String] = None,
    reason: Option[String] = None,
    at: Instant
)

object Actor:
  val Operator = "operator"
  val Player   = "player"
  val System   = "system"

/** One of the player's addresses; exactly one is primary, the realm's sign-in address (FR-026). */
final case class EmailAddress(address: Personal[String], verified: Boolean = false, primary: Boolean = false, addedAt: Instant)

/** A marketing consent: whether it is given, since when and from where (FR-028). */
final case class Consent(granted: Boolean = false, at: Option[Instant] = None, source: Option[String] = None)

/** The two marketing consents and the operator's flag that stops every message (FR-028, FR-030). */
final case class Consents(email: Consent = Consent(), sms: Consent = Consent(), messagesSuppressed: Boolean = false)

/** A device's cookie decision, every field required (FR-028). */
final case class CookieDecision(analytics: Boolean, marketing: Boolean, version: String, timestamp: String, recordedAt: Instant)

/** The duplicate flag (FR-036): the cases, the players they link, the signals, and when first flagged. */
final case class FraudFlag(caseIds: Vector[String] = Vector.empty, linkedPlayerIds: Set[String] = Set.empty,
    signals: Set[String] = Set.empty, flaggedAt: Instant)

/** A game session of the day, for the session limit (R5): open while `endedAt` is empty. */
final case class DaySession(sessionId: String, startedAt: Instant, endedAt: Option[Instant] = None)

/** What the registration presented (FR-001, FR-004): the tag, click id and code as sent, and where from. */
final case class Registration(
    tag: Option[String] = None,
    clickId: Option[String] = None,
    bonusCode: Option[String] = None,
    networkAddress: Option[Personal[String]] = None,
    networkCountry: Option[String] = None,
    deviceFingerprint: Option[Personal[String]] = None
)

/** The verification email's resend rate (R16). */
final case class VerificationEmail(lastSentAt: Option[Instant] = None, pending: Boolean = false)

final case class Suspension(reason: String, at: Instant)

final case class ErasureFiling(correlationId: String, holdUntil: Instant)

/**
 * A player (data-model.md): the status and its trail, the profile, the addresses, the consents, the
 * sessions the day's limit counts, the flags other services set, and what registration presented.
 * Nothing here is a password, a token the realm issued or a lookup token (SC-006).
 */
final case class PlayerState(
    playerId: String = "",
    status: String = "",
    trail: Vector[AccountEvent] = Vector.empty,
    profile: Profile = Profile(),
    locked: Set[String] = Set.empty,
    kycVerified: Boolean = false,
    currency: String = "",
    emails: Vector[EmailAddress] = Vector.empty,
    consents: Consents = Consents(),
    cookieDecisions: Map[String, CookieDecision] = Map.empty,
    favourites: Set[String] = Set.empty,
    vipTier: Option[String] = None,
    potVip: Boolean = false,
    fraud: Option[FraudFlag] = None,
    sessionLimitMinutes: Option[Int] = None,
    todaysGameSessions: Vector[DaySession] = Vector.empty,
    openPlayerSessions: Set[String] = Set.empty,
    pendingBonusCode: Option[String] = None,
    registeredAt: Option[Instant] = None,
    lastSignInAt: Option[Instant] = None,
    lastRefusedSignInAt: Option[Instant] = None,
    registration: Registration = Registration(),
    verificationEmail: VerificationEmail = VerificationEmail(),
    suspension: Option[Suspension] = None,
    closedAt: Option[Instant] = None,
    exclusionAt: Option[Instant] = None,
    exclusionUntil: Option[Instant] = None,
    erasure: Option[ErasureFiling] = None,
    withdrawalsOpen: Set[String] = Set.empty
):
  def exists: Boolean = status.nonEmpty
  def primary: Option[EmailAddress] = emails.find(_.primary)
  def address(text: String): Option[EmailAddress] = emails.find(_.address.reveal.equalsIgnoreCase(text.trim))
  def withdrawalOpen: Boolean = withdrawalsOpen.nonEmpty

/** A refusal: the code the route answers, and what it names. */
final case class Refusal(code: String, details: Map[String, String] = Map.empty)

/** A command's answer: refused, or done (and whether anything changed). */
final case class Done(refusal: Option[Refusal] = None, changed: Boolean = false)

object Done:
  val unchanged: Done              = Done()
  val changed: Done                = Done(changed = true)
  def refused(code: String, details: (String, String)*): Done = Done(Some(Refusal(code, details.toMap)))

// ── events ─────────────────────────────────────────────────────────────────

sealed trait PlayerEvent:
  def at: Instant

object PlayerEvent:
  final case class Registered(playerId: String, profile: Profile, address: Personal[String], currency: String, consents: Consents,
      registration: Registration, at: Instant) extends PlayerEvent
  final case class EmailVerified(at: Instant)                                                      extends PlayerEvent
  final case class VerificationEmailSent(at: Instant)                                              extends PlayerEvent
  final case class VerificationEmailPending(at: Instant)                                           extends PlayerEvent
  final case class SignedIn(at: Instant)                                                           extends PlayerEvent
  final case class SignInRefused(status: String, at: Instant)                                      extends PlayerEvent
  final case class SessionOpened(sessionId: String, at: Instant)                                   extends PlayerEvent
  final case class SessionEnded(sessionId: String, at: Instant)                                    extends PlayerEvent
  final case class GameSessionOpened(sessionId: String, at: Instant)                               extends PlayerEvent
  final case class GameSessionEnded(sessionId: String, at: Instant)                                extends PlayerEvent
  final case class SessionLimitSet(minutes: Option[Int], at: Instant)                              extends PlayerEvent
  final case class ProfileEdited(profile: Profile, changed: Set[String], at: Instant)               extends PlayerEvent
  final case class DateOfBirthCorrected(value: Personal[String], operatorId: String, reason: String, at: Instant) extends PlayerEvent
  final case class EmailAdded(address: Personal[String], at: Instant)                             extends PlayerEvent
  final case class EmailAddressVerified(address: Personal[String], at: Instant)                   extends PlayerEvent
  final case class EmailPromoted(address: Personal[String], at: Instant)                          extends PlayerEvent
  final case class EmailRemoved(address: Personal[String], at: Instant)                           extends PlayerEvent
  final case class EmailChanged(address: Personal[String], at: Instant)                           extends PlayerEvent
  final case class ConsentChanged(channel: String, granted: Boolean, source: String, actorId: Option[String], at: Instant) extends PlayerEvent
  final case class CookieDecisionRecorded(deviceId: Personal[String], decision: CookieDecision, at: Instant) extends PlayerEvent
  final case class FavouriteAdded(gameId: String, at: Instant)                                     extends PlayerEvent
  final case class FavouriteRemoved(gameId: String, at: Instant)                                   extends PlayerEvent
  final case class Suspended(previous: String, reason: String, actorId: Option[String], at: Instant) extends PlayerEvent
  final case class Reinstated(actorId: Option[String], reason: Option[String], at: Instant)       extends PlayerEvent
  final case class SelfExcluded(previous: String, until: Option[Instant], at: Instant)            extends PlayerEvent
  final case class ExclusionLifted(at: Instant)                                                    extends PlayerEvent
  final case class Closed(previous: String, actor: String, actorId: Option[String], reason: String, at: Instant) extends PlayerEvent
  final case class Reopened(actorId: String, reason: String, at: Instant)                          extends PlayerEvent
  final case class VipTierChanged(previous: Option[String], tier: String, actorId: Option[String], at: Instant) extends PlayerEvent
  final case class PotVipSet(potVip: Boolean, actorId: Option[String], at: Instant)                extends PlayerEvent
  final case class DuplicateFlagged(caseId: String, linked: Set[String], signals: Set[String], at: Instant) extends PlayerEvent
  final case class DuplicateCleared(caseId: String, actorId: Option[String], at: Instant)        extends PlayerEvent
  final case class KycVerified(at: Instant)                                                        extends PlayerEvent
  final case class KycWithdrawn(at: Instant)                                                       extends PlayerEvent
  final case class WithdrawalOpened(withdrawalId: String, at: Instant)                             extends PlayerEvent
  final case class WithdrawalSettled(withdrawalId: String, at: Instant)                            extends PlayerEvent
  final case class PendingBonusCodeCleared(at: Instant)                                            extends PlayerEvent
  final case class ErasureFiled(correlationId: String, holdUntil: Instant, at: Instant)            extends PlayerEvent
  final case class Erased(at: Instant)                                                             extends PlayerEvent

  /** A status carried over from the original by the migration (spec 019), `blocked` among them; no command persists it. */
  final case class StatusImported(status: String, at: Instant)                                     extends PlayerEvent

  /** An operator sent the realm's reset link (FR-033): audited, not a trail entry, and it changes nothing on the player. */
  final case class ResetLinkSent(operatorId: String, at: Instant)                                  extends PlayerEvent

// ── commands and their answers ─────────────────────────────────────────────

final case class Register(playerId: String, profile: Profile, address: String, currency: String, consents: Consents,
    registration: Registration, at: Instant)
final case class RegisterAnswer(playerId: String, status: String)
final case class At(at: Instant)
final case class Resend(at: Instant, interval: java.time.Duration)
final case class SignIn(at: Instant)

/** A sign-in the entity served but responsible gambling refused (FR-011): recorded as the refused attempt. */
final case class RefuseSignIn(reason: String, at: Instant)

/** What a served sign-in answers the route (FR-012): the player's details. */
final case class SignInAnswer(refusal: Option[Refusal] = None, currency: String = "", country: Option[String] = None,
    pendingBonusCode: Option[String] = None)

final case class SessionId(sessionId: String, at: Instant)

/** The other player sessions open when one opened (R4, FR-012 `singleSession`). */
final case class OtherSessions(others: Set[String])

/** What a game session opening was answered (FR-018, FR-019): refused, or the minutes the limit leaves. */
final case class GameSessionAnswer(refusal: Option[Refusal] = None, minutesLeft: Option[Int] = None)

final case class SetSessionLimit(minutes: Option[Int], at: Instant)

/** Each open game session and the minutes the new limit leaves it (none when there is no limit). */
final case class Remainders(open: Map[String, Int] = Map.empty, limited: Boolean = false)

final case class EditProfile(edit: Map[String, Option[String]], allowList: Set[String], at: Instant)
final case class Edited(refusal: Option[Refusal] = None, changed: Set[String] = Set.empty)
final case class CorrectDateOfBirth(value: String, reason: String, operatorId: String, at: Instant)
final case class Address(address: String, at: Instant)
final case class ChangeConsent(email: Option[Boolean], sms: Option[Boolean], source: String, actorId: Option[String], at: Instant)
final case class SetMessagesSuppressed(suppressed: Boolean, actorId: String, at: Instant)
final case class RecordCookieDecision(deviceId: Option[String], analytics: Option[Boolean], marketing: Option[Boolean],
    version: Option[String], timestamp: Option[String], at: Instant)
final case class Favourite(gameId: String, at: Instant)
final case class Suspend(reason: String, actorId: Option[String], at: Instant)
final case class Reinstate(actorId: Option[String], reason: Option[String], at: Instant)
final case class SetSelfExcluded(until: Option[Instant], at: Instant)
final case class Close(actor: String, actorId: Option[String], reason: String, at: Instant)
final case class Reopen(operatorId: String, reason: String, retention: java.time.Duration, at: Instant)
final case class SetVipTier(tier: String, actorId: Option[String], at: Instant)
final case class VipAnswer(previous: Option[String], changed: Boolean)
final case class SetPotVip(potVip: Boolean, actorId: Option[String], at: Instant)
final case class FlagDuplicate(caseId: String, linked: Set[String], signals: Set[String], at: Instant)
final case class ClearDuplicate(caseId: String, actorId: Option[String], at: Instant)
final case class Withdrawal(withdrawalId: String, at: Instant)
final case class FileErasure(correlationId: String, holdUntil: Instant, at: Instant)
final case class SendResetLink(operatorId: String, at: Instant)
