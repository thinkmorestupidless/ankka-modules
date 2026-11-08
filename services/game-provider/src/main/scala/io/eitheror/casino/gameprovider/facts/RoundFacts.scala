package io.eitheror.casino.gameprovider.facts

import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.{CasinoRounds, CasinoRoundsFact, RoundSettled}
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.gameprovider.rounds.{Round, RoundEvent, SettlementSummary}

/**
 * `round_settled` on `casino.rounds` (FR-019, contracts/round-settled.md): one message per settlement or
 * cancellation of a round, in the contract's shape, keyed by the wallet. Its `ce-id` is the round and the
 * event's sequence, so a redelivered settlement carries the same id and a reader can drop it.
 */
final class RoundFacts extends Consumer[RoundEvent, CasinoRoundsFact]:

  def onMessage(event: RoundEvent): Effect = event match
    case RoundEvent.RoundSettled(summary)   => publish(summary)
    case RoundEvent.RoundCancelled(summary) => publish(summary)
    case _                                  => effects.ignore()

  private def publish(s: SettlementSummary): Effect =
    val fact = RoundFacts.fact(s)
    effects.produceAll(Seq(
      effects.message(fact: CasinoRoundsFact)
        .withKey(fact.key)
        .withMetadata(Metadata.empty.set("ce-id", s"${messageContext.subject}/${messageContext.sequenceNumber}"))
    ))

object RoundFacts extends Consumer.Companion[RoundFacts, RoundEvent, CasinoRoundsFact](
      componentId = ComponentId("round-facts"),
      source = ChangeSource.eventsOf(Round)
    ):
  def create(ctx: ConsumerContext) = new RoundFacts

  override val outputSerializer: Option[Serializer[CasinoRoundsFact]] = Some(CasinoRounds.serializer)
  override val produceTo: Option[String]                              = Some(CasinoRounds.name)
  // The topic's contract, which the project declares: ankka refuses a publisher that names none (T082).
  override val produces: Option[Publication] = Some(Publication(CasinoRounds.name, Some(CasinoRounds.contract), None))

  /**
   * `sessionId` is the game session's id, the `session_id` of `casino.sessions`' `game_session_started`:
   * the launch opens it at `players` (`api/Launch.scala:96`, whose answer is the `GameSessionEntity`'s id,
   * `players/.../api/GameSessionRoutes.scala:67-71`) and attaches it to the ticket; the provider's callbacks
   * name the ticket, and the round takes the session from it when it opens (`api/callbacks/Callbacks.scala`,
   * `opening`). Empty when no ticket named the round (a buyin or payout without one); a random id, matching
   * no session, when `players` did not answer the launch (FR-009). `gameName` and `gameType` (the
   * catalogue's category) are the catalogue's when the round opened, absent for a game it did not hold
   * (spec 015 research R11).
   */
  def fact(s: SettlementSummary): RoundSettled =
    RoundSettled(
      playerId = s.playerId,
      currency = Currency.unsafe(s.currency),
      roundId = Round.entityId(s.provider, s.roundId),
      provider = s.provider,
      providerRoundId = s.roundId,
      gameId = s.gameCode.getOrElse(""),
      sessionId = s.sessionId.getOrElse(""),
      stakeCents = MinorUnits.unsafe(math.max(0L, s.stake)),
      winCents = MinorUnits.unsafe(math.max(0L, s.win)),
      netResultCents = SignedMinorUnits(s.win - s.stake),
      outcome = s.outcome,
      transactionIds = s.walletTransactionIds,
      providerTransactionIds = s.providerTransactionIds,
      walletTransactionIds = s.walletTransactionIds,
      freeRoundsGrant = s.freeRoundsGrant,
      occurredAt = s.at,
      gameName = s.gameName,
      gameType = s.gameType
    )
