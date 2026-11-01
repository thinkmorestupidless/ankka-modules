package io.eitheror.casino.programme.activity

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.time.Instant
import java.util.UUID

/**
 * One audited action (FR-005): who did what to which affiliate at which brand, when, and the values
 * before and after. Never a personal value: an identity change is audited as changed, not shown.
 */
final case class Audit(
    affiliate: String,
    brand: Option[String],
    action: String,
    actor: String,
    at: Instant,
    before: Option[String] = None,
    after: Option[String] = None,
    details: Map[String, String] = Map.empty
)

final case class AuditState(entry: Option[Audit] = None)

object AuditCodecs:
  given Serializer[AuditState] = ProgrammeCodecs.serializer[AuditState]("audit-entry")
  given Serializer[Audit]      = ProgrammeCodecs.serializer[Audit]("audit")

import AuditCodecs.given

final class AuditEntry(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[AuditState]:
  def emptyState: AuditState = AuditState()

  /** Written once; an entry is never changed. */
  def write(entry: Audit): Effect[AuditState] =
    if currentState.entry.isDefined then effects.reply(currentState) else effects.updateState(AuditState(Some(entry))).thenReplyState

  def get: ReadOnlyEffect[AuditState] = effects.reply(currentState)

object AuditEntry extends KeyValueEntity.Companion[AuditEntry, AuditState](
      componentId = ComponentId("audit-entry"),
      stateSerializer = AuditCodecs.given_Serializer_AuditState
    ):
  def newId(): String = UUID.randomUUID().toString

  def create(context: KeyValueEntityContext) = new AuditEntry(context)

  val write = command("write")(_.write)
  val get   = query("get")(_.get)
