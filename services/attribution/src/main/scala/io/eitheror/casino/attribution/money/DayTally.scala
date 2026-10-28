package io.eitheror.casino.attribution.money

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.AttributionCodecs
import io.eitheror.casino.attribution.AttributionCodecs.given_Serializer_Instant
import io.eitheror.casino.attribution.money.RealMoney.Leg

import java.time.Instant

object DayTallyCodecs:
  given Serializer[Tally.State]  = AttributionCodecs.serializer[Tally.State]("day-tally")
  given Serializer[Tally.Event]  = AttributionCodecs.serializer[Tally.Event]("day-tally-event")
  given Serializer[Tally.LegCmd] = AttributionCodecs.serializer[Tally.LegCmd]("leg")

import DayTallyCodecs.given

/**
 * One player's real stake and win for one currency on one reporting day, id `<player>/<currency>/<day>`
 * (data-model.md *DayTally*). It records what [[Tally]] decides.
 */
final class DayTally(@scala.annotation.unused context: EventSourcedEntityContext) extends EventSourcedEntity[Tally.State, Tally.Event]:
  def emptyState: Tally.State = Tally.State()

  def applyEvent(event: Tally.Event): Tally.State = Tally.apply(currentState, event)

  private val kinds = Set(Leg.Stake, Leg.Win, Leg.Rollback, Leg.Loss)

  def leg(cmd: Tally.LegCmd): Effect[Tally.State] =
    if !kinds.contains(cmd.kind) then effects.error(s"no leg of kind ${cmd.kind}", ErrorCode.BadRequest)
    else
      val events = Tally.leg(currentState, cmd)
      if events.isEmpty then effects.reply(currentState) else effects.persistAll(events).thenReplyState

  def close(at: Instant): Effect[Tally.State] =
    val events = Tally.close(currentState, at)
    if events.isEmpty then effects.reply(currentState) else effects.persistAll(events).thenReplyState

  def get: ReadOnlyEffect[Tally.State] = effects.reply(currentState)

object DayTally extends EventSourcedEntity.Companion[DayTally, Tally.State, Tally.Event](
      componentId = ComponentId("day-tally"),
      stateSerializer = DayTallyCodecs.given_Serializer_State,
      eventSerializer = DayTallyCodecs.given_Serializer_Event
    ):
  override def snapshotEvery: Option[Int] = Some(100)

  def id(player: String, currency: String, day: String): String = s"$player/$currency/$day"

  def create(context: EventSourcedEntityContext) = new DayTally(context)

  val leg   = command("leg")(_.leg)
  val close = command("close")(_.close)
  val get   = query("get")(_.get)
