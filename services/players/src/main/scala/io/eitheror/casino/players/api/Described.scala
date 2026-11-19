package io.eitheror.casino.players.api

import com.thinkmorestupidless.ankka.http.openapi.Description

/**
 * What the player's routes take and answer, for their descriptions (spec 031,
 * contracts/descriptions.md). The routes build their JSON as trees (`ProfileRoutes`, `SessionRoutes`,
 * `GameSessionRoutes`, …), so these types exist only to give the document their shapes, field for
 * field. A profile holds more fields than these (each `Profile.all` field the player has); a shape
 * admits fields it does not name.
 */
private[api] object Described:

  /** `Answers.refusal`: the code, the message, and the fields the route names. */
  final case class Refusal(error: String, message: Option[String])

  // ── registration, sign-in and the links ───────────────────────────────────────────────────────
  final case class Register(email: String, password: String, currency: String, displayName: Option[String],
      firstName: Option[String], lastName: Option[String], country: Option[String], language: Option[String],
      dateOfBirth: Option[String], emailMarketingConsent: Option[Boolean], smsMarketingConsent: Option[Boolean],
      btag: Option[String], clickId: Option[String], bonusCode: Option[String], deviceFingerprint: Option[String])
  final case class Registered(playerId: String, status: String)
  final case class Login(email: String, password: String)
  final case class LoggedIn(token: String, refreshToken: String, brandId: String, playerId: String, pendingBonusCode: String,
      currency: Option[String], country: Option[String])
  final case class Refresh(refreshToken: String)
  final case class Refreshed(accessToken: String, refreshToken: String, expiresIn: Long)
  final case class Token(token: String)
  final case class Email(email: String)
  final case class Status(status: String)
  final case class Result(result: String)
  final case class AddressVerified(status: String, address: String)
  final case class PasswordChange(currentPassword: String)
  final case class PasswordChangeVerify(token: String, newPassword: String)

  // ── the player's own record ───────────────────────────────────────────────────────────────────
  final case class Profile(playerId: String, status: String, email: Option[String], emailVerified: Boolean, currency: String,
      registeredAt: Option[String], vipTier: Option[String], profileComplete: Boolean, missingProfileFields: Vector[String],
      erasureHoldUntil: Option[String])
  final case class ProfileEdit(firstName: Option[String], lastName: Option[String], phone: Option[String],
      addressLine1: Option[String], city: Option[String], stateProv: Option[String], postalCode: Option[String],
      dateOfBirth: Option[String], country: Option[String], mobile: Option[String], province: Option[String],
      bankAccount: Option[String], institutionCode: Option[String], transitNumber: Option[String])
  final case class Updated(updated: Boolean)
  final case class Close(password: String)
  final case class Consents(emailMarketingConsent: Boolean, smsMarketingConsent: Boolean, emailConsentAt: Option[String],
      smsConsentAt: Option[String], consentSource: Option[String])
  final case class ConsentChange(emailMarketingConsent: Option[Boolean], smsMarketingConsent: Option[Boolean])
  final case class CookieConsent(deviceId: Option[String], analyticsConsent: Option[Boolean], marketingConsent: Option[Boolean],
      version: Option[String], timestamp: Option[String])
  final case class GameIds(gameIds: Vector[String])
  final case class Event(eventId: String, category: String, eventType: String, timestamp: String, actor: String,
      amountMinorUnits: Option[Long], currency: Option[String], description: String, expandable: Boolean)
  final case class Activity(events: Vector[Event], from: Option[String], until: Option[String], generatedAt: String, page: Int)
  final case class Message(notificationId: String, subject: String, body: String, state: String, category: String,
      createdAt: String, readAt: Option[String], originName: String)
  final case class Messages(items: Vector[Message], total: Int, unreadCount: Int, page: Int, pageSize: Int)
  final case class Ok(ok: Boolean)
  final case class Address(address: String, verified: Boolean, primary: Boolean, addedAt: String)
  final case class Addresses(emails: Vector[Address])
  final case class AddAddress(address: String)
  final case class Promoted(status: String, newPrimary: String)
  final case class EmailChange(newEmail: String, currentPassword: String)
  final case class Deposit(amount: Long, currency: String, method: Option[String], requestId: Option[String])
  /** A terminal is as ingest gave it; its fields are ingest's. */
  final case class Terminal()
  final case class Cashier(redirectUrl: String, paymentId: String, cashierTicket: String, terminals: Vector[Terminal], requestId: String)
  final case class Erasure(correlationId: String, holdUntil: Option[String])
  final case class ExportStarted(exportId: String, statusUrl: String)

  // ── the sessions, and the lookup ──────────────────────────────────────────────────────────────
  final case class Session(sessionId: String, gameId: String, status: String, launchedAt: String, endedAt: Option[String],
      durationMinutes: Option[Long], reason: Option[String], lastBetAt: Option[String])
  final case class Sessions(sessions: Vector[Session])
  final case class Ended(status: String, sessionId: String, durationMinutes: Long)
  final case class Country(country: Option[String], dialCode: Option[String])

  /** An export as it was assembled: an object of each service's part. */
  val ExportSchema = """{"type":"object"}"""

  /** The activity: the CSV text, or the events as JSON with `format=json` (`Activity`), one route's bytes. */
  val ActivitySchema = """{"type":"string"}"""

  val refusals: Description = Description.none.refusalShape[Refusal]

  val mine    = "player_id_mismatch"
  val noToken = "missing_token"
