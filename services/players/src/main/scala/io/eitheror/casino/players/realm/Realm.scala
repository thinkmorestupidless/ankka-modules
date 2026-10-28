package io.eitheror.casino.players.realm

import java.time.Instant

/** Why the realm did not do what was asked (contracts/realm.md), each mapped to the wire's refusal. */
enum RealmRefusal(val code: String):
  case EmailExists        extends RealmRefusal("email_already_exists")
  case PasswordTooWeak    extends RealmRefusal("password_too_weak")
  case RealmNotFound      extends RealmRefusal("brand_not_found")
  case InvalidCredentials extends RealmRefusal("invalid_credentials")
  case EmailNotVerified   extends RealmRefusal("email_not_verified")
  case Disabled           extends RealmRefusal("account_restricted")
  case InvalidToken       extends RealmRefusal("invalid_token")
  case MissingToken       extends RealmRefusal("missing_token")
  case UserNotFound       extends RealmRefusal("player_not_found")
  case NotConfigured(why: String) extends RealmRefusal("realm_unavailable")
  case Failed(why: String)        extends RealmRefusal("realm_unavailable")

/** What a grant or a refresh answers: the tokens, and the two claims `players` reads from the access token. */
final case class RealmTokens(accessToken: String, refreshToken: String, expiresIn: Long, playerId: String, realmSessionId: String)

/** A session the realm holds for `casino-frontend` (the reconciler's read). */
final case class LiveSession(sessionId: String, userId: String, startedAt: Instant, lastAccessAt: Instant)

/**
 * The brand's realm, `casino-<slug>` (contracts/realm.md's first table; research R13): the authority on
 * the password, the address's verified state and the tokens. Spec 001 FR-021's exception lets `players`
 * administer it, with `players-realm-admin`; sign-in and refresh use client `casino-frontend` with no
 * credential. Every call answers, never throws; a silent realm is the caller's bound to race.
 */
trait Realm:
  def createUser(email: String, password: String, playerId: String): Either[RealmRefusal, String]
  def deleteUser(userId: String): Either[RealmRefusal, Unit]
  def setVerified(userId: String): Either[RealmRefusal, Unit]
  def changeAddress(userId: String, email: String): Either[RealmRefusal, Unit]
  def directGrant(email: String, password: String): Either[RealmRefusal, RealmTokens]
  def refresh(refreshToken: String): Either[RealmRefusal, RealmTokens]
  def verifyPassword(email: String, password: String): Either[RealmRefusal, Unit]
  def revokeSession(realmSessionId: String): Either[RealmRefusal, Unit]
  def setEnabled(userId: String, enabled: Boolean): Either[RealmRefusal, Unit]
  def liveSessions(): Either[RealmRefusal, Vector[LiveSession]]
  def sendResetEmail(userId: String): Either[RealmRefusal, Unit]
  def setPassword(userId: String, password: String): Either[RealmRefusal, Unit]
  def sendActions(userId: String, actions: Vector[String]): Either[RealmRefusal, Unit]

  /** The realm user holding a player id (the `eitheror_twin_id` attribute). */
  def userOf(playerId: String): Either[RealmRefusal, String]
