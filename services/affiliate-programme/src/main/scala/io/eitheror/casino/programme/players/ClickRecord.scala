package io.eitheror.casino.programme.players

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.Click
import io.eitheror.casino.programme.ProgrammeCodecs

import java.time.Instant

/** A click a brand recorded on one of the programme's links (FR-024), id `<brand>/<factId>`. */
final case class ClickState(
    id: String = "",
    brand: String = "",
    link: String = "",
    affiliate: String = "",
    campaign: Option[String] = None,
    subIds: Vector[String] = Vector.empty,
    clickId: Option[String] = None,
    deviceType: Option[String] = None,
    country: Option[String] = None,
    occurredAt: Option[Instant] = None,
    recordedAt: Option[Instant] = None
)

final case class RecordClick(fact: Click, at: Instant)

object ClickCodecs:
  given Serializer[ClickState]  = ProgrammeCodecs.serializer[ClickState]("click")
  given Serializer[RecordClick] = ProgrammeCodecs.serializer[RecordClick]("record-click")

import ClickCodecs.given

final class ClickRecord(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[ClickState]:
  def emptyState: ClickState = ClickState()

  /** Once: the inbox already claimed the fact, and a repeat changes nothing. */
  def record(c: RecordClick): Effect[ClickState] =
    if currentState.id.nonEmpty then effects.reply(currentState)
    else
      val f = c.fact
      effects.updateState(ClickState(f.factId, f.brand, f.link, f.affiliate, f.campaign, f.subIds, f.clickId, f.deviceType, f.country,
        Some(f.occurredAt), Some(c.at))).thenReplyState

  /** FR-038: the click's details go once its player is closed; the link it counts towards stays. */
  def forget: Effect[ClickState] =
    if currentState.clickId.isEmpty && currentState.country.isEmpty && currentState.deviceType.isEmpty then effects.reply(currentState)
    else effects.updateState(currentState.copy(clickId = None, country = None, deviceType = None)).thenReplyState

  def get: ReadOnlyEffect[ClickState] = effects.reply(currentState)

object ClickRecord extends KeyValueEntity.Companion[ClickRecord, ClickState](
      componentId = ComponentId("click-record"),
      stateSerializer = ClickCodecs.given_Serializer_ClickState
    ):
  def entityId(brand: String, factId: String): EntityId = EntityId(s"$brand/$factId")

  def create(context: KeyValueEntityContext) = new ClickRecord(context)

  val record = command("record")(_.record)
  val forget = command("forget")(_.forget)
  val get    = query("get")(_.get)
