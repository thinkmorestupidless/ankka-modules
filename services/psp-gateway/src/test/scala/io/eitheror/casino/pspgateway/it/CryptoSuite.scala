package io.eitheror.casino.pspgateway.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.sessions.*

import java.util.UUID
import scala.concurrent.duration.*

/** US2, FR-005: Cozeni and Morphosis quote a destination; what arrives is captured, or failed when short. */
class CryptoSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 5.minutes

  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    Provider.values.foreach(kit.credential("sb-1", _))

  /** A quoted deposit the stub holds open: the payment id and the initiate's answer. */
  private def quoted(provider: Provider): (String, Reply) =
    kit.psp.control(provider, "pending")
    val id = UUID.randomUUID().toString
    val r  = kit.initiate(id, provider)
    assertEquals(r.status, 200, r.text)
    (id, r)

  private def capture(id: String): Delivered =
    kit.eventually(s"$id delivered")(kit.merchant.deliveredTo(id).headOption)

  private def field(d: Delivered, name: String): Option[String] = Json.parse(d.body).flatMap(Json.text(_)(name))

  Seq(Provider.Cozeni, Provider.Morphosis).foreach { provider =>
    test(s"${provider.wire}: the answer is a payment address, a coin amount to 8 places, a coin currency, a deadline and no redirect") {
      val (_, r) = quoted(provider)
      assertEquals(r.str("redirectUrl"), "")
      assert(r.str("paymentAddress").nonEmpty, r.text)
      assert(r.str("cryptoAmount").matches("""\d+(\.\d{1,8})?"""), r.text)
      assert(r.str("cryptoCurrency").nonEmpty, r.text)
      if provider == Provider.Cozeni then assert(r.str("validUntil").nonEmpty, r.text)
    }

    test(s"${provider.wire}: success captures with no variance; an overpayment captures with OVERPAYMENT") {
      val (a, _) = quoted(provider)
      kit.psp.webhook(provider, kit.stubReference(a, provider), "outcome" -> "success"): Unit
      val plain = capture(a)
      assertEquals((plain.key, field(plain, "variance")), (s"$a/capture", None))

      val (b, _) = quoted(provider)
      kit.psp.webhook(provider, kit.stubReference(b, provider), "outcome" -> "overpayment"): Unit
      val over = capture(b)
      // Cozeni reports an overpayment (`02`); Morphosis's `ac` does not say, and the original records none.
      if provider == Provider.Cozeni then
        assertEquals((over.key, field(over, "variance")), (s"$b/capture", Some(Variance.Overpayment)))
        assert(field(over, "receivedCryptoAmount").nonEmpty, over.body)
      else assertEquals((over.key, field(over, "variance")), (s"$b/capture", None))
    }
  }

  test("Cozeni: the initiate asks the rate first, then the payin") {
    val before = kit.psp.received(Provider.Cozeni).size
    quoted(Provider.Cozeni): Unit
    val paths = kit.psp.received(Provider.Cozeni).drop(before).map(r => Json.text(r)("path").getOrElse(""))
    assert(paths.size >= 2, paths)
    assert(paths.indexWhere(_.contains("rate")) < paths.indexWhere(p => !p.contains("rate")), paths)
  }

  test("Cozeni: an unvalued underpayment fails 03 with the variance; a valued one captures UNDERPAYMENT with the value") {
    val (a, _) = quoted(Provider.Cozeni)
    kit.psp.webhook(Provider.Cozeni, a, "outcome" -> "underpayment", "convertAmount" -> "0"): Unit
    val failed = capture(a)
    assertEquals((failed.key, field(failed, "failureCode")), (s"$a/fail", Some("03")))
    assert(field(failed, "receivedCryptoAmount").nonEmpty, failed.body)

    val (b, _) = quoted(Provider.Cozeni)
    kit.psp.webhook(Provider.Cozeni, b, "outcome" -> "underpayment", "convertAmount" -> "20.00"): Unit
    val valued = capture(b)
    assertEquals((valued.key, field(valued, "variance")), (s"$b/capture", Some(Variance.Underpayment)))
    assertEquals(field(valued, "receivedConvertAmount"), Some("20.00"))
    assert(field(valued, "receivedConvertCurrency").nonEmpty, valued.body)
  }

  test("Morphosis: a webhook on another session's token is acknowledged and moves neither") {
    val (a, _) = quoted(Provider.Morphosis)
    val (b, _) = quoted(Provider.Morphosis)
    val bToken = kit.stubReference(b, Provider.Morphosis)
    val aId    = kit.deposit(a).initiation.flatMap(_.reference).get
    val r = kit.request("POST", s"/v1/webhooks/morphosis/deposit/$bToken",
      Some(Json.render(Json.obj("id" -> aId, "status" -> "ac", "coin_amount" -> "1000"))), kit.asProvider)
    assertEquals(r.status, 200, r.text)
    Thread.sleep(500)
    assertEquals((kit.deposit(a).stage, kit.deposit(b).stage), (Stage.Initiated, Stage.Initiated))
    assert(kit.merchant.delivered.isEmpty)
  }
