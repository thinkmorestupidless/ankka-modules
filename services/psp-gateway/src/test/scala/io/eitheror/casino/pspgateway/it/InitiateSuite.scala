package io.eitheror.casino.pspgateway.it

import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.sessions.*

import java.util.UUID
import scala.concurrent.duration.*

/** US1, FR-001 to FR-008: an initiate for each of the nine providers through psp-stub, and its refusals. */
class InitiateSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 5.minutes

  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    Provider.values.foreach(kit.credential("sb-1", _))

  private def id() = UUID.randomUUID().toString

  Provider.values.foreach { provider =>
    test(s"${provider.wire}: the initiate answers where the player goes, and a repeat answers the same with no call") {
      kit.psp.control(provider, "pending")
      val paymentId = id()
      val before    = kit.psp.received(provider).size
      val first     = kit.initiate(paymentId, provider)
      assertEquals(first.status, 200, first.text)
      val crypto = provider == Provider.Cozeni || provider == Provider.Morphosis
      if crypto then
        assertEquals(first.str("redirectUrl"), "")
        assert(first.str("paymentAddress").nonEmpty, first.text)
      else assert(first.str("redirectUrl").nonEmpty, first.text)
      val expectedId = if provider == Provider.T365 then paymentId.replace("-", "") else paymentId
      assertEquals(first.str("pspTransactionId"), expectedId)
      assertEquals(kit.deposit(paymentId).stage, Stage.Initiated)

      val calls = kit.psp.received(provider).size
      assert(provider == Provider.DreamzPay || calls > before, s"${provider.wire} was called")
      val again = kit.initiate(paymentId, provider)
      assertEquals((again.status, Json.parse(again.text)), (200, Json.parse(first.text)))
      assertEquals(kit.psp.received(provider).size, calls, "a repeat calls no provider (FR-008)")
    }
  }

  test("no credential is 404 no_credentials naming provider and sub-brand, and no provider is called") {
    kit.merchant.removeCredential("sb-1", Provider.T365.wire)
    val before = kit.psp.received(Provider.T365).size
    val r      = kit.initiate(id(), Provider.T365)
    assertEquals((r.status, r.str("error")), (404, "no_credentials"), r.text)
    assert(r.str("message").contains("T365") && r.str("message").contains("sb-1"), r.text)
    assertEquals(kit.psp.received(Provider.T365).size, before)
  }

  test("a decimal amount is 400 bad_request; Interac in EUR 400 unsupported_currency; BounceMoney at 1,499 400 player_error") {
    assertEquals(kit.initiate(id(), Provider.DreamzPay, "amount" -> "25.00").str("error"), "bad_request")
    assertEquals(kit.initiate(id(), Provider.Gigadat, "currency" -> "EUR").str("error"), "unsupported_currency")
    val low = kit.initiate(id(), Provider.BounceMoney, "amount" -> "1499")
    assertEquals((low.status, low.str("error")), (400, "player_error"))
    assert(low.str("message").contains("15.00"), low.text)
  }

  test("terminalProperties carrying a value is never read: the credential comes from merchant, read once") {
    val paymentId = id()
    kit.psp.control(Provider.DreamzPay, "pending")
    val r = kit.initiate(paymentId, Provider.DreamzPay, "terminalProperties" -> Json.obj("secureKey" -> "from-the-body"))
    assertEquals(r.status, 200, r.text)
    assertEquals(kit.merchant.credentialReads.count(_.contains("DREAMZPAY")), 1)
  }

  test("a capture from psp-stub reaches merchant once, and the session reads resolved and delivered") {
    val paymentId = id()
    val r         = kit.initiate(paymentId, Provider.Digitarium)
    assertEquals(r.status, 200, r.text)
    kit.eventually("the capture is delivered") {
      Option(kit.merchant.deliveredTo(paymentId)).filter(_.nonEmpty)
    }
    assertEquals(kit.merchant.deliveredTo(paymentId).map(_.key), Vector(s"$paymentId/capture"))
    val s = kit.session(paymentId)
    assertEquals((s.str("state"), s.str("outcome")), ("resolved", "captured"), s.text)
    assertEquals(s.json.get("delivered").asBoolean, true)
  }
