package io.eitheror.casino.pspgateway.it

import io.eitheror.casino.pspgateway.sessions.*

import java.util.UUID
import scala.concurrent.duration.*

/** US1, FR-010 to FR-014, R8: each provider's webhook from psp-stub, verified, settled once and delivered. */
class WebhookSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 5.minutes

  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    Provider.values.foreach(kit.credential("sb-1", _))

  /** An initiated session the stub holds open, so the test decides when its webhook goes. */
  private def held(provider: Provider): String =
    kit.psp.control(provider, "pending")
    val paymentId = UUID.randomUUID().toString
    val r         = kit.initiate(paymentId, provider)
    assertEquals(r.status, 200, r.text)
    kit.relay(paymentId).foreach(s => assert(s / 100 == 2 || s / 100 == 3, s"the relay form was refused: $s"))
    paymentId

  private def settled(paymentId: String): Outcome =
    kit.eventually(s"$paymentId settled")(kit.deposit(paymentId).outcome)

  private def deliveredTo(paymentId: String) =
    kit.eventually(s"$paymentId delivered")(Option(kit.merchant.deliveredTo(paymentId)).filter(_.nonEmpty))

  Provider.values.foreach { provider =>
    test(s"${provider.wire}: a success captures once, reaches merchant once, and the same webhook again moves nothing") {
      val paymentId = held(provider)
      val ref       = kit.stubReference(paymentId, provider)
      val (status, answer) = kit.psp.webhook(provider, ref, "outcome" -> "success")
      assertEquals(status, 200, answer.toString)
      assert(settled(paymentId).isInstanceOf[Outcome.Captured], kit.deposit(paymentId).toString)
      assertEquals(deliveredTo(paymentId).map(_.key), Vector(s"$paymentId/capture"))

      kit.psp.webhook(provider, ref, "outcome" -> "success"): Unit
      Thread.sleep(500)
      assertEquals(kit.merchant.deliveredTo(paymentId).size, 1, "a repeat settles nothing (SC-002)")
    }

    test(s"${provider.wire}: a failure is one fail call with the provider's reason") {
      val paymentId = held(provider)
      kit.psp.webhook(provider, kit.stubReference(paymentId, provider), "outcome" -> "failure"): Unit
      assert(settled(paymentId).isInstanceOf[Outcome.Failed], kit.deposit(paymentId).toString)
      val d = deliveredTo(paymentId)
      assertEquals(d.map(_.key), Vector(s"$paymentId/fail"))
      assert(d.head.body.contains("failureReason"), d.head.body)
    }
  }

  test("an open status leaves the session initiated and merchant uncalled") {
    val paymentId = held(Provider.Digitarium)
    kit.psp.webhook(Provider.Digitarium, paymentId, "outcome" -> "pending"): Unit
    Thread.sleep(500)
    assertEquals(kit.deposit(paymentId).stage, Stage.Initiated)
    assert(kit.merchant.deliveredTo(paymentId).isEmpty)
  }

  Seq(Provider.BounceMoney, Provider.Digitarium).foreach { provider =>
    test(s"${provider.wire}: an unsigned webhook is 400 and settles nothing") {
      val paymentId        = held(provider)
      val (status, answer) = kit.psp.webhook(provider, paymentId, "outcome" -> "unsigned")
      assertEquals(status, 400, answer.toString)
      Thread.sleep(300)
      assertEquals(kit.deposit(paymentId).stage, Stage.Initiated)
    }
  }

  test("a credential that cannot be read is 502 for BounceMoney and acknowledged for DreamzPay, and settles nothing") {
    val bm = held(Provider.BounceMoney)
    val dz = held(Provider.DreamzPay)
    kit.merchant.removeCredential("sb-1", Provider.BounceMoney.wire)
    kit.merchant.removeCredential("sb-1", Provider.DreamzPay.wire)
    assertEquals(kit.psp.webhook(Provider.BounceMoney, bm, "outcome" -> "success")._1, 502)
    assertEquals(kit.psp.webhook(Provider.DreamzPay, dz, "outcome" -> "success")._1, 200)
    assertEquals((kit.deposit(bm).stage, kit.deposit(dz).stage), (Stage.Initiated, Stage.Initiated))
  }

  test("a webhook for a reference the gateway never saw is acknowledged, and nothing is delivered") {
    val r = kit.request("POST", "/v1/webhooks/digitarium", Some("""{"referenceId":"nobody","state":"COMPLETED"}"""), kit.asProvider)
    assert(r.status / 100 == 2 || r.status == 400, s"${r.status} ${r.text}")
    assert(kit.merchant.delivered.isEmpty)
  }
