package io.eitheror.casino.attribution.record

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.{AttributionCodecs, Binding, MoneyEvent, Touch}

import java.time.Instant

final case class CloseRecord(category: String, at: Instant)

object RecordCodecs:
  given Serializer[Record.State]    = AttributionCodecs.serializer[Record.State]("record")
  given Serializer[Record.Event]    = AttributionCodecs.serializer[Record.Event]("record-event")
  given Serializer[Record.Register] = AttributionCodecs.serializer[Record.Register]("register")
  given Serializer[CloseRecord]     = AttributionCodecs.serializer[CloseRecord]("close-record")

import RecordCodecs.given
import AttributionCodecs.given

/**
 * The brand's regulated attribution record of one player (FR-007, FR-009, FR-028), id the player id. It
 * records what [[Record]] decides; nothing a network says reaches a command here (FR-024).
 */
final class AttributionRecord(@scala.annotation.unused context: EventSourcedEntityContext)
    extends EventSourcedEntity[Record.State, Record.Event]:

  def emptyState: Record.State = Record.State()

  def applyEvent(event: Record.Event): Record.State = Record.apply(currentState, event)

  private def record(events: Vector[Record.Event]): Effect[Record.State] =
    if events.isEmpty then effects.reply(currentState) else effects.persistAll(events).thenReplyState

  def registered(cmd: Record.Register): Effect[Record.State] = record(Record.register(currentState, cmd))

  /** A binding after registration; refused `already_bound`, and the caller then records a touch. */
  def bind(binding: Binding): Effect[Record.State] =
    Record.bind(currentState, binding) match
      case Left(reason) => effects.error(reason, ErrorCode.Conflict)
      case Right(evs)   => record(evs)

  def touch(t: Touch): Effect[Record.State] = record(Record.touch(t))

  def money(event: MoneyEvent): Effect[Record.State] =
    if !MoneyEvent.kinds.contains(event.kind) then effects.error(s"no money event of kind ${event.kind}", ErrorCode.BadRequest)
    else record(Record.money(currentState, event))

  def close(cmd: CloseRecord): Effect[Record.State] =
    if cmd.category != Record.Category.Closed && cmd.category != Record.Category.SelfExcluded then
      effects.error(s"no closure category ${cmd.category}", ErrorCode.BadRequest)
    else record(Record.close(currentState, cmd.category, cmd.at))

  def erase(at: Instant): Effect[Record.State] = record(Record.erase(currentState, at))

  def get: ReadOnlyEffect[Record.State] = effects.reply(currentState)

object AttributionRecord extends EventSourcedEntity.Companion[AttributionRecord, Record.State, Record.Event](
      componentId = ComponentId("attribution-record"),
      stateSerializer = RecordCodecs.given_Serializer_State,
      eventSerializer = RecordCodecs.given_Serializer_Event
    ):
  override def snapshotEvery: Option[Int] = Some(100)

  def create(context: EventSourcedEntityContext) = new AttributionRecord(context)

  val registered = command("registered")(_.registered)
  val bind       = command("bind")(_.bind)
  val touch      = command("touch")(_.touch)
  val money      = command("money")(_.money)
  val close      = command("close")(_.close)
  val erase      = command("erase")(_.erase)
  val get        = query("get")(_.get)
