package io.eitheror.casino.publicapi.stats

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.publicapi.PublicApiCodecs

import java.time.Instant

/** A slot's snapshot (data-model.md `StatsPointer`): `building` while a run writes it, `ready` once it is whole. */
final case class SlotState(snapshotId: Option[String] = None, computedAt: Option[Instant] = None, state: String = "empty"):
  def ready: Boolean = state == "ready"

/** The two slots and which is current; the one that is not current is the next run's to write. */
final case class PointerState(current: Option[String] = None, a: SlotState = SlotState(), b: SlotState = SlotState()):
  def slot(name: String): SlotState = if name == "a" then a else b
  def next: String                  = if current.contains("a") then "b" else "a"
  def currentSlot: Option[(String, SlotState)] = current.map(c => c -> slot(c)).filter(_._2.ready)

  /** The slot holding a snapshot, when that slot is ready (FR-031: otherwise `410 snapshot_expired`). */
  def ready(snapshotId: String): Option[(String, SlotState)] =
    Vector("a" -> a, "b" -> b).find((_, s) => s.ready && s.snapshotId.contains(snapshotId))

  private def set(name: String, s: SlotState) = if name == "a" then copy(a = s) else copy(b = s)
  def building(name: String, snapshotId: String): PointerState = set(name, SlotState(Some(snapshotId), None, "building"))
  def published(name: String, at: Instant): PointerState        = set(name, slot(name).copy(computedAt = Some(at), state = "ready")).copy(current = Some(name))

final case class Building(slot: String, snapshotId: String)
final case class Publish(slot: String, snapshotId: String, at: Instant)

final class StatsPointer(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[PointerState]:
  def emptyState: PointerState = PointerState()

  def building(b: Building): Effect[PointerState] = effects.updateState(currentState.building(b.slot, b.snapshotId)).thenReply(identity)

  /** Marks the slot ready and current, only when it still holds the run's snapshot (a later run did not start over it). */
  def publish(p: Publish): Effect[PointerState] =
    if !currentState.slot(p.slot).snapshotId.contains(p.snapshotId) then effects.reply(currentState)
    else effects.updateState(currentState.published(p.slot, p.at)).thenReply(identity)

  def get: ReadOnlyEffect[PointerState] = effects.reply(currentState)

object StatsPointer extends KeyValueEntity.Companion[StatsPointer, PointerState](
      componentId = ComponentId("stats-pointer"),
      stateSerializer = PublicApiCodecs.serializer[PointerState]("stats-pointer")
    ):
  val Id = "stats"
  given Serializer[Building]     = PublicApiCodecs.serializer[Building]("stats-building")
  given Serializer[Publish]      = PublicApiCodecs.serializer[Publish]("stats-publish")
  given Serializer[PointerState] = PublicApiCodecs.serializer[PointerState]("stats-pointer-reply")

  def create(context: KeyValueEntityContext) = new StatsPointer(context)

  val building = command("building")(_.building)
  val publish  = command("publish")(_.publish)
  val get      = query("get")(_.get)
