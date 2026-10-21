package io.eitheror.casino.merchant.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.merchant.cashier.{Session, SessionsByCustomer}
import io.eitheror.casino.merchant.payments.{PaymentCustomer, PaymentsView}

import scala.concurrent.duration.*

/** FR-032: a brand's players erases a player's customer fields; another brand's cannot. */
class ErasureSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds
  private var kit: Kit = null
  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()

  test("the brand's players erases the customer's payment records and live sessions; the payment's journal is untouched") {
    val r        = kit.subBrand(brand = Some("goldvibe"))
    val customer = "goldvibe:p-erase"
    val paid     = kit.payment(r, customerId = Some(customer))
    val open     = kit.session(r, customerId = Some(customer)).str("session_token")
    kit.eventually("indexed")(Option(kit.views.forView(PaymentsView).all(10_000).filter(_.customerId == customer)).filter(_.nonEmpty))
    kit.eventually("sessions indexed")(Option(kit.views.forView(SessionsByCustomer).all(10_000).filter(_.customerId == customer)).filter(_.size == 2))
    val other = kit.request("POST", s"/erasure/players/$customer", None, kit.asService("spinvibe", "players"))
    assertEquals(other.status, 403)
    val r1 = kit.request("POST", s"/erasure/players/$customer", None, kit.asService("goldvibe", "players"))
    assertEquals((r1.status, r1.json.get("confirmed").asBoolean, r1.json.get("payments").asInt), (200, true, 1), r1.text)
    assertEquals(kit.components.forKeyValueEntity(EntityId(paid)).call(PaymentCustomer.get).invoke().customer.email, None)
    assert(!kit.components.forKeyValueEntity(EntityId(open)).call(Session.get).invoke().exists)
    assertEquals(kit.paymentState(paid).opened.map(_.customerId), Some(customer), "the payment keeps the player id, which is not personal")
  }

  test("a service that is not players is refused") {
    assertEquals(kit.request("POST", "/erasure/players/goldvibe:p1", None, kit.asService("goldvibe", "wallet")).status, 403)
  }
