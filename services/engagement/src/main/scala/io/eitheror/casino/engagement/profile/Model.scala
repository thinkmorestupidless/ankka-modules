package io.eitheror.casino.engagement.profile

import com.thinkmorestupidless.ankka.core.{DataSubject, Personal}

import java.time.{Instant, LocalDate}

/** An RG limit as the last `rg_limit_applied` of its type left it. */
final case class Limit(period: Option[String], value: Option[Long], currency: Option[String])

/** An open RG case, from `rg_case_opened` until its `rg_case_closed`. */
final case class RgCase(caseType: String, closesAt: Option[Instant])

/** The last `player_consent_changed` for a channel (`email`, `sms`, `messages`). */
final case class Consent(granted: Boolean, at: Instant, source: String)

/** A `payment.initiated` remembered so its failure or expiry can say the amount (contracts/facts.md). */
final case class RecentPayment(paymentId: String, amountCents: Long, currency: String, provider: String, terminal: String)

/**
 * What engagement knows of a player, from the brand's facts (data-model.md, research R9). Every personal
 * field is ankka's `Personal` under `player/<id>`, so the store holds it sealed. `seen` is the time of the
 * last fact applied per field group: a fact no newer than it changes nothing, so a repeat is harmless.
 * `status` is `active`, `self_excluded`, `closed` or `suspended`.
 */
final case class ProfileState(
    playerId: String = "",
    registeredAt: Option[Instant] = None,
    email: Option[Personal[String]] = None,
    givenName: Option[Personal[String]] = None,
    familyName: Option[Personal[String]] = None,
    dateOfBirth: Option[Personal[String]] = None,
    country: Option[String] = None,
    currency: Option[String] = None,
    btag: Option[String] = None,
    vipTier: Option[String] = None,
    lastLoginAt: Option[Instant] = None,
    lastDepositAt: Option[Instant] = None,
    depositCount: Long = 0,
    totalDepositCents: Long = 0,
    dailyDepositCount: Int = 0,
    depositDay: Option[LocalDate] = None,
    realCents: Option[Long] = None,
    restrictedCents: Option[Long] = None,
    limits: Map[String, Limit] = Map.empty,
    cases: Map[String, RgCase] = Map.empty,
    status: String = "active",
    kycStatus: Option[String] = None,
    suspectedDuplicate: Boolean = false,
    consents: Map[String, Consent] = Map.empty,
    recentPayments: Vector[RecentPayment] = Vector.empty,
    erased: Boolean = false,
    seen: Map[String, Instant] = Map.empty
):
  def subject: DataSubject = ProfileState.subject(playerId)

object ProfileState:
  def subject(playerId: String): DataSubject = DataSubject(s"player/$playerId")

  def personal(playerId: String, value: String): Personal[String] = Personal(subject(playerId), value)

  /** A personal field's value: `None` when absent, empty or erased. */
  def reveal(field: Option[Personal[String]]): Option[String] =
    field.collect { case Personal.Present(_, v) if v != null && v.nonEmpty => v }
