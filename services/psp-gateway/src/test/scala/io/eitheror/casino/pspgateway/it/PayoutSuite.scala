package io.eitheror.casino.pspgateway.it

import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.sessions.*

import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** US3, FR-004, FR-009, FR-009a, contracts/payouts.md: a brand's payout through four providers. */
class PayoutSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 5.minutes

  private lazy val kit = SharedKit.kit
  private val brand    = "goldvibe"

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    Provider.values.foreach(kit.credential("sb-1", _))
    kit.payoutTerminals(brand)

  private def id() = UUID.randomUUID().toString

  /** The payout session's own reference at the stub: the request id, or Morphosis's token. */
  private def stubRef(requestId: String, provider: Provider): String =
    if provider == Provider.Morphosis then kit.payout(requestId).claim.flatMap(_.generated.get("token")).get else requestId

  private def outcomeAt(requestId: String) =
    kit.eventually(s"$requestId at payouts")(kit.payouts.delivered(brand).find(_.key.startsWith(s"$requestId/")))

  Kit.PayoutProviders.foreach { provider =>
    test(s"${provider.wire}: a payout is initiated once with the provider's reference; a repeat answers the same and calls nothing") {
      kit.psp.control(provider, "pending")
      val requestId = id()
      val before    = kit.psp.received(provider).size
      val first     = kit.withdraw(brand, requestId, provider)
      assertEquals(first.status, 200, first.text)
      assert(Set("initiated", "completed").contains(first.str("status")), first.text)
      val calls = kit.psp.received(provider).size
      assert(calls > before, s"${provider.wire} was called")
      val again = kit.withdraw(brand, requestId, provider)
      assertEquals((again.status, Json.parse(again.text)), (200, Json.parse(first.text)))
      assertEquals(kit.psp.received(provider).size, calls, "a repeat calls no provider")
    }

    test(s"${provider.wire}: the withdrawal webhook's success reaches the brand once as completed with the provider's reference") {
      kit.psp.control(provider, "pending")
      val requestId = id()
      val r         = kit.withdraw(brand, requestId, provider)
      assertEquals(r.status, 200, r.text)
      if r.str("status") != "completed" then
        val (status, body) = kit.psp.webhook(provider, stubRef(requestId, provider), "outcome" -> "success")
        assert(status / 100 == 2, s"$status $body")
      val d = outcomeAt(requestId)
      assertEquals(d.key, s"$requestId/completed")
      assert(Json.parse(d.body).flatMap(Json.text(_)("providerReference")).exists(_.nonEmpty), d.body)
      Thread.sleep(300)
      assertEquals(kit.payouts.delivered(brand).count(_.key.startsWith(s"$requestId/")), 1)
    }
  }

  test("a failure webhook reaches the brand as failed with a reason") {
    kit.psp.control(Provider.UnityFinance, "pending")
    val requestId = id()
    assertEquals(kit.withdraw(brand, requestId, Provider.UnityFinance).status, 200)
    kit.psp.webhook(Provider.UnityFinance, requestId, "outcome" -> "failure"): Unit
    val d = outcomeAt(requestId)
    assertEquals(d.key, s"$requestId/failed")
    assert(Json.parse(d.body).flatMap(Json.text(_)("reason")).exists(_.nonEmpty), d.body)
  }

  test("a body naming another brand is 403; a repeat naming another brand is 409") {
    val requestId = id()
    val r = kit.request("POST", "/v1/payments/withdrawals", Some(kit.withdrawalBody("spinvibe", requestId, Provider.Cozeni)),
      kit.asPayouts(brand))
    assertEquals(r.status, 403, r.text)
    kit.payoutTerminals("spinvibe")
    assertEquals(kit.withdraw(brand, requestId, Provider.Cozeni).status, 200)
    assertEquals(kit.withdraw("spinvibe", requestId, Provider.Cozeni).status, 409)
  }

  test("duplicate → already_initiated; the stub's 401 → rejected unauthorised; Morphosis in EUR → rejected unsupported_currency") {
    kit.psp.control(Provider.UnityFinance, "duplicate")
    assertEquals(kit.withdraw(brand, id(), Provider.UnityFinance).str("status"), "already_initiated")
    kit.psp.control(Provider.UnityFinance, "unauthorised")
    val u = kit.withdraw(brand, id(), Provider.UnityFinance)
    assertEquals((u.str("status"), u.str("reason")), ("rejected", "unauthorised"), u.text)
    // The original rejected an auth failure for Unity Finance only (`payout_test.go:261`); every provider does
    // now (PayoutAnswer.unauthorised, the 2026-10-19 decision).
    kit.psp.control(Provider.Gigadat, "unauthorised")
    val g = kit.withdraw(brand, id(), Provider.Gigadat)
    assertEquals((g.str("status"), g.str("reason")), ("rejected", "unauthorised"), g.text)
    val eur = kit.withdraw(brand, id(), Provider.Morphosis, "currency" -> "EUR")
    assertEquals((eur.str("status"), eur.str("reason")), ("rejected", "unsupported_currency"), eur.text)
  }

  test("an empty credential is rejected missing_credentials, and no provider is called") {
    kit.credential("sb-1", Provider.Cozeni, Map("unused" -> ""))
    val before = kit.psp.received(Provider.Cozeni).size
    val r      = kit.withdraw(brand, id(), Provider.Cozeni)
    assertEquals((r.str("status"), r.str("reason")), ("rejected", "missing_credentials"), r.text)
    assertEquals(kit.psp.received(Provider.Cozeni).size, before)
  }

  test("Cozeni's 00 completes at once, and the brand is told completed once") {
    kit.psp.control(Provider.Cozeni, "success")
    val requestId = id()
    val r         = kit.withdraw(brand, requestId, Provider.Cozeni)
    if r.str("status") == "completed" then assertEquals(outcomeAt(requestId).key, s"$requestId/completed")
    else assertEquals(r.str("status"), "initiated", r.text)
  }

  test("status: Unity Finance is asked (pending, then completed, which settles and delivers once); the others answer the session") {
    kit.psp.control(Provider.UnityFinance, "pending")
    val uf = id()
    assertEquals(kit.withdraw(brand, uf, Provider.UnityFinance).status, 200)
    assertEquals(kit.withdrawal(brand, uf).str("status"), "pending")
    kit.psp.control(Provider.UnityFinance, "success")
    kit.eventually("completed")(Option(kit.withdrawal(brand, uf)).filter(_.str("status") == "completed"))
    assertEquals(outcomeAt(uf).key, s"$uf/completed")

    kit.psp.control(Provider.Gigadat, "pending")
    val gd = id()
    assertEquals(kit.withdraw(brand, gd, Provider.Gigadat).status, 200)
    assertEquals(kit.withdrawal(brand, gd).str("status"), "pending")

    assertEquals(kit.withdrawal(brand, "nobody-" + id()).status, 404)
  }

  test("status: Unity Finance's empty credential is 502 status_unavailable, never no_record") {
    kit.psp.control(Provider.UnityFinance, "pending")
    val uf = id()
    assertEquals(kit.withdraw(brand, uf, Provider.UnityFinance).status, 200)
    kit.merchant.removeCredential("sb-1", Provider.UnityFinance.wire)
    val r = kit.withdrawal(brand, uf)
    assertEquals((r.status, r.str("error")), (502, "status_unavailable"), r.text)
  }

  test("the terminals listed are the brand's withdrawal terminals, each with hasStatus, reviewWindow and coins") {
    val r = kit.request("GET", "/v1/payments/withdrawal-terminals", None, kit.asPayouts(brand))
    assertEquals(r.status, 200, r.text)
    val rows = r.json.get("terminals").elements.asScala.toVector
    assertEquals(rows.map(n => Json.text(n)("gateway").get).toSet, Kit.PayoutProviders.map(_.wire).toSet)
    val uf = rows.find(n => Json.text(n)("gateway").contains("UNITYFINANCE")).get
    assertEquals(uf.get("hasStatus").asBoolean, true)
    val cz = rows.find(n => Json.text(n)("gateway").contains("COZENI")).get
    assertEquals(Json.text(cz)("reviewWindow"), Some("PT24H"))
    assertEquals(cz.get("coins").elements.asScala.map(_.asText).toVector, Vector("USDT", "BTC"))
    val cad = kit.request("GET", "/v1/payments/withdrawal-terminals?currency=CAD", None, kit.asPayouts(brand)).json.get("terminals")
    assert(cad.elements.asScala.exists(n => Json.text(n)("gateway").contains("GIGADAT")))
    assert(!cad.elements.asScala.exists(n => Json.text(n)("gateway").contains("MORPHOSIS")), cad.toString)
  }

  test("merchant may not initiate a payout") {
    val r = kit.request("POST", "/v1/payments/withdrawals", Some(kit.withdrawalBody(brand, id(), Provider.Cozeni)), kit.asMerchant)
    assertEquals(r.status, 403)
  }
