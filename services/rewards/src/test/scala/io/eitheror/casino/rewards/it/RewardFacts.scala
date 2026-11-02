package io.eitheror.casino.rewards.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.eligibility.Decision
import io.eitheror.casino.rewards.offers.PayloadType
import io.eitheror.casino.rewards.reward.*

import java.util.UUID

/** One fact on `casino.rewards` as it was published: the fact and its `ce-id`. */
final case class RewardFact(fact: CasinoRewardsFact, ceId: Option[String]):
  def family: String = RewardFacts.family(fact)

/**
 * A reward's facts on `casino.rewards` (contracts/facts.md), and rewards opened on `Reward` itself for the suites
 * that drive one through its transitions: the publisher reads the reward's events whatever moved it.
 */
final class RewardFacts(kit: Kit):
  val Eur: Currency = Currency.unsafe("EUR")

  def unique(prefix: String): String = s"$prefix-${UUID.randomUUID().toString.take(8)}"

  /** Every fact published for the reward, in order, with its `ce-id`. */
  def of(rewardId: String): Vector[RewardFact] =
    kit.broker.publishedTo(CasinoRewards.name).toVector.flatMap { d =>
      scala.util.Try(CasinoRewards.serializer.fromBytes(d.message.payload)).toOption
        .filter(f => RewardFacts.bonusId(f).contains(rewardId))
        .map(RewardFact(_, d.message.metadata.eventId.orElse(d.message.metadata.get("ce-id"))))
    }

  /** The reward's fact of `family`, once published. */
  def awaitFamily(rewardId: String, family: String): RewardFact =
    kit.eventually(s"$family for $rewardId")(of(rewardId).find(_.family == family))

  /** What a funds reward is opened with: an affiliate's code and tag, so every fact must carry all three. */
  def funds(player: String, code: String = unique("code"), tag: String = unique("tag")): Opening =
    Opening(player, unique("o"), "Facts", "DEPOSIT_MATCH", PayloadType.BonusFunds, Decision(1000L, 10000L, 1000L, 10L, Eur),
      priority = Some(1L), source = Source.AffiliateCode, code = Some(code), tag = Some(tag))

  /** What a free-rounds reward of `count` rounds is opened with, given by an operator. */
  def freeRounds(player: String, count: Int = 1): Opening =
    Opening(player, unique("o"), "Facts rounds", "FREE_SPINS", PayloadType.FreeSpins, Decision(0L, 0L, 0L, 0L, Eur),
      source = Source.OperatorManual, freeRounds = Some(FreeRoundsTerms("g-1", "st8", count, 20L, Destination.RealBalance)),
      by = Some("op-1"))

  def reward(id: String) = kit.components.forEventSourcedEntity(EntityId(id))

  /** A reward opened in `initial`, its id; an `ACTIVE` one with its wallet record under its id. */
  def open(opening: Opening, initial: RewardStatus): String =
    val id    = unique("rw")
    val award = Option.when(initial == RewardStatus.Active && opening.freeRounds.isEmpty)(WalletAward(Some(id), 10000L))
    val a     = reward(id).call(Reward.open).invoke(Open(opening, initial, kit.now(), award))
    assert(a.refusal.isEmpty, s"$id not opened: ${a.refusal}")
    id

object RewardFacts:
  def family(f: CasinoRewardsFact): String = f match
    case _: BonusGranted     => "bonus_granted"
    case _: BonusCancelled   => "bonus_cancelled"
    case _: BonusForfeited   => "bonus_forfeited"
    case _: BonusCompleted   => "bonus_completed"
    case _: GrantExpired     => "grant_expired"
    case _: FreeSpinsGranted => "free_spins_granted"
    case _: FreeSpinsSettled => "free_spins_settled"
    case _: BonusCodeEntered => "bonus_code_entered"

  def bonusId(f: CasinoRewardsFact): Option[String] = f match
    case g: BonusGranted     => Some(g.bonusId)
    case c: BonusCancelled   => Some(c.bonusId)
    case x: BonusForfeited   => Some(x.bonusId)
    case c: BonusCompleted   => Some(c.bonusId)
    case e: GrantExpired     => Some(e.bonusId)
    case g: FreeSpinsGranted => Some(g.bonusId)
    case s: FreeSpinsSettled => Some(s.bonusId)
    case _: BonusCodeEntered => None

  /** The fact's source, code and tag (research R11). */
  def origin(f: CasinoRewardsFact): (Option[String], Option[String], Option[String]) = f match
    case g: BonusGranted     => (g.source, g.code, g.tag)
    case c: BonusCancelled   => (c.source, c.code, c.tag)
    case x: BonusForfeited   => (x.source, x.code, x.tag)
    case c: BonusCompleted   => (c.source, c.code, c.tag)
    case e: GrantExpired     => (e.source, e.code, e.tag)
    case g: FreeSpinsGranted => (g.source, g.code, g.tag)
    case s: FreeSpinsSettled => (s.source, s.code, s.tag)
    case c: BonusCodeEntered => (None, Some(c.code), None)
