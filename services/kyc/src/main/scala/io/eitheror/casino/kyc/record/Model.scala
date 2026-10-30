package io.eitheror.casino.kyc.record

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.kyc.KycCodecs
import io.eitheror.casino.kyc.policy.Policy

import java.time.{Duration, Instant, LocalDate}

/**
 * A document's category (data-model.md). `Identity` is the legacy category of documents uploaded before
 * identity was split into front and back: it is read from the migration only, and FR-013 lets an approved
 * one stand for the front while no front exists.
 */
enum Category(val wire: String):
  case IdentityFront      extends Category("identity_front")
  case IdentityBack       extends Category("identity_back")
  case ProofOfAddress     extends Category("proof_of_address")
  case PaymentMethod      extends Category("payment_method")
  case BankAccountDetails extends Category("bank_account_details")
  case Other              extends Category("other")
  case Identity           extends Category("identity")

object Category:
  def of(wire: String): Option[Category] = values.find(_.wire == wire)

  /** The categories a player or operator may upload to; the legacy `identity` comes from the migration only. */
  val Uploadable: Vector[Category] = values.toVector.filter(_ != Identity)

  given JsonValueCodec[Category] = KycCodecs.wire(values, _.wire)

/** The account's verification status (FR-012). */
enum Status(val wire: String):
  case Unverified extends Status("unverified")
  case Pending    extends Status("pending")
  case Verified   extends Status("verified")
  case Rejected   extends Status("rejected")

object Status:
  def of(wire: String): Option[Status] = values.find(_.wire == wire)
  given JsonValueCodec[Status]          = KycCodecs.wire(values, _.wire)

/** Who decides that the account is verified (FR-015). */
enum Mode(val wire: String):
  case Automatic extends Mode("automatic")
  case Manual    extends Mode("manual")

object Mode:
  def of(wire: String): Option[Mode] = values.find(_.wire == wire)
  given JsonValueCodec[Mode]          = KycCodecs.wire(values, _.wire)

/** Why a player is flagged for review (FR-024). */
enum FlagReason(val wire: String):
  case Threshold     extends FlagReason("threshold")
  case SourceOfFunds extends FlagReason("source_of_funds")
  case Window        extends FlagReason("window")
  case LargeDeposit  extends FlagReason("large_deposit")

object FlagReason:
  def of(wire: String): Option[FlagReason] = values.find(_.wire == wire)
  given JsonValueCodec[FlagReason]          = KycCodecs.wire(values, _.wire)

/** The source of funds review's status. */
enum Sof(val wire: String):
  case Unverified extends Sof("unverified")
  case Cleared    extends Sof("cleared")

object Sof:
  given JsonValueCodec[Sof] = KycCodecs.wire(values, _.wire)

/** A review's outcome; a document with none is awaiting review. */
enum Outcome(val wire: String):
  case Approved extends Outcome("approved")
  case Rejected extends Outcome("rejected")

object Outcome:
  def of(wire: String): Option[Outcome] = values.find(_.wire == wire)
  given JsonValueCodec[Outcome]          = KycCodecs.wire(values, _.wire)

/** Why a document was rejected (FR-010), the original's five. */
enum ReasonCode(val wire: String):
  case DocumentUnclear  extends ReasonCode("document_unclear")
  case AddressMismatch  extends ReasonCode("address_mismatch")
  case DocumentExpired  extends ReasonCode("document_expired")
  case InvalidDocument  extends ReasonCode("invalid_document")
  case Other            extends ReasonCode("other")

object ReasonCode:
  def of(wire: String): Option[ReasonCode] = values.find(_.wire == wire)
  given JsonValueCodec[ReasonCode]          = KycCodecs.wire(values, _.wire)

/** Who did something (FR-014): `system`, `player` or `operator` with the operator's id. */
final case class Actor(kind: String, id: Option[String] = None)

object Actor:
  val System                      = Actor("system")
  val Player                      = Actor("player")
  def operator(id: String): Actor = Actor("operator", Some(id))

/**
 * Who uploaded a document: the player, or support with the operator's id and the reason given, which
 * is personal (R7). Shown as submitted by support.
 */
final case class Uploader(operatorId: Option[String] = None, reason: Option[Personal[String]] = None):
  def bySupport: Boolean = operatorId.isDefined

object Uploader:
  val Player: Uploader                                         = Uploader()
  def support(operatorId: String, reason: String): Uploader = Uploader(Some(operatorId), Some(Personal(reason)))

final case class Review(outcome: Outcome, reasonCode: Option[ReasonCode], operatorId: String, at: Instant)

final case class Document(
    id: String,
    category: Category,
    objectName: String,
    contentType: String,
    size: Long,
    uploadedAt: Instant,
    by: Uploader = Uploader.Player,
    review: Option[Review] = None
):
  def awaiting: Boolean = review.isEmpty

final case class Offer(documentId: String, category: Category, contentType: String, objectName: String, by: Uploader, offeredAt: Instant)

object Offer:
  /** An offer is dropped an hour after it is made (R7); its signed link lasts as long. */
  val Lifetime: Duration = Duration.ofHours(1)

/** What the record keeps of the profile (R11): no value, only which fields are present and the adult date. */
final case class ProfileFacts(
    present: Set[ProfileField] = Set.empty,
    adultFrom: Option[Personal[LocalDate]] = None,
    changedAt: Option[Instant] = None
)

/** `suspend` is what the lapse decided: the policy suspends and the deadline is not a migrated one already past (FR-025). */
final case class Deadline(at: Instant, migrated: Boolean = false, handled: Boolean = false, suspend: Boolean = false,
    suspensionRequested: Boolean = false)

final case class Dismissal(operatorId: String, note: Personal[String], at: Instant)

/** A flag is open until dismissed, or closed by verification or a cleared source of funds (FR-024). */
final case class Flag(reason: FlagReason, at: Instant, dismissed: Option[Dismissal] = None, closedAt: Option[Instant] = None):
  def open: Boolean = dismissed.isEmpty && closedAt.isEmpty

/** Lifetime deposits in one currency, and the wallet's highest ordinal counted (R6). */
final case class Deposits(total: Long = 0L, lastOrdinal: Int = 0)

final case class KycState(
    playerId: String = "",
    documents: Vector[Document] = Vector.empty,
    offers: Map[String, Offer] = Map.empty,
    status: Status = Status.Unverified,
    mode: Mode = Mode.Automatic,
    required: Vector[Category] = Vector.empty,
    sof: Sof = Sof.Unverified,
    verifiedAt: Option[Instant] = None,
    documentsChangedAt: Option[Instant] = None,
    readyAt: Option[Instant] = None,
    profile: ProfileFacts = ProfileFacts(),
    deadline: Option[Deadline] = None,
    flags: Vector[Flag] = Vector.empty,
    deposits: Map[String, Deposits] = Map.empty,
    closed: Boolean = false,
    erased: Boolean = false
):
  def document(id: String): Option[Document] = documents.find(_.id == id)

  def openFlag(reason: FlagReason): Option[Flag] = flags.find(f => f.reason == reason && f.open)

  /** Lifetime deposits in `currency`; none named is nothing (FR-021). */
  def lifetime(currency: Option[String]): Long = currency.flatMap(deposits.get).fold(0L)(_.total)

  /** Whether anything has been recorded; the migration opens only an empty record. */
  def isEmpty: Boolean = this == KycState(playerId)

  /** The latest document of a single-slot category, by the derivation's rule. */
  def latest(category: Category): Option[Document] =
    documents.filter(_.category == category).foldLeft(Option.empty[Document]) { (acc, d) =>
      if acc.forall(e => !d.uploadedAt.isBefore(e.uploadedAt)) then Some(d) else acc
    }

  def missingProfileFields(today: LocalDate): Vector[ProfileField] = Completeness.missing(profile.present, profile.adultFrom, today)

  /** The derivation's inputs from this record, with the policy's required categories (R4). */
  def rollupInputs(policyRequired: Vector[Category], today: LocalDate): Rollup.Inputs =
    Rollup.Inputs(
      documents.map(d => Rollup.Doc(d.category, d.uploadedAt, d.review.map(_.outcome))),
      policyRequired,
      required,
      missingProfileFields(today).isEmpty,
      status,
      mode,
      verifiedAt,
      documentsChangedAt,
      profile.changedAt,
      readyAt
    )

// ── events ──────────────────────────────────────────────────────────────────

sealed trait KycEvent:
  def at: Instant

object KycEvent:
  final case class UploadOffered(playerId: String, offer: Offer, at: Instant)                                           extends KycEvent
  final case class DocumentRecorded(playerId: String, document: Document, at: Instant)                                  extends KycEvent
  final case class DocumentReviewed(documentId: String, category: Category, review: Review, at: Instant)                extends KycEvent
  final case class ViewIssued(documentId: String, requester: Actor, at: Instant)                                        extends KycEvent
  final case class ModeChanged(mode: Mode, previous: Mode, operatorId: String, at: Instant)                             extends KycEvent
  final case class ReadySet(at: Instant)                                                                                extends KycEvent
  final case class ReadyCleared(at: Instant)                                                                            extends KycEvent
  /** `reason` is a code (`document`, `profile_changed`, `agent`, `required_category`, `override`); `note` an operator's own words. */
  final case class StatusChanged(previous: Status, status: Status, actor: Actor, reason: String, note: Option[Personal[String]], at: Instant)
      extends KycEvent
  final case class RequiredChanged(categories: Vector[Category], previous: Vector[Category], operatorId: String, at: Instant) extends KycEvent
  final case class SofCleared(operatorId: String, at: Instant)                                                          extends KycEvent
  final case class SofReset(operatorId: String, at: Instant)                                                            extends KycEvent
  final case class Flagged(playerId: String, reason: FlagReason, at: Instant)                                           extends KycEvent
  final case class FlagDismissed(reason: FlagReason, operatorId: String, note: Personal[String], at: Instant)          extends KycEvent
  final case class DepositCounted(playerId: String, currency: String, amount: Long, ordinal: Int, at: Instant)          extends KycEvent
  final case class ProfileUpdated(present: Set[ProfileField], adultFrom: Option[Personal[LocalDate]], requiredChanged: Boolean, at: Instant)
      extends KycEvent
  final case class DeadlineSet(playerId: String, deadline: Instant, migrated: Boolean, at: Instant)                     extends KycEvent
  final case class DeadlineHandled(deadline: Instant, suspend: Boolean, at: Instant)                                    extends KycEvent
  final case class SuspensionRequested(deadline: Instant, at: Instant)                                                  extends KycEvent
  final case class Closed(at: Instant)                                                                                  extends KycEvent
  /** Offers an hour old, dropped by the next command after its own check (R7). */
  final case class OffersDropped(documentIds: Vector[String], at: Instant)                                             extends KycEvent
  final case class Opened(record: KycState, at: Instant)                                                                extends KycEvent
  final case class Erased(at: Instant)                                                                                  extends KycEvent

// ── commands ────────────────────────────────────────────────────────────────

final case class RequestUpload(playerId: String, category: String, contentType: String, by: Uploader, documentId: String, at: Instant)
final case class ConfirmUpload(documentId: String, size: Long, contentType: String, policyRequired: Vector[Category], at: Instant)
final case class ReviewDocument(documentId: String, outcome: Outcome, reasonCode: Option[String], operatorId: String,
    policyRequired: Vector[Category], at: Instant)
final case class IssueView(documentId: String, requester: Actor, at: Instant)
final case class SetMode(mode: Mode, operatorId: String, policyRequired: Vector[Category], at: Instant)
final case class SetStatus(status: Status, operatorId: String, policyRequired: Vector[Category], at: Instant)
final case class SetRequired(categories: Vector[String], operatorId: String, policyRequired: Vector[Category], at: Instant)
final case class Override(status: Status, reason: Option[String], operatorId: String, policyRequired: Vector[Category], at: Instant)
final case class ClearSof(operatorId: String, at: Instant)
final case class ResetSof(operatorId: String, at: Instant)
final case class DismissFlag(reason: String, note: String, operatorId: String, at: Instant)
/** The policy is carried by the caller that read it from `PolicyEntity` (R5, R6). */
final case class DepositCheck(playerId: String, policy: Policy, currency: Option[String], amount: Long, at: Instant)
final case class CountDeposit(playerId: String, currency: String, amount: Long, ordinal: Int, policy: Policy, at: Instant)
final case class ProfileChanged(playerId: String, present: Set[ProfileField], adultFrom: Option[Personal[LocalDate]], requiredChanged: Boolean,
    policyRequired: Vector[Category], at: Instant)
final case class SetDeadline(playerId: String, registeredAt: Instant, windowDays: Option[Int], at: Instant)
final case class WindowLapsed(deadline: Instant, suspendOnExpiry: Boolean, at: Instant)
final case class RequestedSuspension(deadline: Instant, at: Instant)
final case class FlagForPolicy(policy: Policy, at: Instant)
final case class Close(at: Instant)
final case class Open(record: KycState, at: Instant)
final case class Erase(at: Instant)

/** A refusal: its code, and the missing fields or allowed values the routes name with it. */
final case class Refusal(code: String, fields: Vector[String] = Vector.empty)

/** The record after a command, a refusal, and the deposit check's answer when it was one. */
final case class KycAnswer(state: KycState, refusal: Option[Refusal] = None, gate: Option[GateAnswer] = None)
