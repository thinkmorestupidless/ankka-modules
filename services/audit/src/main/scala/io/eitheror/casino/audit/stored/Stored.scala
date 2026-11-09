package io.eitheror.casino.audit.stored

import io.eitheror.casino.audit.ids.{Ids, RegistryKeyBytes}
import io.eitheror.casino.contract.facts.{AuditRecord, ModuleChange}

import java.time.Instant

/** Where a record came from (contracts/ingest.md, "Sources"); its name prefixes the record's key. */
enum Source:
  case Brand(slug: String)
  case Payments
  case Modules(slug: String)
  case Audit

  def name: String = this match
    case Brand(slug)   => s"brand:$slug"
    case Payments      => "payments"
    case Modules(slug) => s"modules:$slug"
    case Audit         => "audit"

  /** The project whose ids the record's own id is unique within (research R5). */
  def project: String = this match
    case Brand(slug)   => slug
    case Payments      => "payments"
    case Modules(slug) => slug
    case Audit         => "reasoner"

object Source:
  def parse(name: String): Option[Source] = name match
    case "payments"                      => Some(Payments)
    case "audit"                         => Some(Audit)
    case n if n.startsWith("brand:")     => Some(Brand(n.drop(6)))
    case n if n.startsWith("modules:")   => Some(Modules(n.drop(8)))
    case _                               => None

object Visibility:
  val Brand        = "brand"
  val Installation = "installation"

/** A change as stored: a personal field's names the field and carries no value (FR-003). */
final case class StoredChange(field: String, before: Option[String], after: Option[String])

/**
 * A record as the shared store holds it (data-model.md, "The stored record"): every person by a
 * pseudonym, every id built from a person's by a digest, visibility derived from the source. Nothing
 * in it is a real id of a person (SC-002).
 */
final case class Stored(
    key: String,
    source: String,
    brand: String,
    visibility: String,
    service: String,
    action: String,
    outcome: String,
    reasonCode: Option[String],
    category: String,
    occurredAt: Instant,
    actorKind: String,
    actor: String,
    subjectKind: String,
    subject: String,
    approver: Option[String],
    changes: Vector[StoredChange],
    amount: Option[Long],
    currency: Option[String],
    correlation: String,
    causation: String,
    recordDigest: String,
    arrivedAt: Instant
)

object Stored:
  /** The approver's kind in the registry: an approver is an operator. */
  val ApproverKind = "operator"

  /** The people a record names, as (kind, real id): the actor and subject of a person's kind, and any approver. */
  def people(record: AuditRecord): Vector[(String, String)] =
    Vector(
      Option.when(Ids.isPerson(record.actorKind) && record.actorId.nonEmpty)(record.actorKind -> record.actorId),
      Option.when(Ids.isPerson(record.subjectKind) && record.subjectId.nonEmpty)(record.subjectKind -> record.subjectId),
      record.approverId.filter(_.nonEmpty).map(ApproverKind -> _)
    ).flatten.distinct

  /** Whether an erasure request's state, which a brand's topic carries for the installation (research R6). */
  def isErasureState(record: AuditRecord): Boolean =
    record.service == "players" && record.action.startsWith("erasure.")

  /**
   * The record as stored. `pseudonym(kind, realId)` answers the registry's pseudonym for each of
   * [[people]], issued before this is called.
   */
  def from(record: AuditRecord, source: Source, pseudonym: (String, String) => String, key: RegistryKeyBytes,
      arrivedAt: Instant): Stored =
    val realIds = people(record).map(_._2)
    val brand   = record.brand
    def digest(id: String) = if id.isEmpty then "" else Ids.digest(key, brand, id)
    // An id that is not a person's is kept as written, unless it carries the real id of a person the record names.
    // A person's id is a UUID (players' ids are minted, staff are realm subjects), so no amount or currency
    // can carry one by chance.
    def other(id: String) = if Ids.contains(id, realIds) then digest(id) else id
    def party(kind: String, id: String) = if Ids.isPerson(kind) && id.nonEmpty then pseudonym(kind, id) else other(id)
    val visibility = source match
      case Source.Brand(_) if isErasureState(record) => Visibility.Installation
      case Source.Brand(_) | Source.Payments         => Visibility.Brand
      case Source.Modules(_) | Source.Audit          => Visibility.Installation
    val changes = record.changes.map(c => StoredChange(c.field, c.before.map(other), c.after.map(other)))
    val recordDigest = Ids.digest(key, source.project, record.recordId)
    Stored(
      key = s"${source.name}/$recordDigest",
      source = source.name,
      brand = brand,
      visibility = visibility,
      service = record.service,
      action = record.action,
      outcome = record.outcome,
      reasonCode = record.reasonCode,
      category = record.recordClass,
      occurredAt = record.occurredAt,
      actorKind = record.actorKind,
      actor = party(record.actorKind, record.actorId),
      subjectKind = record.subjectKind,
      subject = party(record.subjectKind, record.subjectId),
      approver = record.approverId.filter(_.nonEmpty).map(pseudonym(ApproverKind, _)),
      changes = changes,
      amount = changes.find(_.field == "amount").flatMap(_.after).flatMap(_.toLongOption),
      currency = changes.find(_.field == "currency").flatMap(_.after),
      correlation = digest(record.correlationId),
      causation = digest(record.causationId),
      recordDigest = recordDigest,
      arrivedAt = arrivedAt
    )

  /** A module change on a brand as an installation record (contracts/ingest.md, "A module change as a record"). */
  def fromModule(change: ModuleChange, brand: String, key: RegistryKeyBytes, arrivedAt: Instant): Stored =
    val source       = Source.Modules(brand)
    val recordDigest = Ids.digest(key, brand, s"${change.module}/${change.change}/${change.at}")
    Stored(
      key = s"${source.name}/$recordDigest",
      source = source.name,
      brand = brand,
      visibility = Visibility.Installation,
      service = change.module,
      action = s"module.${change.change}",
      outcome = "done",
      reasonCode = None,
      category = "system",
      occurredAt = change.at,
      actorKind = "platform",
      actor = change.actor,
      subjectKind = "brand",
      subject = brand,
      approver = None,
      changes = Vector(
        change.version.map(v => StoredChange("version", None, Some(v))),
        change.channel.map(c => StoredChange("channel", None, Some(c)))
      ).flatten,
      amount = None,
      currency = None,
      correlation = "",
      causation = "",
      recordDigest = recordDigest,
      arrivedAt = arrivedAt
    )

  /**
   * One of the audit's own records (a query, an export, a re-identification, a grant, a sweep): an
   * installation record about `brand`, its people already pseudonyms, its id the caller's.
   */
  def own(id: String, brand: String, action: String, actorKind: String, actor: String, subjectKind: String, subject: String,
      outcome: String, reasonCode: Option[String], changes: Vector[StoredChange], key: RegistryKeyBytes, now: Instant,
      visibility: String = Visibility.Installation): Stored =
    val recordDigest = Ids.digest(key, Source.Audit.project, id)
    Stored(
      key = s"${Source.Audit.name}/$recordDigest",
      source = Source.Audit.name,
      brand = brand,
      visibility = visibility,
      service = "audit",
      action = action,
      outcome = outcome,
      reasonCode = reasonCode,
      category = "security",
      occurredAt = now,
      actorKind = actorKind,
      actor = actor,
      subjectKind = subjectKind,
      subject = subject,
      approver = None,
      changes = changes,
      amount = None,
      currency = None,
      correlation = "",
      causation = "",
      recordDigest = recordDigest,
      arrivedAt = now
    )
