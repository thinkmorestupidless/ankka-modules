package io.eitheror.casino.rewards.eligibility

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.offers.{Offer, OfferType}

import java.time.Instant

/**
 * FR-007's amount, in minor units with the percentage in basis points and a fraction of a minor unit
 * rounded half up, from `EligibilityEvaluatorSpec`'s `computeBonusAmount` cases.
 */
class AmountsSuite extends munit.FunSuite:
  private val now = Instant.parse("2026-11-01T12:00:00Z")
  private val Gbp = Currency.unsafe("GBP")

  private def offer(percentage: Long = 0L, max: Option[Long] = None, min: Option[Long] = None, multiplier: Long = 3L) =
    Offer(id = "o-1", offerType = OfferType.DepositMatch, bonusPercentage = percentage, maxBonusValue = max,
      minDepositAmount = min, wageringMultiplier = multiplier, createdAt = now, updatedAt = now)

  private def compute(o: Offer, deposit: Option[Long], enforce: Boolean = false) =
    Amounts.computeBonusAmount(o, deposit, enforce, Gbp)

  test("cap the percentage-of-deposit calculation at the maximum value; the requirement is the amount times the multiplier"):
    val d = compute(offer(percentage = 5000L, max = Some(10000L)), Some(100000L), enforce = true).toOption.get
    assertEquals((d.amount, d.wageringRequirement, d.wageringMultiplier), (10000L, 30000L, 3L))

  test("fall back to the maximum value when the percentage is zero"):
    assertEquals(compute(offer(max = Some(7500L)), Some(50000L)).map(_.amount), Right(7500L))

  test("fall back to the maximum value when there is no deposit, or a deposit of zero"):
    assertEquals(compute(offer(percentage = 5000L, max = Some(6000L)), None).map(_.amount), Right(6000L))
    assertEquals(compute(offer(percentage = 5000L, max = Some(6000L)), Some(0L)).map(_.amount), Right(6000L))

  test("BELOW_MIN_DEPOSIT when enforced and the deposit is below the minimum, or there is none"):
    assertEquals(compute(offer(min = Some(5000L)), Some(1000L), enforce = true), Left(Decline.BelowMinDeposit))
    assertEquals(compute(offer(min = Some(5000L)), None, enforce = true), Left(Decline.BelowMinDeposit))
    assert(compute(offer(min = Some(5000L), max = Some(1L)), Some(5000L), enforce = true).isRight)
    assert(compute(offer(max = Some(1L)), None, enforce = true).isRight, "no minimum is a minimum of zero")

  test("ignore the minimum entirely when not enforced (the webhook's exemption)"):
    assertEquals(compute(offer(percentage = 5000L, max = Some(10000L), min = Some(5000L)), Some(1000L)).map(_.amount), Right(500L))

  test("a fraction of a minor unit is rounded half up"):
    assertEquals(compute(offer(percentage = 5000L, max = Some(100000L)), Some(1001L)).map(_.amount), Right(501L))
    assertEquals(compute(offer(percentage = 5000L, max = Some(100000L)), Some(1L)).map(_.amount), Right(1L))
    assertEquals(compute(offer(percentage = 3333L, max = Some(100000L)), Some(1003L)).map(_.amount), Right(334L))
    assertEquals(compute(offer(percentage = 2500L, max = Some(100000L)), Some(1001L)).map(_.amount), Right(250L))
    assertEquals(Amounts.percentOf(1999L, 2500L), 500L)
    assertEquals(Amounts.percentOf(1998L, 2500L), 500L)
    assertEquals(Amounts.percentOf(1997L, 2500L), 499L)

  test("a deposit match of 100% up to 500 on a deposit of 50 is 50 (offers.feature)"):
    assertEquals(compute(offer(percentage = 10000L, max = Some(50000L)), Some(5000L)).map(_.amount), Right(5000L))

  test("a large deposit does not overflow before the cap"):
    assertEquals(compute(offer(percentage = 20000L, max = Some(100L)), Some(Long.MaxValue / 2)).map(_.amount), Right(100L))

  test("the currency is the one resolved for it"):
    assertEquals(compute(offer(max = Some(1L)), None).map(_.currency), Right(Gbp))

  test("the convertible cap defaults to the amount when the offer sets none, and an explicit one is kept"):
    assertEquals(compute(offer(max = Some(1000L)), None).map(_.maxConvertible), Right(1000L))
    assertEquals(compute(offer(max = Some(1000L)).copy(maxConvertibleAmount = Some(500L)), None).map(_.maxConvertible), Right(500L))

  test("an offer with neither a maximum value nor a convertible cap awards nothing and caps nothing"):
    val d = compute(offer(), None).toOption.get
    assertEquals((d.amount, d.maxConvertible), (0L, 0L))

  test("a percentage with no maximum value is capped at zero, as the original's"):
    assertEquals(compute(offer(percentage = 5000L), Some(10000L)).map(_.amount), Right(0L))

  test("a decision survives its stored form"):
    import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
    val d = compute(offer(percentage = 5000L, max = Some(10000L)), Some(100000L)).toOption.get
    assertEquals(readFromString[Decision](writeToString(d)), d)
