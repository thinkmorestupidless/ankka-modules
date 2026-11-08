package io.eitheror.casino.provider.edge.admin

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.audit
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord}

import java.time.Instant

/** One field an act set, as the audit names it. */
final case class ActChange(field: String, after: String)

/**
 * One operator act on the house provider (data-model, Audit records): what was done, by whom (the
 * staff token's subject), when, to what, and for which brand (`games` for the installation's own).
 */
final case class Act(action: String, actor: String, at: Long, subjectKind: String, subjectId: String, brand: String,
    changes: Vector[ActChange] = Vector.empty)

enum ActEvent:
  case Acted(act: Act)

/**
 * The operator acts on one subject, in order (a brand entry, the key ring, a callback, a game). The
 * endpoint records an act after the command it audits succeeded; `ProviderAudit` makes each one an
 * `audit_record` on `platform.audit`, since a record is produced from a change and never written to
 * the topic from a handler (constitution IV).
 */
final class ProviderActs(context: EventSourcedEntityContext) extends EventSourcedEntity[Vector[Act], ActEvent]:
  def emptyState: Vector[Act] = Vector.empty
  def applyEvent(event: ActEvent): Vector[Act] = event match
    case ActEvent.Acted(a) => currentState :+ a
  def record(act: Act): Effect[Done] = effects.persist(ActEvent.Acted(act)).thenReply(_ => Done)
  def get: ReadOnlyEffect[Vector[Act]] = effects.reply(currentState)

object ProviderActs
    extends EventSourcedEntity.Companion[ProviderActs, Vector[Act], ActEvent](
      componentId = ComponentId("provider-acts"),
      stateSerializer = Codecs.serializer[Vector[Act]]("provider-acts"),
      eventSerializer = Codecs.serializer[ActEvent]("provider-act-event")
    ):
  given Serializer[Act] = Codecs.serializer[Act]("provider-act")
  def create(context: EventSourcedEntityContext) = new ProviderActs(context)
  val record = command("record")(_.record)
  val get    = query("get")(_.get)

  /** The installation's own subjects (the key ring, the registry) are audited under this brand. */
  val InstallationBrand = "games"

  def id(subjectKind: String, subjectId: String): String = s"$subjectKind:$subjectId"

  // The actions, in the data model's family.
  val BrandEntryWritten  = "brand_entry_written"
  val KeyRotationStarted = "key_rotation_started"
  val KeySwitched        = "key_switched"
  val KeyWithdrawn       = "key_withdrawn"
  val CallbackAbandoned  = "callback_abandoned"
  val GameRemoved        = "game_removed"

/** Every act on the house provider as an `audit_record` (spec 018 FR-001), `service = provider`. */
final class ProviderAudit extends audit.AuditConsumer[ActEvent]:
  protected def audit(event: ActEvent): Option[AuditRecord] = event match
    case ActEvent.Acted(a) =>
      val seq = messageContext.sequenceNumber
      Some(AuditRecord(
        recordId = s"${ProviderAudit.Service}/${a.subjectKind}/${a.subjectId}/$seq", brand = a.brand, service = ProviderAudit.Service,
        action = a.action, actorKind = "operator", actorId = a.actor, approverId = None, subjectKind = a.subjectKind,
        subjectId = a.subjectId, outcome = "accepted", reasonCode = None, changes = a.changes.map(c => AuditChange(c.field, None, Some(c.after))),
        correlationId = a.subjectId, causationId = s"${a.subjectKind}/${a.subjectId}/$seq", recordClass = "operator",
        occurredAt = Instant.ofEpochMilli(a.at)
      ))

object ProviderAudit extends audit.AuditCompanion[ProviderAudit, ActEvent]("provider-audit", ChangeSource.eventsOf(ProviderActs)):
  val Service = "provider"
  def create(ctx: ConsumerContext) = new ProviderAudit
