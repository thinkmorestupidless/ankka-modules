package io.eitheror.casino.kyc.facts

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.kyc.policy.{Policy, PolicyChanged}
import io.eitheror.casino.kyc.record.{Actor, KycEvent, Status}
import io.eitheror.casino.kyc.record.KycEvent.*

/**
 * The record's and the policy's events as facts (contracts/facts.md), pure. The CloudEvents id is the
 * event's id, `<component>/<entity>/<sequence>`, so a redelivered event is the same fact.
 */
object Facts:
  final case class Published[F](fact: F, id: String)

  def eventId(component: String, entityId: String, sequence: Long): String = s"$component/$entityId/$sequence"

  /** The reasons a fact may carry; an operator's own words never go on one (FR-030). */
  private val FactReasons = Set("agent", "required_category", "profile_changed", "document")

  private def factReason(reason: String): Option[String] = Option.when(FactReasons(reason))(reason)

  private def actorId(a: Actor): Option[String] = a.id

  // ── casino.players ──────────────────────────────────────────────────────

  def players(playerId: String, sequence: Long, event: KycEvent): Option[Published[f.CasinoPlayersFact]] =
    val fact: Option[f.CasinoPlayersFact] = event match
      case e: DocumentRecorded => Some(f.PlayerKycSubmitted(playerId, e.document.id, e.document.category.wire, e.at))
      case e: DocumentReviewed =>
        Some(f.PlayerKycDocumentReviewed(playerId, e.documentId, e.category.wire, e.review.outcome.wire, e.review.reasonCode.map(_.wire),
          e.review.operatorId, e.at))
      case e: StatusChanged =>
        val (prev, next, reason) = (e.previous.wire, e.status.wire, factReason(e.reason))
        e.status match
          case Status.Verified => Some(f.PlayerKycVerified(playerId, prev, next, e.actor.kind, actorId(e.actor), reason, e.at))
          case Status.Rejected => Some(f.PlayerKycRejected(playerId, prev, next, e.actor.kind, actorId(e.actor), reason, e.at))
          case Status.Pending  => Some(f.PlayerKycPending(playerId, prev, next, e.at, reason))
          case Status.Unverified if e.previous == Status.Verified =>
            Some(f.PlayerKycRejected(playerId, prev, next, e.actor.kind, actorId(e.actor), reason, e.at))
          case Status.Unverified => None
      case e: Flagged    => Some(f.PlayerKycReviewRequired(playerId, e.reason.wire, e.at))
      case e: SofCleared => Some(f.PlayerSofCleared(playerId, e.operatorId, e.at))
      case _             => None
    fact.map(Published(_, eventId("record", playerId, sequence)))

  // ── platform.audit ──────────────────────────────────────────────────────

  private def change(field: String, before: Option[String], after: Option[String]) = f.AuditChange(field, before, after)

  /** A field whose value is personal: named, with no value (FR-032). */
  private def named(field: String) = f.AuditChange(field, None, None)

  private def record(brand: String, id: String, action: String, actor: Actor, subjectKind: String, subjectId: String,
      changes: Vector[f.AuditChange], reasonCode: Option[String], at: java.time.Instant) =
    f.AuditRecord(recordId = id, brand = brand, service = "kyc", action = action, actorKind = actor.kind,
      actorId = actor.id.getOrElse("kyc"), approverId = None, subjectKind = subjectKind, subjectId = subjectId, outcome = "accepted",
      reasonCode = reasonCode, changes = changes, correlationId = id, causationId = id,
      recordClass = if actor.kind == "system" then "system" else "operator", occurredAt = at)

  /**
   * One record per status change, review, view issued, source of funds change, flag dismissal and
   * operator upload (FR-014, FR-037). A note or reason is named, never written.
   */
  def audit(brand: String, playerId: String, sequence: Long, event: KycEvent): Option[Published[f.AuditRecord]] =
    val op = Actor.operator
    val found: Option[(String, Actor, Vector[f.AuditChange], Option[String])] = event match
      case e: StatusChanged =>
        Some(("kyc.status_changed", e.actor,
          Vector(change("status", Some(e.previous.wire), Some(e.status.wire)), change("reason", None, Some(e.reason))) ++ e.note.map(_ => named("note")),
          None))
      case e: DocumentReviewed =>
        Some(("kyc.document_reviewed", op(e.review.operatorId),
          Vector(change("document", None, Some(e.documentId)), change("decision", None, Some(e.review.outcome.wire))), e.review.reasonCode.map(_.wire)))
      case e: ViewIssued       => Some(("kyc.view_issued", e.requester, Vector(change("document", None, Some(e.documentId))), None))
      case e: SofCleared       => Some(("kyc.sof_changed", op(e.operatorId), Vector(change("sof", Some("unverified"), Some("cleared"))), None))
      case e: SofReset         => Some(("kyc.sof_changed", op(e.operatorId), Vector(change("sof", Some("cleared"), Some("unverified"))), None))
      case e: FlagDismissed    => Some(("kyc.flag_dismissed", op(e.operatorId), Vector(change("flag", Some(e.reason.wire), None), named("note")), None))
      case e: DocumentRecorded if e.document.by.bySupport =>
        Some(("kyc.upload_by_operator", op(e.document.by.operatorId.get),
          Vector(change("document", None, Some(e.document.id)), change("category", None, Some(e.document.category.wire)), named("reason")), None))
      case _ => None
    found.map { (action, actor, changes, reasonCode) =>
      val id = eventId("audit/record", playerId, sequence)
      Published(record(brand, id, action, actor, "player", playerId, changes, reasonCode, event.at), id)
    }

  /** Every field of the policy, before and after, that changed (FR-028). */
  def policyAudit(brand: String, sequence: Long, event: PolicyChanged): Published[f.AuditRecord] =
    def fields(p: Policy): Vector[(String, String)] = Vector(
      "depositGate"      -> p.depositGate.wire,
      "playGate"         -> p.playGate.wire,
      "currency"         -> p.currency.getOrElse(""),
      "depositThreshold" -> p.depositThreshold.fold("")(_.toString),
      "sofThreshold"     -> p.sofThreshold.fold("")(_.toString),
      "windowDays"       -> p.windowDays.fold("")(_.toString),
      "required"         -> p.required.map(_.wire).mkString(","),
      "suspendOnExpiry"  -> p.suspendOnExpiry.toString,
      "profile"          -> p.profile.wire
    )
    val changes = fields(event.before).zip(fields(event.after)).collect {
      case ((name, b), (_, a)) if a != b => change(name, Option(b).filter(_.nonEmpty), Option(a).filter(_.nonEmpty))
    }
    val id = eventId("audit/policy", brand, sequence)
    Published(record(brand, id, "kyc.policy_changed", Actor.operator(event.operatorId), "brand", brand, changes, None, event.at), id)
