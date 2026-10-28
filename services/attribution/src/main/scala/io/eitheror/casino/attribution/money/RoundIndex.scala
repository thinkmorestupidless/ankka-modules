package io.eitheror.casino.attribution.money

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.AttributionCodecs

import java.time.Instant
import scala.concurrent.duration.*

/**
 * The day a round's first stake fell on (data-model.md *RoundIndex*), id `<player>/<currency>/<round>`:
 * a round's later legs count on that day, so a round across midnight is one day's (research R10).
 */
final case class RoundState(day: Option[String] = None, firstStakeAt: Option[java.time.Instant] = None)

final case class Remember(day: String, at: Instant)

object RoundIndexCodecs:
  given Serializer[RoundState] = AttributionCodecs.serializer[RoundState]("round-index")
  given Serializer[Remember]   = AttributionCodecs.serializer[Remember]("remember")

import RoundIndexCodecs.given

final class RoundIndex(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[RoundState]:
  def emptyState: RoundState = RoundState()

  /** The first day remembered wins; the reply is the round's day. */
  def remember(cmd: Remember): Effect[RoundState] =
    if currentState.day.isDefined then effects.reply(currentState)
    else effects.updateState(RoundState(Some(cmd.day), Some(cmd.at))).expireAfter(RoundIndex.Life).thenReplyState

  def get: ReadOnlyEffect[RoundState] = effects.reply(currentState)

object RoundIndex extends KeyValueEntity.Companion[RoundIndex, RoundState](
      componentId = ComponentId("round-index"),
      stateSerializer = RoundIndexCodecs.given_Serializer_RoundState
    ):
  val Life: FiniteDuration = 7.days

  def id(player: String, currency: String, round: String): String = s"$player/$currency/$round"

  def create(context: KeyValueEntityContext) = new RoundIndex(context)

  val remember = command("remember")(_.remember)
  val get      = query("get")(_.get)
