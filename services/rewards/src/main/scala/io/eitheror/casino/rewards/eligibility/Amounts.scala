package io.eitheror.casino.rewards.eligibility

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.RewardsCodecs
import io.eitheror.casino.rewards.offers.Offer

import java.math.{BigDecimal as JBigDecimal, RoundingMode}

/**
 * An eligible evaluation's terms for the wallet (data-model.md `Claimed`): the amount, its wagering
 * requirement, the most of it that converts, and the currency, all in minor units.
 */
final case class Decision(amount: Long, wageringRequirement: Long, maxConvertible: Long, wageringMultiplier: Long, currency: Currency)

object Decision:
  given JsonValueCodec[Decision] = RewardsCodecs.stored[Decision]

/** FR-007's amount (the original's `computeBonusAmount`, `EE:430-456`), in minor units, half up. */
object Amounts:
  private val Whole = JBigDecimal.valueOf(10000L)

  /** `basisPoints` of `amount`, a fraction of a minor unit rounded half up. */
  def percentOf(amount: Long, basisPoints: Long): Long =
    JBigDecimal.valueOf(amount).multiply(JBigDecimal.valueOf(basisPoints)).divide(Whole, 0, RoundingMode.HALF_UP).longValueExact

  /**
   * The deposit × percentage capped at the maximum value when the offer has a percentage and there is a
   * deposit above zero, else the fixed maximum value (none is zero, as the original's). Enforced, a deposit
   * below the minimum, or none against a minimum, is `BELOW_MIN_DEPOSIT`. The convertible cap is the
   * offer's, else the amount.
   */
  def computeBonusAmount(offer: Offer, deposit: Option[Long], enforceMinDeposit: Boolean, currency: Currency): Either[Decline, Decision] =
    if enforceMinDeposit && deposit.getOrElse(0L) < offer.minDepositAmount.getOrElse(0L) then Left(Decline.BelowMinDeposit)
    else
      val max = offer.maxBonusValue.getOrElse(0L)
      val amount = deposit.filter(_ > 0 && offer.bonusPercentage > 0) match
        case Some(d) => percentOf(d, offer.bonusPercentage).min(max)
        case None    => max
      Right(Decision(amount, Math.multiplyExact(amount, offer.wageringMultiplier), offer.maxConvertibleAmount.getOrElse(amount),
        offer.wageringMultiplier, currency))

  /** The offer's currency when it carries one, else the deposit's, else the player's wallet currency (FR-007). */
  def currencyOf(offer: Offer, deposit: Option[Deposit], wallet: Currency): Currency =
    offer.currency.orElse(deposit.map(_.currency)).getOrElse(wallet)
