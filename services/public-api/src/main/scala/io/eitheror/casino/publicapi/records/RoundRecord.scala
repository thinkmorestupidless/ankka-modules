package io.eitheror.casino.publicapi.records

import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.publicapi.PublicApiCodecs
import io.eitheror.casino.publicapi.PublicApiCodecs.given_Serializer_Boolean

import java.time.Instant

/**
 * A round (data-model.md `RoundRecord`), keyed by `uuid3("round/" + roundId)`. `round_opened` makes it `open`;
 * `round_settled` settles it (`cancelled` for that outcome, else `settled`), the last fact winning. `startedAt` is
 * `round_opened.occurredAt`, else `round_settled.openedAt`, else `round_settled.occurredAt`.
 */
final case class RoundState(
    id: String = "",
    roundKey: String = "",
    playerId: String = "",
    sessionId: Option[String] = None,
    gameId: String = "",
    currency: String = "",
    openedAt: Option[Instant] = None,
    settledOpenedAt: Option[Instant] = None,
    firstSettledAt: Option[Instant] = None,
    startedAt: Option[Instant] = None,
    settledAt: Option[Instant] = None,
    stakeCents: Long = 0L,
    winCents: Long = 0L,
    realStakeCents: Option[Long] = None,
    realWinCents: Option[Long] = None,
    freeRoundsGrant: Option[String] = None,
    status: String = "",
    walletTxIds: Vector[String] = Vector.empty,
    applied: Vector[String] = Vector.empty
)

object RoundFold:
  def key(fact: f.CasinoRoundsFact): String = fact match
    case x: f.RoundSettled => x.roundId
    case x: f.RoundOpened  => x.roundId

  def apply(s0: RoundState, fact: f.CasinoRoundsFact): RoundState =
    val s1 = fact match
      case x: f.RoundOpened =>
        s0.copy(roundKey = x.roundId, playerId = x.playerId, sessionId = Option(x.sessionId).filter(_.nonEmpty).orElse(s0.sessionId),
          gameId = if s0.gameId.nonEmpty then s0.gameId else x.gameId, currency = x.currency.code, openedAt = Some(x.occurredAt),
          freeRoundsGrant = x.freeRoundsGrant.orElse(s0.freeRoundsGrant), status = if s0.status.isEmpty then "open" else s0.status)
      case x: f.RoundSettled =>
        val cancelled = x.outcome == f.RoundSettled.Outcome.Cancelled
        s0.copy(roundKey = x.roundId, playerId = x.playerId, sessionId = Option(x.sessionId).filter(_.nonEmpty).orElse(s0.sessionId),
          gameId = if x.gameId.nonEmpty then x.gameId else s0.gameId, currency = x.currency.code,
          settledOpenedAt = x.openedAt.orElse(s0.settledOpenedAt), firstSettledAt = s0.firstSettledAt.orElse(Some(x.occurredAt)),
          settledAt = if cancelled then None else Some(x.occurredAt), stakeCents = x.stakeCents.value, winCents = x.winCents.value,
          realStakeCents = x.realStakeCents.map(_.value), realWinCents = x.realWinCents.map(_.value),
          freeRoundsGrant = x.freeRoundsGrant.orElse(s0.freeRoundsGrant), status = if cancelled then "cancelled" else "settled",
          walletTxIds = x.walletTransactionIds)
    s1.copy(id = Records.roundId(s1.roundKey), startedAt = s1.openedAt.orElse(s1.settledOpenedAt).orElse(s1.firstSettledAt))

final case class RoundFact(factId: String, fact: f.CasinoRoundsFact)

final class RoundRecord(@scala.annotation.unused context: KeyValueEntityContext) extends RecordEntity[RoundState]:
  def emptyState: RoundState                                   = RoundState()
  protected def applied(s: RoundState): Vector[String]        = s.applied
  protected def remembered(s: RoundState, ids: Vector[String]) = s.copy(applied = ids)

  def record(c: RoundFact): Effect[Boolean] = once(c.factId)(RoundFold(_, c.fact))
  def get: ReadOnlyEffect[RoundState]       = effects.reply(currentState)

object RoundRecord extends KeyValueEntity.Companion[RoundRecord, RoundState](
      componentId = ComponentId("round-record"),
      stateSerializer = PublicApiCodecs.serializer[RoundState]("round-record")
    ):
  given Serializer[RoundFact]  = Serializer.json[RoundFact]("public-api.round-fact")(using JsonCodecMaker.make[RoundFact])
  given Serializer[RoundState] = PublicApiCodecs.serializer[RoundState]("round-reply")

  def create(context: KeyValueEntityContext) = new RoundRecord(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)
