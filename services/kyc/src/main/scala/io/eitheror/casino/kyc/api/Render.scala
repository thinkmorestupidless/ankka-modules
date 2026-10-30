package io.eitheror.casino.kyc.api

import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.policy.Policy
import io.eitheror.casino.kyc.queue.QueueRow
import io.eitheror.casino.kyc.record.{Category, Document, KycRecord, KycState}

import java.time.Instant

/** The answers' bodies (contracts/routes.md), written field by field. */
object Render:

  /** The policy's legacy `identity` is shown as the front of an identity document (FR-013). */
  private def shown(c: Category): Category = if c == Category.Identity then Category.IdentityFront else c

  /**
   * A document as the player sees it. A rejection's reason code is shown only while the document is its
   * category's latest (FR-011), which the caller says; an operator's view adds who reviewed it.
   */
  def document(d: Document, latest: Boolean = true, operator: Boolean = false): ObjectNode =
    val status = d.review.fold("pending")(_.outcome.wire)
    Json.obj(
      "documentId"  -> d.id,
      "category"    -> shown(d.category).wire,
      "status"      -> status,
      "uploadedAt"  -> d.uploadedAt.toString,
      "reviewedAt"  -> d.review.map(_.at.toString),
      "reasonCode"  -> Option.when(latest)(d.review.flatMap(_.reasonCode).map(_.wire)).flatten,
      "submittedBy" -> (if d.by.bySupport then "support" else "player"),
      "reviewedBy"  -> Option.when(operator)(d.review.map(_.operatorId)).flatten
    )

  /** The latest document of a category; the front's is a legacy `identity` while there is no front (FR-013). */
  private def latest(s: KycState, c: Category): Option[Document] =
    if c == Category.IdentityFront then s.latest(c).orElse(s.latest(Category.Identity)) else s.latest(c)

  /** The player's read: every category with its latest document, every `other` document, what is missing of the profile. */
  def documents(s: KycState, policy: Policy, now: Instant, operator: Boolean = false): ObjectNode =
    val byPolicy = policy.required.map(shown).toSet
    val ofPlayer = s.required.map(shown).toSet
    val categories = Category.Uploadable.map { c =>
      Json.obj("category" -> c.wire, "requiredByPolicy" -> byPolicy(c), "requiredOfPlayer" -> ofPlayer(c),
        "document" -> Option.when(c != Category.Other)(latest(s, c).map(document(_, operator = operator))).flatten)
    }
    Json.obj(
      "status"               -> s.status.wire,
      "mode"                 -> s.mode.wire,
      "categories"           -> Json.arr(categories*),
      "other"                -> Json.arr(s.documents.filter(_.category == Category.Other).map(document(_, operator = operator))*),
      "missingProfileFields" -> Json.arr(s.missingProfileFields(KycRecord.today(now)).map(_.wire)*)
    )

  /**
   * The policy in the original's field names (contracts/wire.md), every field with `null` for none, and
   * `isDefault`. The KYC currency is this service's own (Clarifications 2026-10-30).
   */
  def policy(p: Policy): ObjectNode =
    Json.obj(
      "depositGate"                -> p.depositGate.wire,
      "playGate"                   -> p.playGate.wire,
      "kycCurrency"                -> p.currency.orNull,
      "depositThresholdMinorUnits" -> p.depositThreshold.map(Long.box).orNull,
      "sofThresholdMinorUnits"     -> p.sofThreshold.map(Long.box).orNull,
      "kycWindowDays"              -> p.windowDays.map(Int.box).orNull,
      "requiredDocTypes"           -> p.required.map(_.wire),
      "autoSuspendOnWindowExpiry"  -> p.suspendOnExpiry,
      "licenceProfile"             -> p.profile.wire,
      "isDefault"                  -> p.isDefault
    )

  /** The operator's read (FR-036): the player's, with each document's reviewer, and the deadline, source of funds, deposits, flags and ready time. */
  /**
   * The player's KYC part of `players`' export (R15, 008 R10): every document record, no object and no
   * address to one, with the status, mode, source of funds, flags and deadline.
   */
  def exported(s: KycState): ObjectNode =
    def record(d: Document) =
      val o = document(d, latest = s.latest(d.category).contains(d))
      Json.set(o, "contentType", d.contentType)
      Json.set(o, "size", d.size)
      o
    Json.obj(
      "documents" -> Json.arr(s.documents.map(record)*),
      "status"    -> s.status.wire,
      "mode"      -> s.mode.wire,
      "sof"       -> s.sof.wire,
      "flags"     -> Json.arr(s.flags.map(f => Json.obj("reason" -> f.reason.wire, "at" -> f.at.toString, "open" -> f.open))*),
      "deadline"  -> s.deadline.map(_.at.toString).orNull
    )

  def operator(s: KycState, policy: Policy, now: Instant): ObjectNode =
    val read = documents(s, policy, now, operator = true)
    Json.set(read, "playerId", s.playerId)
    Json.set(read, "readyAt", s.readyAt.map(_.toString).orNull)
    Json.set(read, "sof", s.sof.wire)
    Json.set(read, "requiredOfPlayer", s.required.map(shown(_).wire))
    Json.set(read, "deadline", s.deadline.map(d => Json.obj("at" -> d.at.toString, "migrated" -> d.migrated, "handled" -> d.handled,
      "suspensionRequested" -> d.suspensionRequested)).orNull)
    Json.set(read, "depositsByCurrency", Json.obj(s.deposits.toSeq.sortBy(_._1).map((c, d) => c -> d.total)*))
    Json.set(read, "flags", flags(s))
    read

  /** Every flag, open or not, with who dismissed it and when; never the dismissal's note. */
  def flags(s: KycState): ArrayNode =
    Json.arr(s.flags.map(f => Json.obj("reason" -> f.reason.wire, "at" -> f.at.toString, "open" -> f.open,
      "dismissedAt" -> f.dismissed.map(_.at.toString), "dismissedBy" -> f.dismissed.map(_.operatorId), "closedAt" -> f.closedAt.map(_.toString)))*)

  /** A queue row (FR-035), with the seconds since the filter's time: since ready, since flagged, since the oldest upload. */
  def queueRow(r: QueueRow, since: QueueRow => Option[Instant], now: Instant): ObjectNode =
    Json.obj(
      "playerId"           -> r.playerId,
      "status"             -> r.status,
      "mode"               -> r.mode,
      "awaiting"           -> r.awaiting,
      "oldestAwaitingAt"   -> r.oldestAwaitingAt.map(_.toString),
      "readyAt"            -> r.readyAt.map(_.toString),
      "flags"              -> r.flags,
      "flaggedAt"          -> r.flaggedAt.map(_.toString),
      "sof"                -> r.sof,
      "depositsByCurrency" -> Json.obj(r.depositsByCurrency.toSeq.sortBy(_._1)*),
      "updatedAt"          -> r.updatedAt.map(_.toString),
      "sinceSeconds"       -> since(r).map(t => java.time.Duration.between(t, now).toSeconds.max(0L))
    )
