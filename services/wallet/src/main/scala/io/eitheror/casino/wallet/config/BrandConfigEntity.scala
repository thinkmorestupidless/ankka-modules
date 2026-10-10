package io.eitheror.casino.wallet.config

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.wallet.entity.{BetSwitches, TurnoverTerms}

import java.time.Instant

/**
 * The brand's bet switches (FR-004) and deposit turnover terms (FR-004a): one key value entity per
 * brand, id `brand` (the project is the brand). Only the latest value matters; nobody reacts to how
 * a rate changed, and the audit of each write is `platform.audit`'s (research R7).
 *
 * Absent values are the original's defaults (`rules.go`, `deposit.tl`): contribution rate 100 for
 * every game type, bonus wagering counts, partial splits on, no limit on concurrent awards, and
 * turnover once the deposit, gross wagered.
 */
final case class BrandConfig(
    contributionRateByGameType: Map[String, Long] = Map.empty,
    bonusWageringCounts: Boolean = true,
    partialSplitsAllowed: Boolean = true,
    maxConcurrentAwards: Option[Int] = None,
    turnover: TurnoverTerms = TurnoverTerms.Default,
    updatedBy: String = "",
    updatedAt: Option[Instant] = None
):
  /** The switches a bet on a game of this type is funded under. */
  def switchesFor(gameType: String): BetSwitches =
    BetSwitches(contributionRateByGameType.getOrElse(gameType, BrandConfig.DefaultRate), bonusWageringCounts, partialSplitsAllowed)

object BrandConfig:
  val DefaultRate: Long = 100L

/** `rewards`' write (FR-004): replaces the switches and leaves the turnover terms. */
final case class SetBetSwitches(
    contributionRateByGameType: Map[String, Long],
    bonusWageringCounts: Boolean,
    partialSplitsAllowed: Boolean,
    maxConcurrentAwards: Option[Int],
    by: String,
    at: Instant
)

/** The operator's write (FR-004a): replaces the turnover terms and leaves the switches. */
final case class SetTurnoverTerms(terms: TurnoverTerms, by: String, at: Instant)

final class BrandConfigEntity(context: KeyValueEntityContext) extends KeyValueEntity[BrandConfig]:

  def emptyState: BrandConfig = BrandConfig()

  def setBetSwitches(c: SetBetSwitches): Effect[BrandConfig] =
    if c.contributionRateByGameType.values.exists(r => r < 0) then effects.error("a contribution rate is a whole, non-negative percent")
    else if c.maxConcurrentAwards.exists(_ < 1) then effects.error("a limit on concurrent awards is at least 1")
    else
      effects.updateState(currentState.copy(
        contributionRateByGameType = c.contributionRateByGameType,
        bonusWageringCounts = c.bonusWageringCounts,
        partialSplitsAllowed = c.partialSplitsAllowed,
        maxConcurrentAwards = c.maxConcurrentAwards,
        updatedBy = c.by,
        updatedAt = Some(c.at)
      )).thenReplyState

  def setTurnoverTerms(c: SetTurnoverTerms): Effect[BrandConfig] =
    if c.terms.multiplier < 0 then effects.error("a turnover multiplier is a whole, non-negative number")
    else effects.updateState(currentState.copy(turnover = c.terms, updatedBy = c.by, updatedAt = Some(c.at))).thenReplyState

  def get: ReadOnlyEffect[BrandConfig] = effects.reply(currentState)

object BrandConfigEntity
    extends KeyValueEntity.Companion[BrandConfigEntity, BrandConfig](
      componentId = ComponentId("brand-config"),
      stateSerializer = Codecs.serializer[BrandConfig]("wallet.brand-config")
    ):

  given Serializer[SetBetSwitches]   = Codecs.serializer[SetBetSwitches]("wallet.set-bet-switches")
  given Serializer[SetTurnoverTerms] = Codecs.serializer[SetTurnoverTerms]("wallet.set-turnover-terms")

  /** One per brand project. */
  val Id: EntityId = EntityId("brand")

  def create(context: KeyValueEntityContext) = new BrandConfigEntity(context)

  val setBetSwitches   = command("set-bet-switches")(_.setBetSwitches)
  val setTurnoverTerms = command("set-turnover-terms")(_.setTurnoverTerms)
  val get              = query("get")(_.get)
