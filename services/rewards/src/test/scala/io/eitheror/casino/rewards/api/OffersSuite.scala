package io.eitheror.casino.rewards.api

import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.rewards.clients.SpinValues
import io.eitheror.casino.rewards.offers.SpinCheck

/** The spin value check of `/offers` (FR-002), apart from the provider. */
class OffersSuite extends munit.FunSuite:
  private val check = SpinCheck("g-1", Currency.unsafe("EUR"), 20L)

  test("a spin value offered passes; one not offered answers the values offered, in minor units"):
    assertEquals(Offers.notOffered(SpinValues(Vector("0.20"), configured = true), check), None)
    assertEquals(Offers.notOffered(SpinValues(Vector("0.1", "0.25"), configured = true), check), Some(Vector(10L, 25L)))

  test("a provider with no configuration lets any spin value through"):
    assertEquals(Offers.notOffered(SpinValues(Vector.empty, configured = false), check), None)
