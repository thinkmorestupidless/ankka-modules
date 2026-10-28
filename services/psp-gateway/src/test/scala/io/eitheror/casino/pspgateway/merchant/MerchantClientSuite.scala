package io.eitheror.casino.pspgateway.merchant

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.pspgateway.Scripted
import io.eitheror.casino.pspgateway.providers.Credential
import io.eitheror.casino.pspgateway.sessions.Provider

/** Research R7, contracts/merchant.md §4: what the gateway reads from `merchant`, and how each answer is read. */
class MerchantClientSuite extends munit.FunSuite with LogCapturing:

  private def client(status: Int, body: String) =
    val s = Scripted(_ => Some(status -> body))
    (s, MerchantClient.http(s, "merchant"))

  test("a credential is read by sub-brand and the provider in the catalogue's upper case") {
    val (s, m) = client(200, """{"provider":"DREAMZPAY","credentials":{"memberId":"m1","secureKey":"k","n":3}}""")
    assertEquals(m.credential("sb 1", Provider.DreamzPay), Right(Credential(Map("memberId" -> "m1", "secureKey" -> "k", "n" -> "3"))))
    assertEquals(s.calls.map(c => (c.project, c.name, c.method, c.path)).toVector,
      Vector(("payments", "merchant", "GET", "/internal/v1/sub-brands/sb%201/psp-credentials/DREAMZPAY")))
  }

  test("404 is none; 503, another status, no answer and an unreadable body are unavailable") {
    assertEquals(client(404, """{"error":"NOT_FOUND"}""")._2.credential("sb", Provider.T365), Left(Unread.None))
    assert(client(503, """{"error":"UNAVAILABLE"}""")._2.credential("sb", Provider.T365).left.exists(_.isInstanceOf[Unread.Unavailable]))
    assert(client(500, "")._2.credential("sb", Provider.T365).left.exists(_.isInstanceOf[Unread.Unavailable]))
    assert(client(200, "not json")._2.credential("sb", Provider.T365).left.exists(_.isInstanceOf[Unread.Unavailable]))
    assert(MerchantClient.http(Scripted(_ => None), "merchant").credential("sb", Provider.T365).left.exists(_.isInstanceOf[Unread.Unavailable]))
  }

  test("a credential's values are named in no description of it") {
    val (_, m) = client(200, """{"credentials":{"secureKey":"s3cr3t-value"}}""")
    val c = m.credential("sb", Provider.DreamzPay)
    assert(!c.toString.contains("s3cr3t-value"), c.toString)
  }

  test("a terminal's properties, secrets merged as merchant serves them") {
    val (s, m) = client(200, """{"properties":{"supportedCurrencies":"BTC,USDT","apiKey":"x"}}""")
    assertEquals(m.terminalProperties("t-1").map(_.get("supportedCurrencies")), Right(Some("BTC,USDT")))
    assertEquals(s.calls.head.path, "/internal/v1/terminals/t-1/properties")
  }

  test("a brand's terminals, the 004 follow-up's listing") {
    val (s, m) = client(200, """{"terminals":[{"id":"t-1","subBrandId":"sb-1","gateway":"COZENI","scheme":"USDT","name":"Cozeni",
      "status":"active","supportsDeposit":true,"supportsWithdrawal":true,"requiresPayoutDestination":true,
      "properties":{"supportedCurrencies":"USDT"}},{"name":"no id"}]}""")
    assertEquals(m.brandTerminals("goldvibe"), Right(Vector(Terminal("t-1", "sb-1", "COZENI", "USDT", "Cozeni", "active", true, true, true,
      Map("supportedCurrencies" -> "USDT")))))
    assertEquals(s.calls.head.path, "/internal/v1/brands/goldvibe/terminals")
  }
