package io.eitheror.casino.audit.store

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.AuditCodecs
import io.eitheror.casino.audit.stored.Stored

/** One record in the shared store (data-model.md `Record`), id its key `<source>/<digest>`. */
final case class RecordState(stored: Option[Stored] = None)

/** `new` the first time, `same` for the same record again (FR-010), `collision` for another under its key. */
final case class Kept(outcome: String):
  def isNew: Boolean = outcome == Record.New

object RecordCodecs:
  given Serializer[RecordState] = AuditCodecs.serializer[RecordState]("record")
  given Serializer[Kept]        = AuditCodecs.serializer[Kept]("kept")

import AuditCodecs.given
import RecordCodecs.given
import com.thinkmorestupidless.ankka.core.Serializers.string

/**
 * Written once and never changed (FR-010, SC-008): `keep` stores a record, answers `same` for it again
 * and refuses another under the same key as a `collision`, which is reported, never overwritten. `drop`
 * deletes it, which removes its row from [[Records]]; only the retention sweep sends it (FR-011).
 */
final class Record(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[RecordState]:
  def emptyState: RecordState = RecordState()

  def keep(stored: Stored): Effect[Kept] =
    currentState.stored match
      case None                                   => effects.updateState(RecordState(Some(stored))).thenReply(_ => Kept(Record.New))
      case Some(held) if Record.same(held, stored) => effects.reply(Kept(Record.Same))
      case Some(_)                                => effects.reply(Kept(Record.Collision))

  /** The sweep's deletion of a record past its brand's retention; `sweepId` names the sweep that sent it. */
  def drop(@scala.annotation.unused sweepId: String): Effect[Kept] =
    if currentState.stored.isEmpty then effects.reply(Kept(Record.Absent))
    else effects.deleteEntity().thenReply(_ => Kept(Record.Dropped))

  def get: ReadOnlyEffect[RecordState] = effects.reply(currentState)

object Record extends KeyValueEntity.Companion[Record, RecordState](
      componentId = ComponentId("record"),
      stateSerializer = RecordCodecs.given_Serializer_RecordState
    ):
  val New       = "new"
  val Same      = "same"
  val Collision = "collision"
  val Dropped   = "dropped"
  val Absent    = "absent"

  /** The same record: everything but when this service received it, which a redelivery changes. */
  def same(a: Stored, b: Stored): Boolean = a.copy(arrivedAt = b.arrivedAt) == b

  def entityId(key: String): EntityId = EntityId(key)

  def create(context: KeyValueEntityContext) = new Record(context)

  val keep = command("keep")(_.keep)
  val drop = command("drop")(_.drop)
  val get  = query("get")(_.get)
