package io.eitheror.casino.attribution.clicks

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.AttributionCodecs

import java.time.Instant
import scala.concurrent.duration.*

/**
 * A landing (data-model.md *Click*): what the tag named, when, the device, the country and the salted
 * address digest (research R5), the `utm_*` values, and the refusal when the tag was refused. The digest
 * never reaches a fact.
 */
final case class ClickRecord(
    clickId: String,
    network: Option[String],
    affiliate: Option[String],
    link: Option[String],
    campaign: Option[String] = None,
    subIds: Vector[String] = Vector.empty,
    at: Instant,
    deviceType: String = "unknown",
    country: Option[String] = None,
    addressDigest: Option[String] = None,
    utm: Map[String, String] = Map.empty,
    refused: Option[String] = None
)

final case class ClickState(click: Option[ClickRecord] = None)

object ClickCodecs:
  given Serializer[ClickRecord] = AttributionCodecs.serializer[ClickRecord]("click")
  given Serializer[ClickState]  = AttributionCodecs.serializer[ClickState]("click-state")

import ClickCodecs.given

final class Click(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[ClickState]:
  def emptyState: ClickState = ClickState()

  /** At most one click per id (FR-006): a repeat changes nothing and replies the first. */
  def record(click: ClickRecord): Effect[ClickState] =
    if currentState.click.isDefined then effects.reply(currentState)
    else effects.updateState(ClickState(Some(click))).expireAfter(Click.Life).thenReplyState

  def get: ReadOnlyEffect[ClickState] = effects.reply(currentState)

object Click extends KeyValueEntity.Companion[Click, ClickState](
      componentId = ComponentId("click"),
      stateSerializer = ClickCodecs.given_Serializer_ClickState
    ):
  /** A click is read only to refuse a repeat of its id and by the operator. */
  val Life: FiniteDuration = 60.days

  def create(context: KeyValueEntityContext) = new Click(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)
