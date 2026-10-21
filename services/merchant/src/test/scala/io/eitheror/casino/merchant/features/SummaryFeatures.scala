package io.eitheror.casino.merchant.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.hierarchy.*
import io.eitheror.casino.merchant.it.{FakeGateway, Reply}
import io.eitheror.casino.merchant.payments.Status

import scala.jdk.CollectionConverters.*

/** features/payments/summary.feature (US5: FR-029), through the backoffice routes. */
class SummaryFeatures extends MerchantFeatures("summary.feature"):

  private var listed: Reply = null
  private var captured: Vector[String] = Vector.empty
  private var operator: Seq[(String, String)] = Nil
  private var target = ""

  Given("a sub-brand with three captured, one failed and one expired payment") { () =>
    ready = kit.subBrand()
    shortDeadline(2000.millisFor)
    captured = (1 to 3).toVector.map { i =>
      val id = kit.payment(ready, amount = 1000L * i)
      assertEquals(kit.capture(id).status, 200)
      Thread.sleep(5)
      id
    }
    val failed = kit.payment(ready)
    assertEquals(kit.fail(failed, "failureCode" -> "05").status, 200)
    val expired = kit.payment(ready)
    kit.gateway.session(expired, FakeGateway.Session.Resolved)
    kit.eventually("expired")(Option(kit.paymentState(expired)).filter(_.status == Status.Expired))
    kit.eventually("all five rows")(Option(kit.request("GET", s"/v1/payments/cashier?merchantId=${ready.subBrandId}", None, kit.asOperator()))
      .filter { r =>
        val c = r.json.get("statusCounts")
        (c.get("captured").asInt, c.get("failed").asInt, c.get("expired").asInt) == (3, 1, 1)
      })
  }

  extension (i: Int) private def millisFor: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.FiniteDuration(i.toLong, "millis")

  When("an operator lists its payments filtered to {string}") { (status: String) =>
    listed = kit.request("GET", s"/v1/payments/cashier?merchantId=${ready.subBrandId}&status=$status", None, kit.asOperator())
  }
  Then("three rows are listed newest first with amount, currency, status, payment provider and the provider's reference") { () =>
    assertEquals(listed.status, 200, listed.text)
    val rows = listed.json.get("payments").elements.asScala.toVector
    assertEquals(rows.map(_.textOr("paymentId", "")), captured.reverse)
    val newest = rows.head
    assertEquals((newest.textOr("amount", ""), newest.long("amountCents"), newest.textOr("currency", ""), newest.textOr("status", ""), newest.textOr("psp", "")),
      ("30.00", Some(3000L), "EUR", "captured", "DREAMZPAY"))
    assertEquals(newest.textOr("pspTransactionId", ""), s"psp-${captured.last}")
  }
  Then("the counts by status say three, one and one") { () =>
    val c = listed.json.get("statusCounts")
    assertEquals((c.get("captured").asInt, c.get("failed").asInt, c.get("expired").asInt), (3, 1, 1))
  }

  When("an operator reads its summary") { () =>
    listed = kit.request("GET", s"/v1/payments/cashier/summary?merchantId=${ready.subBrandId}", None, kit.asOperator())
  }
  Then("the captured volume per currency is the sum of the three in minor units") { () =>
    val v = listed.json.get("capturedVolume").get(0)
    assertEquals((v.textOr("currency", ""), v.long("amountCents"), v.textOr("amount", "")), ("EUR", Some(6000L), "60.00"))
  }
  Then("the daily series and the series per payment provider count them") { () =>
    val daily = listed.json.get("daily").elements.asScala.toVector
    assertEquals((daily.map(_.get("total").asInt).sum, daily.map(_.get("captured").asInt).sum), (5, 3))
    val psp = listed.json.get("psps").get(0)
    assertEquals((psp.textOr("psp", ""), psp.get("total").asInt, psp.get("captured").asInt), ("DREAMZPAY", 5, 3))
  }
  Then("a captured row with no amount is counted separately rather than summed") { () =>
    assertEquals(listed.json.get("capturedWithoutAmount").asInt, 0, "amounts are refused at the session unless positive, so none is missing")
  }

  Given("an operator at the level of one company") { () =>
    ready = kit.subBrand()
    operator = kit.asOperator(level = "company", scopeId = ready.companyId, roles = "resource_finance")
    target = kit.subBrand().subBrandId
  }
  When("they list the payments of a sub-brand of another company") { () =>
    listed = kit.request("GET", s"/v1/payments/cashier?merchantId=$target", None, operator)
  }
  Then("they are refused") { () => assertEquals((listed.status, listed.str("error")), (403, "scope_violation")) }

  Given("a sub-brand with no parent") { () =>
    target = s"orphan-${java.util.UUID.randomUUID().toString.take(8)}"
    kit.components.forEventSourcedEntity(EntityId(target)).call(SubBrand.create)
      .invoke(CreateSubBrand("", "", "Orphan", SubBrandType.Payments, None, Actor.operator("kit"), kit.now())): Unit
    assertEquals(kit.request("GET", s"/v1/payments/cashier?merchantId=$target", None, kit.asOperator()).status, 200)
  }
  When("an operator below the installation level lists its payments") { () =>
    val r = kit.subBrand()
    listed = kit.request("GET", s"/v1/payments/cashier?merchantId=$target", None, kit.asOperator(level = "holding", scopeId = r.holdingId, roles = "resource_owner"))
  }
