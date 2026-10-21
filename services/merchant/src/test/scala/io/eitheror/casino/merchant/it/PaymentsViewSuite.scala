package io.eitheror.casino.merchant.it

import io.eitheror.casino.merchant.payments.PaymentsView

/** The payments view (research R2): a captured deposit counted once for routing; uncredited listed until credited. */
class PaymentsViewSuite extends SharedKitSuite:

  test("a customer's deposit count is their captured payments at the sub-brand, once each") {
    val r        = kit.subBrand()
    val customer = s"acme:p-${java.util.UUID.randomUUID().toString.take(6)}"
    val a = kit.payment(r, customerId = Some(customer))
    val b = kit.payment(r, customerId = Some(customer))
    kit.payment(r, customerId = Some(customer)): Unit
    assertEquals(kit.capture(a).status, 200)
    assertEquals(kit.capture(b).status, 200)
    assertEquals(kit.capture(b).status, 200)
    val view = kit.views.forView(PaymentsView)
    kit.eventually("two captured")(Option(view.all(100_000).count(r => r.customerId == customer && r.status == "captured")).filter(_ == 2))
    assertEquals(PaymentsView.depositCount(kit.views, r.subBrandId, customer), 2L)
  }

  test("a captured payment not credited is listed until it is credited") {
    val r  = kit.subBrand()
    val id = kit.payment(r)
    kit.wallet.down = true
    assertEquals(kit.capture(id).status, 503)
    kit.eventually("listed")(Option(kit.request("GET", "/v1/payments/cashier/uncredited", None, kit.asOperator())).filter(_.text.contains(id)))
    kit.wallet.down = false
    kit.eventually("gone")(Option(kit.request("GET", "/v1/payments/cashier/uncredited", None, kit.asOperator())).filter(!_.text.contains(id)))
  }
