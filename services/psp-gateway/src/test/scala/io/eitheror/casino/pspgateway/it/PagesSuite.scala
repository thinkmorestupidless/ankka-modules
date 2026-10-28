package io.eitheror.casino.pspgateway.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.pspgateway.deposits.Initiate
import io.eitheror.casino.pspgateway.http.{Form, Pages}
import io.eitheror.casino.pspgateway.sessions.*

import java.util.UUID
import scala.concurrent.duration.*

/** US6: the relay pages post the session's form to the provider; every return is the processing page and settles nothing. */
class PagesSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 5.minutes

  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    Provider.values.foreach(kit.credential("sb-1", _))

  private val Processing = "Your payment is being processed. Return to the casino to check your deposit status."

  private def initiated(provider: Provider): String =
    kit.psp.control(provider, "pending")
    val paymentId = UUID.randomUUID().toString
    val r         = kit.initiate(paymentId, provider)
    assertEquals(r.status, 200, r.text)
    paymentId

  private def page(path: String, method: String = "GET", body: Option[String] = None): Reply =
    kit.request(method, path, body, kit.asBrowser, contentType = "application/x-www-form-urlencoded")

  private def isProcessing(r: Reply): Unit =
    assertEquals(r.status, 200, r.text)
    assert(r.contentType.startsWith("text/html"), r.contentType)
    assert(r.text.contains(Processing), r.text)

  /** The hidden fields of a relay page, decoded as a browser would post them. */
  private def fields(html: String): Vector[(String, String)] =
    """<input type="hidden" name="([^"]*)" value="([^"]*)">""".r.findAllMatchIn(html).toVector.map(m =>
      unescape(m.group(1)) -> unescape(m.group(2)))

  private def unescape(s: String) =
    s.replace("&#43;", "+").replace("&#39;", "'").replace("&#34;", "\"").replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&")

  test("DreamzPay's relay page posts the session's form to {DREAMZPAY_BASE_URL}/transaction/Checkout, the checksum in it and the secure key not") {
    val paymentId = initiated(Provider.DreamzPay)
    val stored    = kit.deposit(paymentId).initiation.get.providerData
    val r         = page(s"/v1/psp/checkout/dreamzpay/$paymentId")
    assertEquals(r.status, 200, r.text)
    assert(r.contentType.startsWith("text/html"), r.contentType)
    assert(r.text.contains(s"""action="${Pages.escape(kit.psp.url(Provider.DreamzPay).stripSuffix("/"))}/transaction/Checkout""""), r.text)
    assertEquals(fields(r.text), Form.decode(stored(Initiate.RelayForm)).entries)
    assert(fields(r.text).exists(_._1 == "checksum"), r.text)
    assert(!r.text.contains(PspStub.credentials(Provider.DreamzPay)("secureKey")), "the secure key is on the page")
  }

  test("EuPaymentz's relay page posts the session's form to the address its POST redirect named") {
    val paymentId = initiated(Provider.EuPaymentz)
    val stored    = kit.deposit(paymentId).initiation.get.providerData
    val r         = page(s"/v1/psp/checkout/eupaymentz/$paymentId")
    stored.get("relayAction") match
      case Some(action) =>
        assert(r.text.contains(s"""action="${Pages.escape(action)}""""), r.text)
        assertEquals(fields(r.text), Form.decode(stored(Initiate.RelayForm)).entries)
      case None => isProcessing(r) // a GET redirect: the player was sent straight to EuPaymentz
  }

  test("a relay for an unknown session, or for another provider's, is the processing page") {
    isProcessing(page(s"/v1/psp/checkout/dreamzpay/${UUID.randomUUID()}"))
    val t365 = initiated(Provider.T365)
    isProcessing(page(s"/v1/psp/checkout/dreamzpay/$t365"))
    isProcessing(page(s"/v1/psp/checkout/eupaymentz/$t365"))
  }

  private val Returns = Vector("/v1/psp/checkout/%s/return", "/v1/psp/checkout/unityfinance/%s/return",
    "/v1/psp/checkout/t365/%s/return", "/v1/psp/checkout/eupaymentz/%s/return", "/v1/psp/checkout/gigadat/%s/return",
    "/v1/psp/checkout/bouncemoney/%s/return", "/v1/psp/checkout/digitarium/%s/return", "/v1/psp/checkout/dreamzpay/%s/return")

  test("every return route is the processing page and settles nothing, an unknown reference too") {
    val paymentId = initiated(Provider.UnityFinance)
    Returns.foreach { r =>
      isProcessing(page(r.format(paymentId) + "?status=success&ik_inv_st=success"))
      isProcessing(page(r.format("unknown-ref")))
    }
    isProcessing(page(s"/v1/psp/checkout/dreamzpay/$paymentId/return", "POST", Some("status=Y&checksum=x")))
    Thread.sleep(500)
    assertEquals(kit.deposit(paymentId).stage, Stage.Initiated)
    assert(kit.merchant.delivered.isEmpty, kit.merchant.delivered)
  }

  test("DreamzPay's return POST with the form its webhook would verify settles nothing (correction 8)") {
    val paymentId = initiated(Provider.DreamzPay)
    val form      = Form.decode(kit.deposit(paymentId).initiation.get.providerData(Initiate.RelayForm)).entries :+ ("status" -> "Y")
    isProcessing(page(s"/v1/psp/checkout/dreamzpay/$paymentId/return", "POST", Some(Form(form).encode)))
    Thread.sleep(500)
    assertEquals(kit.deposit(paymentId).stage, Stage.Initiated)
    assert(kit.merchant.deliveredTo(paymentId).isEmpty)
  }
