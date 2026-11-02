package io.eitheror.casino.programme.activity

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.time.Instant
import java.util.UUID

/** A brand's creative (O50 to O52): an address, never a file the programme stores. Id a UUID. */
final case class CreativeState(
    creativeId: String = "",
    brand: String = "",
    name: String = "",
    creativeType: Option[String] = None,
    fileUrl: Option[String] = None,
    dimensions: Option[String] = None,
    description: Option[String] = None,
    status: String = "",
    createdBy: String = "",
    createdAt: Option[Instant] = None,
    deletedAt: Option[Instant] = None
):
  def exists: Boolean = creativeId.nonEmpty
  def live: Boolean   = status == Creative.Active

final case class WriteCreative(creativeId: String, brand: String, name: String, creativeType: Option[String], fileUrl: Option[String],
    dimensions: Option[String], description: Option[String], by: String, at: Instant)

object CreativeCodecs:
  given Serializer[CreativeState] = ProgrammeCodecs.serializer[CreativeState]("creative")
  given Serializer[WriteCreative] = ProgrammeCodecs.serializer[WriteCreative]("write-creative")
  given Serializer[Instant]       = ProgrammeCodecs.serializer[Instant]("instant")

import CreativeCodecs.given

final class Creative(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[CreativeState]:
  def emptyState: CreativeState = CreativeState()

  def write(c: WriteCreative): Effect[CreativeState] =
    if currentState.exists then effects.reply(currentState)
    else
      effects.updateState(CreativeState(c.creativeId, c.brand, c.name, c.creativeType, c.fileUrl, c.dimensions, c.description,
        Creative.Active, c.by, Some(c.at))).thenReplyState

  /** Marked deleted, as the original: the row stays for the audit. */
  def delete(at: Instant): Effect[CreativeState] =
    if !currentState.exists then effects.error("not_found", ErrorCode.NotFound)
    else if !currentState.live then effects.reply(currentState)
    else effects.updateState(currentState.copy(status = Creative.Deleted, deletedAt = Some(at))).thenReplyState

  def get: ReadOnlyEffect[CreativeState] = effects.reply(currentState)

object Creative extends KeyValueEntity.Companion[Creative, CreativeState](
      componentId = ComponentId("creative"),
      stateSerializer = CreativeCodecs.given_Serializer_CreativeState
    ):
  val Active  = "ACTIVE"
  val Deleted = "DELETED"

  def newId(): String = UUID.randomUUID().toString

  def create(context: KeyValueEntityContext) = new Creative(context)

  val write  = command("write")(_.write)
  val delete = command("delete")(_.delete)
  val get    = query("get")(_.get)

final class CreativesView extends View[CreativeState, CreativeState]:
  def onChange(s: CreativeState): Effect = effects.updateRow(s)

object Creatives extends View.Companion[CreativesView, CreativeState, CreativeState](
      componentId = ComponentId("creatives"),
      source = ChangeSource.stateOf(Creative),
      rowSerializer = CreativeCodecs.given_Serializer_CreativeState
    ):
  val liveOf = query("live-of")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand AND payload::jsonb->>'status' = 'ACTIVE' ORDER BY payload::jsonb->>'createdAt', row_key")

  def create(ctx: ViewComponentContext) = new CreativesView
