package io.eitheror.casino.publicapi.audit

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.core.effect.{ConsumerEffect, ConsumerEffects}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.audit.AuditCompanion
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord, PlatformAuditFact}
import io.eitheror.casino.publicapi.keys.{AccessKey, IdentifyingFields, KeyEvent, KeyLog, Served}

/**
 * contracts/facts.md's *Published* records on `platform.audit` (research R6). Each record's id is derived from the
 * event it reports (a key change from the key and the event's sequence number, a read from its request id), so a
 * redelivered event is the same record (spec 018 stores a record id once) and two events are never one. No record carries
 * an identifying value, the key or its digest: a key change names the fields it changed, a read the names of the
 * fields it served.
 */
object Audit:
  val Service = "public-api"

  private def record(brand: String, id: String, action: String, actorKind: String, actorId: String, subjectKind: String, subjectId: String,
      outcome: String, changes: Vector[AuditChange], recordClass: String, at: java.time.Instant): AuditRecord =
    AuditRecord(id, brand, Service, action, actorKind, actorId, None, subjectKind, subjectId, outcome, None, changes, id, id, recordClass, at)

  def key(brand: String, keyId: String, sequenceNumber: Long, e: KeyEvent): AuditRecord =
    def r(kind: String, by: String, at: java.time.Instant, changes: Vector[AuditChange]) =
      record(brand, s"access-key/$keyId/$sequenceNumber", s"access_key.$kind", "operator", by, "access_key", keyId, "done", changes,
        "security", at)
    e match
      case KeyEvent.KeyCreated(_, name, _, expires, fields, rate, by, at) =>
        r("created", by, at, Vector(AuditChange("name", None, Some(name)), AuditChange("identifyingFields", None, Option(names(fields)).filter(_.nonEmpty)),
          AuditChange("requestsPerMinute", None, Some(rate.toString))) ++ expires.map(x => AuditChange("expiresAt", None, Some(x.toString))))
      case KeyEvent.KeyChanged(name, expires, clear, fields, rate, by, at, was) =>
        val after = Vector(name.map("name" -> _), expires.map(x => "expiresAt" -> x.toString), Option.when(clear)("expiresAt" -> ""),
          fields.map(f => "identifyingFields" -> names(f)), rate.map(x => "requestsPerMinute" -> x.toString)).flatten
        r("changed", by, at, after.map((f, v) => AuditChange(f, was.get(f).filter(_.nonEmpty), Option(v).filter(_.nonEmpty))))
      case KeyEvent.KeyDisabled(by, at) => r("disabled", by, at, Vector(AuditChange("enabled", Some("true"), Some("false"))))
      case KeyEvent.KeyEnabled(by, at)  => r("enabled", by, at, Vector(AuditChange("enabled", Some("false"), Some("true"))))
      case KeyEvent.KeyDeleted(by, at)  => r("deleted", by, at, Vector(AuditChange("deleted", Some("false"), Some("true"))))

  private def names(fields: Set[String]): String = IdentifyingFields.Names.filter(fields).mkString(",")

  /** A request's record, naming the players whose identifying fields it served, and one record per such player (FR-036–FR-038). */
  def read(brand: String, keyId: String, s: Served): Vector[AuditRecord] =
    val request = record(brand, s"public-api/${s.requestId}", "public_api.read", "access_key", keyId, "access_key", keyId, s.outcome,
      Vector(AuditChange("operation", None, Some(s.operation)), AuditChange("status", None, Some(s.status.toString)),
        AuditChange("duration_ms", None, Some(s.durationMs.toString))) ++
        s.players.filter(_.fields.nonEmpty).map(p => AuditChange(s"player:${p.playerId}", None, Some(p.fields.mkString(",")))),
      "system", s.at)
    val disclosed = s.players.filter(_.fields.nonEmpty).map { p =>
      record(brand, s"public-api/${s.requestId}/${p.playerId}", "public_api.identifying_fields_served", "access_key", keyId, "player",
        p.playerId, s.outcome, Vector(AuditChange("fields", None, Some(p.fields.mkString(","))), AuditChange("operation", None, Some(s.operation))),
        "system", s.at)
    }
    request +: disclosed

  def produce(effects: ConsumerEffects[PlatformAuditFact], records: Vector[AuditRecord]): ConsumerEffect[PlatformAuditFact] =
    if records.isEmpty then effects.ignore()
    else effects.produceAll(records.map(r => effects.message(r: PlatformAuditFact).withKey(r.key).withMetadata(Metadata.empty.set("ce-id", r.recordId))))

final class KeyAuditConsumer(brand: String) extends Consumer[KeyEvent, PlatformAuditFact]:
  def onMessage(e: KeyEvent): Effect = Audit.produce(effects, Vector(Audit.key(brand, messageContext.subject, messageContext.sequenceNumber, e)))

final class KeyAudit(brand: String) extends AuditCompanion[KeyAuditConsumer, KeyEvent]("access-key-audit", ChangeSource.eventsOf(AccessKey)):
  def create(ctx: ConsumerContext) = new KeyAuditConsumer(brand)

final class ReadAuditConsumer(brand: String) extends Consumer[Served, PlatformAuditFact]:
  def onMessage(s: Served): Effect = Audit.produce(effects, Audit.read(brand, messageContext.subject, s))

final class ReadAudit(brand: String) extends AuditCompanion[ReadAuditConsumer, Served]("public-api-read-audit", ChangeSource.eventsOf(KeyLog)):
  def create(ctx: ConsumerContext) = new ReadAuditConsumer(brand)
