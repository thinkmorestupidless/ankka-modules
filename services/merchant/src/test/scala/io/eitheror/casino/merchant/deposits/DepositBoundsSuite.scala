package io.eitheror.casino.merchant.deposits

import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.merchant.audit.AuditFacts
import io.eitheror.casino.merchant.hierarchy.*

import java.time.Instant

/**
 * Spec 030 H3 (spec 004 FR-029c): the deposit bounds' rules, with the original's comparisons
 * (`DepositPreflightRoutes.scala`: refused below when `amount < min`, above when `amount > max`), and
 * the sub-brand that keeps them.
 */
class DepositBoundsSuite extends munit.FunSuite with LogCapturing:

  private val at   = Instant.parse("2026-11-15T10:00:00Z")
  private val op   = Actor.operator("op-1")
  private val card = DepositBound("card", "EUR", Some(1000), Some(500000))

  test("a terminal's method is its scheme's category in the catalogue, else its scheme") {
    assertEquals(DepositBounds.methodOf("DREAMZPAY", "VISA"), "card")
    assertEquals(DepositBounds.methodOf("eupaymentz", "mastercard"), "card")
    assertEquals(DepositBounds.methodOf("DREAMZPAY", "BANK_TRANSFER"), "bank_transfer")
    assertEquals(DepositBounds.methodOf("BOUNCEMONEY", "ONRAMP_VISA"), "fiat_to_crypto")
    assertEquals(DepositBounds.methodOf("COZENI", "CRYPTO"), "crypto")
    assertEquals(DepositBounds.methodOf("NEWPSP", "Instant-Pay"), "instant_pay")
    assert(DepositBounds.methods.contains("card") && DepositBounds.methods.contains("open_banking"), DepositBounds.methods.toString)
  }

  test("both bounds are inclusive; a pair with no bounds, and another method or currency, takes any amount") {
    val bounds = Vector(card)
    assertEquals(DepositBounds.broken(bounds, "card", "EUR", 999), Some(BrokenBound("card", "EUR", BrokenBound.Minimum, 1000)))
    assertEquals(DepositBounds.broken(bounds, "card", "EUR", 1000), None)
    assertEquals(DepositBounds.broken(bounds, "card", "EUR", 500000), None)
    assertEquals(DepositBounds.broken(bounds, "card", "EUR", 500001), Some(BrokenBound("card", "EUR", BrokenBound.Maximum, 500000)))
    assertEquals(DepositBounds.broken(bounds, "card", "USD", 1), None)
    assertEquals(DepositBounds.broken(bounds, "crypto", "EUR", 1), None)
    assertEquals(DepositBounds.broken(Vector(DepositBound("card", "EUR", None, Some(2000))), "card", "EUR", 1), None)
    assertEquals(DepositBounds.broken(Vector.empty, "card", "EUR", Long.MaxValue), None)
  }

  test("a broken bound names its code and the bound as a decimal in its currency") {
    val below = BrokenBound("card", "EUR", BrokenBound.Minimum, 1000)
    assertEquals((below.code, below.rendered), ("DEPOSIT_BELOW_MINIMUM", "10.00 EUR"))
    assertEquals(BrokenBound("card", "JPY", BrokenBound.Maximum, 5000).code, "DEPOSIT_ABOVE_MAXIMUM")
    assertEquals(BrokenBound("card", "JPY", BrokenBound.Maximum, 5000).rendered, "5000 JPY")
  }

  test("bounds are refused unless each names a method, a currency and one positive bound at least, the least no more than the most, once") {
    def refused(b: DepositBound*) = assert(DepositBounds.validate(b.toVector).isLeft, b.toString)
    refused(card.copy(method = "Card"))
    refused(card.copy(method = ""))
    refused(card.copy(currency = "eur"))
    refused(card.copy(minimum = None, maximum = None))
    refused(card.copy(minimum = Some(0)))
    refused(card.copy(maximum = Some(-1)))
    refused(card.copy(minimum = Some(600000)))
    refused(card, card.copy(minimum = Some(2000)))
    val sorted = DepositBounds.validate(Vector(card.copy(currency = "USD"), DepositBound("bank_transfer", "EUR", None, Some(1)), card))
    assertEquals(sorted.map(_.map(b => (b.method, b.currency))), Right(Vector(("bank_transfer", "EUR"), ("card", "EUR"), ("card", "USD"))))
    assertEquals(DepositBounds.validate(Vector(card.copy(maximum = Some(1000)))).map(_.size), Right(1), "the least may equal the most")
  }

  test("a sub-brand replaces its bounds, records the same set again as nothing, refuses a bad set and audits a change") {
    val kit = EventSourcedTestKit.of(SubBrand, "sb-1")
    assertEquals(kit.call(SubBrand.setDepositBounds)(SetDepositBounds(Vector(card), op, at)).error.code, ErrorCode.NotFound)
    kit.call(SubBrand.create)(CreateSubBrand("c-1", "h-1", "Acme Casino", SubBrandType.Casino, Some("acme"), op, at)): Unit
    val set = kit.call(SubBrand.setDepositBounds)(SetDepositBounds(Vector(card), op, at))
    assertEquals(set.events, Vector(SubBrandEvent.DepositBoundsSet(Vector(card), op, at)))
    assertEquals(kit.currentState.depositBounds, Vector(card))
    assert(!kit.call(SubBrand.setDepositBounds)(SetDepositBounds(Vector(card), op, at)).persisted)
    assertEquals(kit.call(SubBrand.setDepositBounds)(SetDepositBounds(Vector(card.copy(minimum = Some(0))), op, at)).error.code, ErrorCode.BadRequest)
    assertEquals(kit.currentState.depositBounds, Vector(card))
    kit.call(SubBrand.setDepositBounds)(SetDepositBounds(Vector.empty, op, at)): Unit
    assertEquals(kit.currentState.depositBounds, Vector.empty)
    val audited = AuditFacts.subBrand(SubBrandEvent.DepositBoundsSet(Vector(card), op, at)).get
    assertEquals((audited.action, audited.changes.flatMap(_.after)), ("deposit bounds set", Vector("card/EUR:1000..500000")))
  }
