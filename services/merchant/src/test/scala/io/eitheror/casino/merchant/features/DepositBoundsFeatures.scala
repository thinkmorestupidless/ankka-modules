package io.eitheror.casino.merchant.features

import io.eitheror.casino.contract.money.{Amounts, Currency}
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.deposits.DepositBounds
import io.eitheror.casino.merchant.it.{Ready, Reply}
import io.eitheror.casino.merchant.payments.{PaymentRow, PaymentsView}

import scala.jdk.CollectionConverters.*

/**
 * features/payments/deposit-bounds.feature: the deposit bounds (spec 030 H3, spec 004 FR-029c), and the
 * brand's reads (H1, H2: FR-029a, FR-029b). The test payment is H4's, bound with it (spec 030 T107).
 */
class DepositBoundsFeatures extends MerchantFeatures("deposit-bounds.feature"):

  override protected def ranElsewhere: Map[String, String] =
    Map("a test payment answers the provider's outcome and credits no wallet" -> DepositBoundsFeatures.TestPayment)

  private var initiatesBefore = 0
  private var other: Ready    = null
  private var mine            = ""
  private var theirs          = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    initiatesBefore = 0; other = null; mine = ""; theirs = ""

  private def money(text: String, currency: String): Long =
    Amounts.parse(text, Currency.unsafe(currency)).fold(e => fail(e.message), _.value)

  private def setBounds(method: String, currency: String, min: String, max: String): Reply =
    val bounds = Json.obj("method" -> method, "currency" -> currency, "minimumAmountCents" -> money(min, currency), "maximumAmountCents" -> money(max, currency))
    kit.request("PUT", s"/v1/sub-brands/${ready.subBrandId}/deposit-bounds", Some(Json.render(Json.obj("bounds" -> Json.arr(bounds)))),
      kit.asOperator(level = "sub_brand", scopeId = ready.subBrandId, roles = "resource_admin"))

  // ── the bounds (H3) ────────────────────────────────────────────────────────────────────────────

  Given("a sub-brand with no deposit bounds") { () =>
    ready = kit.subBrand()
    assertEquals(kit.request("GET", s"/v1/sub-brands/${ready.subBrandId}/deposit-bounds", None, kit.asOperator()).json.get("bounds").size, 0)
  }

  When("a payments operator sets the deposit bounds for the method {string} in {string} to at least {string} and at most {string}") {
    (method: String, currency: String, min: String, max: String) =>
      last = setBounds(method, currency, min, max)
      assertEquals(last.status, 200, last.text)
  }

  Then("the sub-brand's deposit bounds for the method {string} in {string} are {string} and {string}") {
    (method: String, currency: String, min: String, max: String) =>
      val read = kit.request("GET", s"/v1/sub-brands/${ready.subBrandId}/deposit-bounds", None, kit.asOperator())
      assertEquals(read.status, 200, read.text)
      val b = read.json.get("bounds").elements.asScala.find(b => b.textOr("method", "") == method && b.textOr("currency", "") == currency)
        .getOrElse(fail(s"no bounds for $method in $currency: ${read.text}"))
      assertEquals((b.textOr("minimumAmount", ""), b.textOr("maximumAmount", "")), (min, max))
      assertEquals((b.long("minimumAmountCents"), b.long("maximumAmountCents")), (Some(money(min, currency)), Some(money(max, currency))))
  }

  Given("a sub-brand whose deposit bounds for the method {string} in {string} are {string} and {string}") {
    (method: String, currency: String, min: String, max: String) =>
      ready = kit.subBrand()
      val r = setBounds(method, currency, min, max)
      assertEquals(r.status, 200, r.text)
  }

  When("a player starts a deposit of {string} by the method {string}") { (deposit: String, method: String) =>
    val Array(amount, currency) = deposit.split(' ')
    assertEquals(DepositBounds.methodOf(ready.credential.provider, ready.credential.scheme.getOrElse("")), method, "the kit's credential is that method")
    val s = kit.session(ready, money(amount, currency), currency)
    assertEquals(s.status, 200, s.text)
    initiatesBefore = kit.gateway.initiates.size
    last = kit.submit(s.str("session_token"))
    var tries = 1
    while last.status == 503 && last.str("error") == "OUTCOME_PENDING" && tries < 10 do
      last = kit.submit(s.str("session_token")); tries += 1
  }

  private def refused(code: String, kind: String, named: String): Unit =
    assertEquals((last.status, last.str("error")), (403, code), last.text)
    assert(last.str("message").contains(named), last.text)
    val Array(amount, currency) = named.split(' ')
    assertEquals((last.str(s"${kind}Amount"), last.str("currency"), last.json.long(s"${kind}AmountCents")),
      (amount, currency, Some(money(amount, currency))))
    assertEquals(last.str("method"), "card")

  Then("the deposit is refused as below the minimum, naming {string}") { (named: String) => refused("DEPOSIT_BELOW_MINIMUM", "minimum", named) }
  Then("the deposit is refused as above the maximum, naming {string}") { (named: String) => refused("DEPOSIT_ABOVE_MAXIMUM", "maximum", named) }

  Then("no payment provider is called") { () =>
    assertEquals(kit.gateway.initiates.size, initiatesBefore, "the gateway was asked to initiate")
    assert(last.str("paymentId").isEmpty, last.text)
  }

  Then("the deposit is initiated") { () =>
    assertEquals(last.status, 200, last.text)
    assertEquals(last.str("status"), "AWAITING_REDIRECT")
    assertEquals(kit.gateway.initiates.size, initiatesBefore + 1)
  }

  // ── the brand's reads (H1, H2) ─────────────────────────────────────────────────────────────────

  private def ids(r: Reply): Set[String] = r.json.get("payments").elements.asScala.map(_.textOr("paymentId", "")).toSet

  private def row(paymentId: String): PaymentRow =
    kit.eventually(s"row $paymentId")(kit.views.forView(PaymentsView).all(100_000).find(_.paymentId == paymentId))

  private def brandOperator(slug: String) = kit.asOperator(level = "none", scopeId = "*", roles = s"brand_$slug", subject = "brand-op")

  Given("a sub-brand naming the brand {string} and another naming no brand") { (brand: String) =>
    ready = kit.subBrand(brand = Some(brand))
    other = kit.subBrand(brand = None)
    mine = kit.payment(ready)
    theirs = kit.payment(other)
    Seq(mine, theirs).foreach(row)
  }

  When("an operator of the brand {string} reads deposits") { (brand: String) =>
    last = kit.eventually("the brand's deposit listed")(
      Option(kit.request("GET", "/v1/payments/cashier", None, brandOperator(brand))).filter(r => r.status == 200 && ids(r)(mine)))
  }

  Then("the deposits of the sub-brand naming {string} are listed") { (brand: String) =>
    assert(ids(last)(mine), last.text)
    assert(!ids(last)(theirs), "the other sub-brand's deposit is not listed")
    val own = kit.request("GET", s"/v1/payments/cashier?merchantId=${ready.subBrandId}", None, brandOperator(brand))
    assertEquals(ids(own), Set(mine))
  }

  Then("a read of the other sub-brand's deposits is refused") { () =>
    val brand = ready.brand.get
    val list  = kit.request("GET", s"/v1/payments/cashier?merchantId=${other.subBrandId}", None, brandOperator(brand))
    assertEquals((list.status, list.str("error")), (403, "scope_violation"), list.text)
    val one = kit.request("GET", s"/v1/payments/$theirs", None, brandOperator(brand))
    assertEquals((one.status, one.str("error")), (403, "scope_violation"), one.text)
  }

  Given("a captured deposit with a payment reference") { () =>
    ready = kit.subBrand()
    mine = kit.payment(ready)
    theirs = kit.payment(ready)
    assertEquals(kit.capture(mine).status, 200)
    Seq(mine, theirs).foreach(row)
  }

  When("an operator reads deposits by that payment reference") { () =>
    val reference = kit.paymentState(mine).opened.get.pspTransactionId
    assert(reference.nonEmpty)
    last = kit.eventually("found by its reference")(
      Option(kit.request("GET", s"/v1/payments/cashier?reference=${java.net.URLEncoder.encode(reference, "UTF-8")}", None, kit.asOperator()))
        .filter(r => r.status == 200 && ids(r).nonEmpty))
  }

  Then("only that deposit is listed") { () => assertEquals(ids(last), Set(mine)) }

object DepositBoundsFeatures:
  // Here, not in the class: GherkinSuite reads ranElsewhere while it is constructed, before the class's own vals.
  private val TestPayment = "spec 030 H4, bound with the test payment (T107)"
