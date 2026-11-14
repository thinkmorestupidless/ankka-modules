package io.eitheror.casino.support.facts

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.audit.{AuditCompanion, AuditConsumer}
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord, PlatformAuditFact}
import io.eitheror.casino.support.SupportRuntime
import io.eitheror.casino.support.config.{AgentConfigState, AgentConfiguration, ConfigNote}

/**
 * A configuration version's record (contracts/audit.md): the operator, the fields changed by name, and the values of
 * those whose values may be recorded, never the prompt or a message.
 */
object ConfigRecords:
  def of(brand: String, slug: String, note: ConfigNote): AuditRecord =
    val id = s"support-config/$slug/${note.version}"
    AuditRecord(recordId = id, brand = brand, service = Audit.Service, action = "support.config.changed",
      actorKind = "operator", actorId = note.by, approverId = None, subjectKind = "brand", subjectId = slug, outcome = "ok",
      reasonCode = None, changes = note.fields.map(f => AuditChange(f, None, note.values.get(f))), correlationId = id,
      causationId = id, recordClass = if note.fields.contains("personalFields") then "security" else "operator",
      occurredAt = note.at)

/**
 * Every change the state keeps, as engagement's: a consumer of a key value entity may skip a state, so each state
 * carries its last few notes and all are written; the audit stores a record id once (018 FR-010).
 */
final class ConfigAudit extends AuditConsumer[AgentConfigState]:
  private def records(c: AgentConfigState) =
    c.recentChanges.map(ConfigRecords.of(SupportRuntime.settings.brand, messageContext.subject, _))

  protected def audit(c: AgentConfigState): Option[AuditRecord] = records(c).lastOption

  override def onMessage(c: AgentConfigState): Effect =
    val all = records(c)
    if all.isEmpty then effects.ignore()
    else effects.produceAll(all.map(r =>
      effects.message(r: PlatformAuditFact).withKey(r.key).withMetadata(Metadata.empty.set("ce-id", r.recordId))))

object ConfigAudit extends AuditCompanion[ConfigAudit, AgentConfigState]("support-config-audit", ChangeSource.stateOf(AgentConfiguration)):
  def create(ctx: ConsumerContext) = new ConfigAudit
