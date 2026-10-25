package io.eitheror.casino.contract.audit

import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.{AuditRecord, PlatformAudit, PlatformAuditFact}

/**
 * A consumer that turns a component's changes into `audit_record`s on `platform.audit` (spec 018
 * FR-001), as every service's audit is made (constitution IV): a fact is produced from a change, never
 * written to the topic from a handler. The service's mapping says which change is a record and what it
 * holds, the service's name among it; one change makes at most one record, keyed by the record's key and
 * with `ce-id` its record id, so a redelivered change is the same message.
 */
abstract class AuditConsumer[E] extends Consumer[E, PlatformAuditFact]:
  /** The record a change makes, or none. `messageContext` names the change's subject and sequence. */
  protected def audit(change: E): Option[AuditRecord]

  def onMessage(change: E): Effect =
    audit(change).fold(effects.ignore()) { r =>
      effects.produceAll(Seq(effects.message(r: PlatformAuditFact).withKey(r.key).withMetadata(Metadata.empty.set("ce-id", r.recordId))))
    }

/** The companion of an [[AuditConsumer]]: what it reads, and `platform.audit` under its contract as what it produces. */
abstract class AuditCompanion[C <: Consumer[E, PlatformAuditFact], E](id: String, source: ChangeSource[E])
    extends Consumer.Companion[C, E, PlatformAuditFact](ComponentId(id), source):
  override val outputSerializer: Option[Serializer[PlatformAuditFact]] = Some(PlatformAudit.serializer)
  override val produceTo: Option[String]                              = Some(PlatformAudit.name)
  override val produces: Option[Publication] = Some(Publication(PlatformAudit.name, Some(PlatformAudit.contract), None))
