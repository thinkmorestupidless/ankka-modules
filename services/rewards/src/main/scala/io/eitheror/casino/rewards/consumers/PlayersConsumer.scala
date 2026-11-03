package io.eitheror.casino.rewards.consumers

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.rewards.RewardsRuntime
import io.eitheror.casino.rewards.clients.{Called, Players}
import io.eitheror.casino.rewards.player.{PlayerChange, PlayerFact, PlayerRewards}

/**
 * `casino.players` into this service (research R9; data-model.md *Consumers*): what the evaluator reads of a player
 * (FR-008), to the player's record. The registration time, the duplicate check in progress and its end, the weak
 * signal's block and its clearing, a fraud flag raised or cleared (the suspected duplicate), and the tier. Each fact
 * carries the brand's detection switch as `players` last answered it (`Detection`). A family not read is ignored.
 */
final class PlayersConsumer(client: ComponentClient, services: ServiceClients) extends Consumer[Option[CasinoPlayersFact], Nothing]:
  def onMessage(fact: Option[CasinoPlayersFact]): Effect = fact match
    case None =>
      Read.unreadable(CasinoPlayers.name, messageContext.subject)
      effects.ignore()
    case Some(f) =>
      PlayersConsumer.change(f) match
        case None => effects.ignore()
        case Some((playerId, change)) =>
          val detection = Detection.current(RewardsRuntime.players(services), refresh = PlayersConsumer.isCheck(change))
          client.forEventSourcedEntity(EntityId(playerId)).call(PlayerRewards.playerFact)
            .invoke(PlayerFact(change, detection, RewardsRuntime.now())): Unit
          effects.done()

object PlayersConsumer extends Consumer.Companion[PlayersConsumer, Option[CasinoPlayersFact], Nothing](
      ComponentId("rewards-players-consumer"), Read.from(CasinoPlayers)):
  def create(ctx: ConsumerContext) = new PlayersConsumer(ctx.componentClient, ctx.services)

  /** The player a fact is of, and what it changes; none for a family this service does not read (pure). */
  def change(fact: CasinoPlayersFact): Option[(String, PlayerChange)] = fact match
    case f: PlayerRegistered            => Some(f.playerId -> PlayerChange.Registered(f.registeredAt, f.currency))
    case f: PlayerDuplicateCheckStarted => Some(f.playerId -> PlayerChange.CheckStarted(f.registeredAt))
    case f: PlayerDuplicateCheckEnded   => Some(f.playerId -> PlayerChange.CheckEnded(f.outcome))
    case f: PlayerNoDepositBlocked      => Some(f.playerId -> PlayerChange.Blocked)
    case f: PlayerNoDepositBlockCleared => Some(f.playerId -> PlayerChange.BlockCleared)
    case f: PlayerFraudFlagged          => Some(f.playerId -> PlayerChange.Flag(true))
    case f: PlayerFraudCleared          => Some(f.playerId -> PlayerChange.Flag(false))
    case f: VipTierChanged              => Some(f.playerId -> PlayerChange.Tier(f.newTier))
    case _                              => None

  /** The duplicate check's own facts: on each, the switch is read again (research R9). */
  def isCheck(change: PlayerChange): Boolean = change match
    case _: PlayerChange.CheckStarted | _: PlayerChange.CheckEnded => true
    case _                                                         => false

/**
 * The brand's duplicate detection switch (research R9), read from `players`' brand configuration when first needed
 * after start and again on each check fact. A read that fails keeps the value last read; with none read yet the fact
 * carries none, and the player's record keeps what it holds (on, unless a fact said otherwise).
 */
object Detection:
  private val log = org.slf4j.LoggerFactory.getLogger("io.eitheror.casino.rewards.consumers.Detection")

  @volatile private var known: Option[Boolean] = None

  def current(players: Players, refresh: Boolean): Option[Boolean] =
    if refresh || known.isEmpty then
      players.brandConfig() match
        case Called.Done(c) => known = Some(c.multiAccountDetection)
        case other          => log.warn(s"the brand's duplicate detection switch could not be read; keeping ${known.fold("none")(_.toString)}: $other")
    known

  /** Forgets the value read, so the next fact reads it: a suite's, between scenarios. */
  def forget(): Unit = known = None
