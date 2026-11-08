package io.eitheror.casino.engagement.facts

import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.audit.{AuditCompanion, AuditConsumer}
import com.thinkmorestupidless.ankka.core.Metadata
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord, PlatformAuditFact}
import io.eitheror.casino.engagement.EngagementRuntime
import io.eitheror.casino.engagement.config.{ConfigNote, Configuration, CrmConfig}
import io.eitheror.casino.engagement.send.{Send, SendState}

/**
 * The records on `platform.audit` (contracts/facts.md): a send refused under FR-011, an operator's retry and discard,
 * a configuration change. Each is the note of the update that made it, so a later update writes nothing again, and
 * its id is the note's, so a redelivered change is the same record. No personal value: the player by id, the
 * recipient, data and a discard's reason never; a configuration's keys by name only.
 */
object Audit:
  def send(brand: String, key: String, s: SendState): Option[AuditRecord] =
    for note <- s.freshNote; r <- s.request yield
      val id      = s"send/$key/${note.kind}/${note.seq}"
      val byHand  = note.by.isDefined
      val refused = note.kind == Send.RefusedNote
      AuditRecord(recordId = id, brand = brand, service = "engagement", action = s"engagement.send.${note.kind}",
        actorKind = if byHand then "operator" else "system", actorId = note.by.getOrElse("engagement"), approverId = None,
        subjectKind = "player", subjectId = r.playerId, outcome = if refused then "refused" else s.outcome,
        reasonCode = if refused then Some(s.outcome) else None,
        changes = Vector(AuditChange("send", None, Some(key)), AuditChange("trigger", None, Some(r.trigger)),
          AuditChange("class", None, Some(r.sendClass))) ++ s.reason.filter(_ => refused).map(x => AuditChange("reason", None, Some(x))),
        correlationId = key, causationId = id, recordClass = if byHand then "operator" else "system", occurredAt = note.at)

  /**
   * A record per change the state keeps (FR-029): a consumer of a key value entity may skip a state, so each state
   * carries its last few notes and all are written; the audit stores a record id once (018 FR-010).
   */
  def configs(brand: String, slug: String, c: CrmConfig): Vector[AuditRecord] =
    (if c.recentChanges.isEmpty then c.lastChange.toVector else c.recentChanges).map(record(brand, slug, _))

  private def record(brand: String, slug: String, note: ConfigNote): AuditRecord = {
      val id = s"crm-config/$slug/${note.version}"
      AuditRecord(recordId = id, brand = brand, service = "engagement", action = "engagement.config.changed",
        actorKind = "operator", actorId = if note.by.nonEmpty then note.by else "engagement", approverId = None,
        subjectKind = "brand", subjectId = slug, outcome = "accepted", reasonCode = None,
        changes = note.fields.map(f => AuditChange(f, None, None)), correlationId = id, causationId = id,
        recordClass = "operator", occurredAt = note.at)
  }

final class SendAudit extends AuditConsumer[SendState]:
  protected def audit(s: SendState): Option[AuditRecord] = Audit.send(EngagementRuntime.settings.brand, messageContext.subject, s)

object SendAudit extends AuditCompanion[SendAudit, SendState]("send-audit", ChangeSource.stateOf(Send)):
  def create(ctx: ConsumerContext) = new SendAudit

final class ConfigAudit extends AuditConsumer[CrmConfig]:
  protected def audit(c: CrmConfig): Option[AuditRecord] = configs(c).lastOption

  private def configs(c: CrmConfig) = Audit.configs(EngagementRuntime.settings.brand, messageContext.subject, c)

  override def onMessage(c: CrmConfig): Effect =
    val records = configs(c)
    if records.isEmpty then effects.ignore()
    else effects.produceAll(records.map(r =>
      effects.message(r: PlatformAuditFact).withKey(r.key).withMetadata(Metadata.empty.set("ce-id", r.recordId))))

object ConfigAudit extends AuditCompanion[ConfigAudit, CrmConfig]("crm-config-audit", ChangeSource.stateOf(Configuration)):
  def create(ctx: ConsumerContext) = new ConfigAudit
