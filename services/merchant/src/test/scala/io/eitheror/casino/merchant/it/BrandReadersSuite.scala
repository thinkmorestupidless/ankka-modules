package io.eitheror.casino.merchant.it

import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.payments.{PaymentRow, PaymentsView}

import java.util.UUID
import scala.jdk.CollectionConverters.*

/**
 * Spec 030 H1, H2 (spec 004 FR-029a, FR-029b): an operator holding `brand_<slug>` reads the payments
 * list, the summary and a payment of the sub-brands naming that brand and no other; another's is
 * refused `403 scope_violation`, as a payments operator's out of their level is. `?reference=` finds a
 * payment exactly, within what the reader may read. Payments operators read as they did before.
 */
class BrandReadersSuite extends SharedKitSuite:

  private def slug(): String = s"b${UUID.randomUUID().toString.replace("-", "").take(8)}"

  /** A brand operator's token: `brand_<slug>` and no payments role or level. */
  private def brandOperator(slugs: String*): Seq[(String, String)] =
    kit.asOperator(level = "none", scopeId = "*", roles = slugs.map(s => s"brand_$s").mkString(","), subject = "brand-op")

  private def ids(r: Reply): Set[String] = r.json.get("payments").elements.asScala.map(_.textOr("paymentId", "")).toSet

  private def row(paymentId: String): PaymentRow =
    kit.eventually(s"row $paymentId")(kit.views.forView(PaymentsView).all(100_000).find(_.paymentId == paymentId))

  private def refusedOutOfScope(r: Reply): Unit = assertEquals((r.status, r.str("error")), (403, "scope_violation"), r.text)

  test("a brand's operator reads the list, the summary and a payment of the sub-brand naming their brand, and no other's") {
    val (a, b) = (slug(), slug())
    val mine   = kit.subBrand(brand = Some(a))
    val theirs = kit.subBrand(brand = Some(b))
    val none   = kit.subBrand(brand = None)
    val pa = kit.payment(mine); val pb = kit.payment(theirs); val pn = kit.payment(none)
    assertEquals(kit.capture(pa).status, 200)
    Seq(pa, pb, pn).foreach(row)
    val op = brandOperator(a)

    val listed = kit.eventually("A's listed")(Option(kit.request("GET", "/v1/payments/cashier", None, op)).filter(r => r.status == 200 && ids(r)(pa)))
    assertEquals(ids(listed) & Set(pb, pn), Set.empty[String], "no other sub-brand's payment is listed")
    assertEquals(ids(kit.request("GET", s"/v1/payments/cashier?merchantId=${mine.subBrandId}", None, op)), Set(pa))
    refusedOutOfScope(kit.request("GET", s"/v1/payments/cashier?merchantId=${theirs.subBrandId}", None, op))
    refusedOutOfScope(kit.request("GET", s"/v1/payments/cashier?merchantId=${none.subBrandId}", None, op))
    refusedOutOfScope(kit.request("GET", "/v1/payments/cashier?merchantId=sb-nowhere", None, op))

    val summary = kit.request("GET", s"/v1/payments/cashier/summary?merchantId=${mine.subBrandId}", None, op)
    assertEquals(summary.status, 200, summary.text)
    assertEquals(summary.json.get("statusCounts").get("captured").asInt, 1)
    refusedOutOfScope(kit.request("GET", s"/v1/payments/cashier/summary?merchantId=${theirs.subBrandId}", None, op))
    assertEquals(kit.request("GET", "/v1/payments/cashier/summary", None, op).json.get("count").asInt, 1, "the summary covers A's sub-brand alone")

    val read = kit.request("GET", s"/v1/payments/$pa", None, op)
    assertEquals((read.status, read.str("merchantId")), (200, mine.subBrandId), read.text)
    refusedOutOfScope(kit.request("GET", s"/v1/payments/$pb", None, op))
    refusedOutOfScope(kit.request("GET", s"/v1/payments/$pn", None, op))
  }

  test("a brand's operator reads no route but the three reads") {
    val a  = slug()
    val r  = kit.subBrand(brand = Some(a))
    val op = brandOperator(a)
    assertEquals(kit.request("GET", "/v1/payments/cashier/uncredited", None, op).status, 403)
    assertEquals(kit.request("GET", s"/v1/payments/terminals?merchantId=${r.subBrandId}", None, op).status, 403)
    assertEquals(kit.request("GET", s"/v1/merchants/${r.subBrandId}/psp-credentials/DREAMZPAY?name=main", None, op).status, 403)
    assertEquals(kit.request("GET", "/v1/payments/cashier", None, kit.asOperator(level = "none", roles = "affiliate")).status, 403,
      "a staff token with neither a payments role nor a brand is refused, as before")
  }

  test("?reference= finds a payment by its exact reference, within the reader's sub-brands only") {
    val (a, b) = (slug(), slug())
    val mine   = kit.subBrand(brand = Some(a))
    val theirs = kit.subBrand(brand = Some(b))
    val pa = kit.payment(mine); val other = kit.payment(mine); val pb = kit.payment(theirs)
    assertEquals(kit.capture(pa).status, 200)
    val ra = row(pa); row(other); val rb = row(pb)
    val op = brandOperator(a)
    def byRef(ref: String, as: Seq[(String, String)] = op) =
      ids(kit.request("GET", s"/v1/payments/cashier?reference=${java.net.URLEncoder.encode(ref, "UTF-8")}", None, as))

    // the reader's sub-brands come from a view of its own, which may not yet hold `mine`
    kit.eventually("pa found by its id")(Option(byRef(ra.paymentId)).filter(_ == Set(pa)))
    assertEquals(byRef(ra.transactionId), Set(pa))
    assertEquals(byRef(ra.pspTransactionId), Set(pa))
    assertEquals(byRef(ra.paymentId.take(8)), Set.empty[String], "a reference is matched exactly, not as a search")
    // pb is found once the lookup holds it, so its absence for A's reader below is the scope, not a lag
    kit.eventually("a platform payments operator finds pb")(Option(byRef(rb.paymentId, kit.asOperator())).filter(_ == Set(pb)))
    assertEquals(byRef(rb.paymentId), Set.empty[String], "another brand's payment is not found")
  }

  test("payments operators keep exactly the access they had, a brand role beside theirs included") {
    val (a, b) = (slug(), slug())
    val mine   = kit.subBrand(brand = Some(a))
    val theirs = kit.subBrand(brand = Some(b))
    val pa = kit.payment(mine); val pb = kit.payment(theirs)
    Seq(pa, pb).foreach(row)
    // At sub-brand level for A's sub-brand, and holding brand_<b>: the payments level decides, not the brand.
    val op = kit.asOperator(level = "sub_brand", scopeId = mine.subBrandId, roles = s"resource_viewer,brand_$b")
    assertEquals(ids(kit.request("GET", "/v1/payments/cashier", None, op)), Set(pa))
    assertEquals(kit.request("GET", s"/v1/payments/$pa", None, op).status, 200)
    refusedOutOfScope(kit.request("GET", s"/v1/payments/$pb", None, op))
    refusedOutOfScope(kit.request("GET", s"/v1/payments/cashier/summary?merchantId=${theirs.subBrandId}", None, op))
    val platform = kit.request("GET", s"/v1/payments/$pb", None, kit.asOperator())
    assertEquals(platform.status, 200, platform.text)
    assertEquals(kit.request("GET", "/v1/payments/cashier/uncredited", None, kit.asOperator()).status, 200)
  }
