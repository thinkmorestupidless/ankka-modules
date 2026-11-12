package io.eitheror.casino.gameprovider.facts

import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.{CasinoRounds, CasinoRoundsFact, RoundOpened, RoundSettled}
import io.eitheror.casino.contract.money.{Currency, MinorUnits, SignedMinorUnits}
import io.eitheror.casino.gameprovider.rounds.{Round, RoundEvent, RoundOpening, SettlementSummary}

/**
 * `round_settled` on `casino.rounds` (FR-019, contracts/round-settled.md): one message per settlement or
 * cancellation of a round, in the contract's shape, keyed by the wallet; and `round_opened` (spec 016 G3),
 * one per round, when its first movement opens it. Each `ce-id` is the round and the event's sequence, so a
 * redelivered message carries the same id and a reader can drop it.
 */
final class RoundFacts extends Consumer[RoundEvent, CasinoRoundsFact]:

  def onMessage(event: RoundEvent): Effect = event match
    case RoundEvent.RoundOpened(opening)    => val f = RoundFacts.opened(opening); publish(f, f.key)
    case RoundEvent.RoundSettled(summary)   => val f = RoundFacts.fact(summary); publish(f, f.key)
    case RoundEvent.RoundCancelled(summary) => val f = RoundFacts.fact(summary); publish(f, f.key)
    case _                                  => effects.ignore()

  private def publish(fact: CasinoRoundsFact, key: String): Effect =
    effects.produceAll(Seq(
      effects.message(fact)
        .withKey(key)
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
  def opened(o: RoundOpening): RoundOpened =
    RoundOpened(
      playerId = o.playerId,
      currency = Currency.unsafe(o.currency),
      roundId = Round.entityId(o.provider, o.roundId),
      provider = o.provider,
      providerRoundId = o.roundId,
      gameId = o.gameCode.getOrElse(""),
      sessionId = o.sessionId.getOrElse(""),
      freeRoundsGrant = o.freeRoundsGrant,
      occurredAt = o.at
    )

  /**
   * `openedAt` is the round's opening, so a reader that started after `round_opened` still has the start;
   * `realStakeCents` and `realWinCents` are the real-money parts of the stake and win, from the wallet's
   * answers (`RoundState.realStakeAndWin`), absent for a round with a movement recorded before spec 016.
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
      gameType = s.gameType,
      openedAt = s.openedAt,
      realStakeCents = s.realStake.map(MinorUnits.unsafe),
      realWinCents = s.realWin.map(MinorUnits.unsafe)
    )
