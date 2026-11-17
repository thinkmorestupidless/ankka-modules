package io.eitheror.casino.pspgateway.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.sessions.*

import scala.concurrent.duration.*

/**
 * The test payment through the route (spec 030 T2) against psp-stub: Digitarium's sandbox
 * answered, and nothing kept. No deposit session is claimed under the test's reference, `merchant`
 * is asked for the named credential only, and the provider's webhook for the test finds no session
 * and delivers no outcome to `merchant`.
 */
class TestPaymentItSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 5.minutes

  private lazy val kit = SharedKit.kit

  private val digitarium = PspStub.credentials(Provider.Digitarium) + ("environment" -> "production")

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    kit.merchant.namedCredential("sb-1", Provider.Digitarium.wire, "sandbox card", digitarium)

  private def testPayment(fields: (String, Any)*): Reply =
    val base = Vector[(String, Any)]("subBrandId" -> "sb-1", "psp" -> Provider.Digitarium.wire, "credentialName" -> "sandbox card")
    val keys = fields.map(_._1).toSet
    kit.request("POST", "/v1/psp/test-payment", Some(Json.render(Json.obj((base.filterNot(f => keys(f._1)) ++ fields)*))), kit.asMerchant)

  test("Digitarium's sandbox: accepted with the provider's page; no session, and its webhook delivers nothing") {
    kit.psp.control(Provider.Digitarium, "success")
    val before = kit.psp.received(Provider.Digitarium).size
    val r      = testPayment()
    assertEquals(r.status, 200, r.text)
    assertEquals((r.str("outcome"), r.str("method"), r.str("currency")), ("accepted", "BASIC_CARD", "USD"), r.text)
    assert(r.str("redirectUrl").nonEmpty, r.text)
    val reference = r.str("reference")
    assert(kit.psp.received(Provider.Digitarium).size > before, "the provider was asked")
    // The kit is shared with suites running beside this one: their reads are of the usable credential, by no name.
    assertEquals(kit.merchant.received.filter(r => r.path.contains("/psp-credentials/") && r.query.contains("name")).map(_.query("name")),
      Vector("sandbox card"))

    assert(!kit.deposit(reference).exists, "no deposit session is claimed")
    val indexed = kit.components.forKeyValueEntity(EntityId(ReferenceIndex.key(Provider.Digitarium, reference))).call(ReferenceIndex.get).invoke()
    assert(indexed.isEmpty, s"nothing is indexed: $indexed")

    val (status, _) = kit.psp.webhook(Provider.Digitarium, reference)
    assertEquals(status / 100, 2, "the webhook is answered as an unknown session is")
    Thread.sleep(1500)
    assert(!kit.deposit(reference).exists, "the webhook made no session")
    assertEquals(kit.merchant.deliveredTo(reference), Vector.empty, "no outcome reaches merchant")
  }

  test("a provider with no test mode is 501; merchant's other callers and a token are refused") {
    val r = testPayment("psp" -> Provider.DreamzPay.wire)
    assertEquals((r.status, r.str("error")), (501, "test_mode_unavailable"), r.text)
    val unity = testPayment("psp" -> Provider.UnityFinance.wire)
    assertEquals((unity.status, unity.str("error")), (501, "test_mode_unavailable"), unity.text)
    val body = Some(Json.render(Json.obj("subBrandId" -> "sb-1", "psp" -> "UNITYFINANCE", "credentialName" -> "sandbox card")))
    assertEquals(kit.request("POST", "/v1/psp/test-payment", body, kit.asStaff).status, 403)
    assertEquals(kit.request("POST", "/v1/psp/test-payment", body, kit.asService("payments", "ingest")).status, 403)
  }
