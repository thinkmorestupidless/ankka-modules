package io.eitheror.casino.backoffice.session

import com.thinkmorestupidless.ankka.core.{ComponentId, DataSubject, EntityId, ErrorCode, Personal, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.backoffice.StateCodecs

import java.security.SecureRandom
import java.time.Instant
import java.util.HexFormat
import scala.concurrent.duration.*

/** Why a session ended (data-model.md `Ended(reason)`). */
object EndReason:
  val SignedOut = "signed_out"
  val Expired   = "expired"
  val Revoked   = "revoked"
  val Erased    = "erased"

  val all: Set[String] = Set(SignedOut, Expired, Revoked, Erased)

/**
 * An operator's sign-in (data-model.md `Session`). The subject, name and email are personal fields of the
 * data subject `operator/<subject>`, and so are the tokens: the realm's access token carries the
 * operator's name and email among its claims, so it is sealed under the same key and an erasure leaves
 * it unreadable too. `Ended` clears them. `refreshes` counts the
 * refreshes recorded: the generation a refresh is claimed for, so two callers near expiry make one.
 */
final case class SessionState(
    subject: Option[Personal[String]] = None,
    name: Option[Personal[String]] = None,
    email: Option[Personal[String]] = None,
    accessToken: Option[Personal[String]] = None,
    refreshToken: Option[Personal[String]] = None,
    accessExpiresAt: Option[Instant] = None,
    refreshExpiresAt: Option[Instant] = None,
    roles: Set[String] = Set.empty,
    openedAt: Option[Instant] = None,
    endedAt: Option[Instant] = None,
    endReason: Option[String] = None,
    refreshes: Int = 0
):
  def exists: Boolean = openedAt.isDefined
  def open: Boolean   = exists && endedAt.isEmpty

  /** The realm's subject, from the data subject (in the clear, and kept by an erasure). */
  def subjectId: String = subject.fold("")(p => Session.subjectOf(p.subject))

  /** The access token, while it reads as present. */
  def access: Option[Secret] = accessToken.flatMap(Session.secretOf)

  /** The refresh token, while it reads as present. */
  def refresh: Option[Secret] = refreshToken.flatMap(Session.secretOf)

  /** Open and not past the refresh token's expiry at `now`. */
  def liveAt(now: Instant): Boolean = open && refreshExpiresAt.exists(now.isBefore)

sealed trait SessionEvent
object SessionEvent:
  /**
   * Opened, the tokens sealed under the operator's data subject (spec 030 review). [[Opened]] and
   * [[Refreshed]] wrote them in the clear; they are kept so a journal that holds them still recovers,
   * and are no longer written.
   */
  final case class Signed(subject: Personal[String], name: Personal[String], email: Personal[String],
      accessToken: Personal[String], refreshToken: Personal[String], accessExpiresAt: Instant, refreshExpiresAt: Instant,
      roles: Set[String], at: Instant) extends SessionEvent
  /** Refreshed, the tokens sealed under the operator's data subject. */
  final case class Renewed(accessToken: Personal[String], refreshToken: Personal[String], accessExpiresAt: Instant,
      refreshExpiresAt: Instant, roles: Set[String], at: Instant) extends SessionEvent
  /** Written before the tokens were sealed; read, never written. */
  final case class Opened(subject: Personal[String], name: Personal[String], email: Personal[String], accessToken: Secret,
      refreshToken: Secret, accessExpiresAt: Instant, refreshExpiresAt: Instant, roles: Set[String], at: Instant) extends SessionEvent
  /** Written before the tokens were sealed; read, never written. */
  final case class Refreshed(accessToken: Secret, refreshToken: Secret, accessExpiresAt: Instant, refreshExpiresAt: Instant,
      roles: Set[String], at: Instant) extends SessionEvent
  final case class Ended(reason: String, at: Instant) extends SessionEvent

/** Opens a session with what the code exchange answered. */
final case class Open(tokens: RealmTokens, at: Instant)

/** A token asked for at `now`. */
final case class TokenAsk(now: Instant)

/**
 * What a token ask is answered: `current` with the access token, `stale` (under 30 seconds left, or none)
 * with the generation to refresh, or `ended`.
 */
final case class TokenAnswer(state: String, accessToken: Option[Secret] = None, expiresAt: Option[Instant] = None,
    roles: Set[String] = Set.empty, generation: Int = 0)

object TokenAnswer:
  val Current = "current"
  val Stale   = "stale"
  val Ended   = "ended"

/** A refresh the realm answered, for the generation it was claimed for. */
final case class Refresh(generation: Int, tokens: RealmTokens, at: Instant)

/** Whether the refresh was recorded (its generation was the session's), and whether it changed the roles. */
final case class RefreshOutcome(applied: Boolean, rolesChanged: Boolean = false)

final case class End(reason: String, at: Instant)

/** Whether this call ended the session, and the refresh token it held, to revoke at the realm. */
final case class EndOutcome(ended: Boolean, refreshToken: Option[Secret] = None)

final case class ExpireAsk(now: Instant)

/** Whether the timer ended the session; when it did not, the expiry it is due at now. */
final case class ExpiryOutcome(ended: Boolean, due: Option[Instant] = None)

/** A session without its tokens: what a reader other than the token route sees. */
final case class SessionSummary(subject: String, open: Boolean, reason: Option[String], expiresAt: Option[Instant], refreshes: Int)

/**
 * The one writer of a session's tokens (research R2). `token` answers the access token while 30 seconds
 * or more remain, else the generation to refresh; [[SessionRefresh]] makes that generation's one refresh
 * and records it with `refreshed`, which takes only the generation the session is at. A refusal ends the
 * session `revoked`; `Ended` clears the tokens and the entity is deleted 24 hours later.
 */
final class Session(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[SessionState, SessionEvent]:
  import SessionEvent.*

  def emptyState: SessionState = SessionState()

  def applyEvent(event: SessionEvent): SessionState = event match
    case e: Signed =>
      SessionState(Some(e.subject), Some(e.name), Some(e.email), Some(e.accessToken), Some(e.refreshToken), Some(e.accessExpiresAt),
        Some(e.refreshExpiresAt), e.roles, Some(e.at))
    case e: Renewed =>
      currentState.copy(accessToken = Some(e.accessToken), refreshToken = Some(e.refreshToken), accessExpiresAt = Some(e.accessExpiresAt),
        refreshExpiresAt = Some(e.refreshExpiresAt), roles = e.roles, refreshes = currentState.refreshes + 1)
    case e: Opened =>
      val d = e.subject.subject
      SessionState(Some(e.subject), Some(e.name), Some(e.email), Some(Personal(d, e.accessToken.reveal)), Some(Personal(d, e.refreshToken.reveal)),
        Some(e.accessExpiresAt), Some(e.refreshExpiresAt), e.roles, Some(e.at))
    case e: Refreshed =>
      val d = currentState.subject.fold(Session.dataSubject(""))(_.subject)
      currentState.copy(accessToken = Some(Personal(d, e.accessToken.reveal)), refreshToken = Some(Personal(d, e.refreshToken.reveal)),
        accessExpiresAt = Some(e.accessExpiresAt), refreshExpiresAt = Some(e.refreshExpiresAt), roles = e.roles, refreshes = currentState.refreshes + 1)
    case e: Ended =>
      currentState.copy(accessToken = None, refreshToken = None, endedAt = Some(e.at), endReason = Some(e.reason))

  def open(c: Open): Effect[SessionSummary] =
    if currentState.exists then effects.error("the session is open already", ErrorCode.Conflict)
    else
      val t       = c.tokens
      val subject = Session.dataSubject(t.subject)
      effects.persist(Signed(Personal(subject, t.subject), Personal(subject, t.name), Personal(subject, t.email),
        Personal(subject, t.accessToken.reveal), Personal(subject, t.refreshToken.reveal), t.accessExpiresAt, t.refreshExpiresAt,
        t.roles, c.at)).thenReply(Session.summary)

  def token(a: TokenAsk): ReadOnlyEffect[TokenAnswer] = effects.reply(Session.answer(currentState, a.now, Session.Margin))

  /** As `token`, answering any access token not yet expired: what a caller reads after the refresh it waited for. */
  def current(a: TokenAsk): ReadOnlyEffect[TokenAnswer] = effects.reply(Session.answer(currentState, a.now, Duration.Zero))

  /** The refresh token, for the flow refreshing `generation`; none once the session has moved on or ended. */
  def refreshTokenFor(generation: Int): ReadOnlyEffect[Option[Secret]] =
    effects.reply(if currentState.open && currentState.refreshes == generation then currentState.refresh else None)

  def refreshed(r: Refresh): Effect[RefreshOutcome] =
    val s = currentState
    if !s.open || s.refreshes != r.generation || s.subject.isEmpty then effects.reply(RefreshOutcome(applied = false))
    else
      val t = r.tokens
      val d = s.subject.get.subject
      effects.persist(Renewed(Personal(d, t.accessToken.reveal), Personal(d, t.refreshToken.reveal), t.accessExpiresAt, t.refreshExpiresAt, t.roles, r.at))
        .thenReply(_ => RefreshOutcome(applied = true, rolesChanged = t.roles != s.roles))

  def end(c: End): Effect[EndOutcome] =
    val s = currentState
    if !EndReason.all(c.reason) then effects.error(s"no such reason: ${c.reason}", ErrorCode.BadRequest)
    else if !s.open then effects.reply(EndOutcome(ended = false))
    else effects.persist(Ended(c.reason, c.at)).expireAfter(Session.KeptAfterEnd).thenReply(_ => EndOutcome(ended = true, s.refresh))

  /** The expiry timer's check: ends the session at the refresh token's expiry, or answers when that now is. */
  def expire(a: ExpireAsk): Effect[ExpiryOutcome] =
    val s = currentState
    if !s.open then effects.reply(ExpiryOutcome(ended = false))
    else if s.liveAt(a.now) then effects.reply(ExpiryOutcome(ended = false, s.refreshExpiresAt))
    else effects.persist(Ended(EndReason.Expired, a.now)).expireAfter(Session.KeptAfterEnd).thenReply(_ => ExpiryOutcome(ended = true))

  def get: ReadOnlyEffect[SessionSummary] = effects.reply(Session.summary(currentState))

object Session
    extends EventSourcedEntity.Companion[Session, SessionState, SessionEvent](
      componentId = ComponentId("session"),
      stateSerializer = StateCodecs.serializer[SessionState]("session-state"),
      eventSerializer = StateCodecs.serializer[SessionEvent]("session-event")
    ):
  import com.thinkmorestupidless.ankka.core.Serializers.given

  /** A token is refreshed when less than this remains (data-model.md). */
  val Margin: FiniteDuration = 30.seconds

  /** How long an ended session is kept before the entity is deleted. */
  val KeptAfterEnd: FiniteDuration = 24.hours

  /** The data subject an operator's personal fields are kept under (research R2). */
  val SubjectPrefix = "operator/"

  def dataSubject(subject: String): DataSubject = DataSubject(s"$SubjectPrefix$subject")
  def subjectOf(d: DataSubject): String          = d.value.stripPrefix(SubjectPrefix)

  /** A sealed token as the realm's credential; none once its data subject is erased. */
  def secretOf(p: Personal[String]): Option[Secret] = p match
    case Personal.Present(_, value) => Some(Secret(value))
    case _                          => None

  private val random = SecureRandom()

  /** A new session id: 256 random bits, in hex. */
  def newId(): EntityId =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    EntityId(HexFormat.of().formatHex(bytes))

  /** Whether `id` could be a session id: 64 lower-case hex digits. */
  def validId(id: String): Boolean = id.length == 64 && id.forall(c => c.isDigit || (c >= 'a' && c <= 'f'))

  def summary(s: SessionState): SessionSummary = SessionSummary(s.subjectId, s.open, s.endReason, s.refreshExpiresAt, s.refreshes)

  private def answer(s: SessionState, now: Instant, margin: FiniteDuration): TokenAnswer =
    if !s.liveAt(now) then TokenAnswer(TokenAnswer.Ended)
    else
      val until = s.accessExpiresAt.getOrElse(Instant.EPOCH)
      val left  = java.time.Duration.between(now, until)
      if s.access.isDefined && !left.isNegative && left.toMillis >= margin.toMillis && now.isBefore(until) then
        TokenAnswer(TokenAnswer.Current, s.access, s.accessExpiresAt, s.roles, s.refreshes)
      else TokenAnswer(TokenAnswer.Stale, generation = s.refreshes)

  given Serializer[Open]            = StateCodecs.serializer[Open]("open")
  given Serializer[TokenAsk]        = StateCodecs.serializer[TokenAsk]("token-ask")
  given Serializer[TokenAnswer]     = StateCodecs.serializer[TokenAnswer]("token-answer")
  given Serializer[Refresh]         = StateCodecs.serializer[Refresh]("refresh")
  given Serializer[RefreshOutcome]  = StateCodecs.serializer[RefreshOutcome]("refresh-outcome")
  given Serializer[End]             = StateCodecs.serializer[End]("end")
  given Serializer[EndOutcome]      = StateCodecs.serializer[EndOutcome]("end-outcome")
  given Serializer[ExpireAsk]       = StateCodecs.serializer[ExpireAsk]("expire-ask")
  given Serializer[ExpiryOutcome]   = StateCodecs.serializer[ExpiryOutcome]("expiry-outcome")
  given Serializer[SessionSummary]  = StateCodecs.serializer[SessionSummary]("session-summary")
  given Serializer[Option[Secret]]  = StateCodecs.serializer[Option[Secret]]("refresh-token")

  def create(context: EventSourcedEntityContext) = new Session(context)

  val open            = command("open")(_.open)
  val refreshed       = command("refreshed")(_.refreshed)
  val end             = command("end")(_.end)
  val expire          = command("expire")(_.expire)
  val token           = query("token")(_.token)
  val current         = query("current")(_.current)
  val refreshTokenFor = query("refresh-token-for")(_.refreshTokenFor)
  val get             = query("get")(_.get)
