package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.time.{Duration, Instant}
import java.util.{Base64, HexFormat}

/**
 * An invitation or reset token (research R11), id the SHA-256 of the token so the token itself is never
 * stored: claimed once, atomically (the original's claim was not, `AffiliateInvitationTokenStore.scala:89-103`).
 */
final case class TokenState(user: String = "", purpose: String = "", brand: Option[String] = None, expiresAt: Option[Instant] = None,
    usedAt: Option[Instant] = None):
  def issued: Boolean = user.nonEmpty

final case class IssueToken(user: String, purpose: String, brand: Option[String], expiresAt: Instant)

object TokenCodecs:
  given Serializer[TokenState] = ProgrammeCodecs.serializer[TokenState]("token")
  given Serializer[IssueToken] = ProgrammeCodecs.serializer[IssueToken]("issue-token")
  given Serializer[Instant]    = ProgrammeCodecs.serializer[Instant]("instant")

import TokenCodecs.given

final class Token(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[TokenState]:
  def emptyState: TokenState = TokenState()

  def issue(cmd: IssueToken): Effect[TokenState] =
    if currentState.issued then effects.reply(currentState)
    else effects.updateState(TokenState(cmd.user, cmd.purpose, cmd.brand, Some(cmd.expiresAt))).thenReplyState

  /** The token's user, once, while it has not expired; anything else is the original's `invalid_or_expired_token`. */
  def claim(at: Instant): Effect[TokenState] =
    val s = currentState
    if !s.issued || s.usedAt.isDefined || s.expiresAt.exists(!at.isBefore(_)) then effects.error(Token.Invalid, ErrorCode.BadRequest)
    else effects.updateState(s.copy(usedAt = Some(at))).thenReplyState

  def get: ReadOnlyEffect[TokenState] = effects.reply(currentState)

object Token extends KeyValueEntity.Companion[Token, TokenState](
      componentId = ComponentId("token"),
      stateSerializer = TokenCodecs.given_Serializer_TokenState
    ):
  val Invite  = "invite"
  val Reset   = "reset"
  /** A manager's, whose `user` is the realm's user id: a manager has no account (O2, O3). */
  val ManagerInvite = "manager-invite"
  val ManagerReset  = "manager-reset"
  val Invalid = "invalid_or_expired_token"

  /** As the original: 72 hours for an invitation, 30 minutes for a reset. */
  def life(purpose: String): Duration = if purpose == Reset || purpose == ManagerReset then Duration.ofMinutes(30) else Duration.ofHours(72)

  private val random = SecureRandom()

  /** A new token: 32 random bytes, base64url. */
  def mint(): String =
    val b = new Array[Byte](32)
    random.nextBytes(b)
    Base64.getUrlEncoder.withoutPadding.encodeToString(b)

  def idOf(token: String): EntityId =
    EntityId(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8))))

  def create(context: KeyValueEntityContext) = new Token(context)

  val issue = command("issue")(_.issue)
  val claim = command("claim")(_.claim)
  val get   = query("get")(_.get)
