package io.eitheror.casino.attribution

import java.time.Instant

/**
 * A player's binding to an affiliate (data-model.md *Shared types*): what the tag or the code named, the
 * click id the fact carries, the pseudonym the network knows the player by, when it was made and by what.
 * Set once in a player's life (FR-007).
 */
final case class Binding(
    network: String,
    affiliate: String,
    link: String,
    campaign: Option[String],
    subIds: Vector[String],
    clickId: Option[String],
    pseudonym: String,
    boundAt: Instant,
    touch: String
)

object Binding:
  val Tag              = "tag"
  val RegistrationCode = "registration_code"
  val LaterCode        = "later_code"

/** What a tag named, once verified: the binding's fields without the player's. */
final case class Named(network: String, affiliate: String, link: String, campaign: Option[String], subIds: Vector[String])

/** A tag's verdict (research R6): valid with what it names, or refused with the reason. */
sealed trait TagOutcome

object TagOutcome:
  /** `kid` is `legacy` and `iat` absent for a legacy tag resolved through the programme's document. */
  final case class Valid(named: Named, kid: String, iat: Option[Instant]) extends TagOutcome
  final case class Refused(reason: String) extends TagOutcome

/** Every reason a tag is refused, as the record and the click's answer name them. */
object Reasons:
  val Malformed        = "malformed"
  val Legacy           = "legacy"
  val LegacyUnknown    = "legacy_unknown"
  val UnknownNetwork   = "unknown_network"
  val WithdrawnKey     = "withdrawn_key"
  val KeyUnreachable   = "key_unreachable"
  val BadSignature     = "bad_signature"
  val RevokedLink      = "revoked_link"
  val RevokedAffiliate = "revoked_affiliate"
  val Stale            = "stale"
  val NetworkNotReady  = "network_not_ready"

  val all: Vector[String] = Vector(Malformed, Legacy, LegacyUnknown, UnknownNetwork, WithdrawnKey, KeyUnreachable,
    BadSignature, RevokedLink, RevokedAffiliate, Stale, NetworkNotReady)

/**
 * A later touch (FR-009): a tag, code or link presented for a player already bound, or a code that bound
 * nothing. `outcome` says what became of it: `later`, `unknown`, `unresolved`, or a refusal reason.
 */
final case class Touch(kind: String, value: String, at: Instant, outcome: String)

object Touch:
  val Tag  = "tag"
  val Code = "code"
  val Link = "link"

/**
 * A movement of a player's money the protocol reports (data-model.md): the kind of fact it becomes, its
 * amount in minor units, its reference and the input's dedupe id.
 */
final case class MoneyEvent(kind: String, currency: String, amount: Long, reference: String, at: Instant, dedupeId: String)

object MoneyEvent:
  val Deposit             = "deposit"
  val Chargeback          = "chargeback"
  val WithdrawalRequested = "withdrawal-requested"
  val WithdrawalCompleted = "withdrawal-completed"
  val WithdrawalFailed    = "withdrawal-failed"
  val WithdrawalReversed  = "withdrawal-reversed"
  val BonusCost           = "bonus-cost"

  val kinds: Vector[String] =
    Vector(Deposit, Chargeback, WithdrawalRequested, WithdrawalCompleted, WithdrawalFailed, WithdrawalReversed, BonusCost)
