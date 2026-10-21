package io.eitheror.casino.merchant.wallet

import io.eitheror.casino.merchant.it.{DirectServices, FakeWallet}

/** contracts/wallet.md, row for row: which answers are credited, retried or final. */
class WalletClientSuite extends munit.FunSuite:

  private val w      = FakeWallet()
  private val client = WalletClient.http(DirectServices(Map("wallet" -> w.address)))
  override def afterAll(): Unit = w.stop()
  override def beforeEach(context: BeforeEach): Unit = w.reset()

  private def credit(id: String, amount: Long = 5000) = Credit("acme", "p1", "EUR", id, amount, "t-1", "DREAMZPAY", "VISA", s"psp-$id")

  test("a credit is keyed by the payment id and carries the provider's reference; a repeat moves the balance once") {
    assertEquals(client.credit(credit("pay-1")), CreditAnswer.Credited(Some("txn-pay-1")))
    assertEquals(client.credit(credit("pay-1")), CreditAnswer.Credited(Some("txn-pay-1")))
    assertEquals(w.balance("acme", "p1", "EUR"), 5000L)
    assertEquals(w.deposits.head.json.get.get("paymentReference").asText, "psp-pay-1")
    assert(w.deposits.head.path == "/v1/casino/brands/acme/wallets/p1/EUR/deposits")
  }

  test("OUTCOME_PENDING, a 5xx and no answer are retried") {
    w.pending = 1
    assert(client.credit(credit("pay-2")).isInstanceOf[CreditAnswer.Retry])
    w.down = true
    assert(client.credit(credit("pay-2")).isInstanceOf[CreditAnswer.Retry])
    assert(WalletClient.http(DirectServices(Map("wallet" -> "http://127.0.0.1:1"))).credit(credit("pay-2")).isInstanceOf[CreditAnswer.Retry])
  }

  for code <- Vector("WALLET_NOT_FOUND", "CURRENCY_MISMATCH", "EXCLUDED", "COOLING_OFF", "OVER_LIMIT") do
    test(s"$code is final, with its details") {
      w.refuse("pay-3", code, 409, "remaining" -> 1500L)
      client.credit(credit("pay-3")) match
        case CreditAnswer.Final(c, _, details) => assertEquals((c, details.get("remaining")), (code, Some("1500")))
        case other                             => fail(other.toString)
    }

  test("RG_UNAVAILABLE is retried, as a 5xx is") {
    w.refuse("pay-4", "RG_UNAVAILABLE", 503)
    assert(client.credit(credit("pay-4")).isInstanceOf[CreditAnswer.Retry])
  }

  test("an outcome is reported keyed by payment and outcome; a repeat is acknowledged and not first") {
    val r = OutcomeReport("acme", "pay-5", "failed", "p1", "DREAMZPAY", Some("t-1"), None, None, Some("05"), Some("Do not honour"))
    assertEquals(client.report(r), Right(true))
    assertEquals(client.report(r), Right(false))
    w.refuseReports = true
    assert(client.report(r).isLeft)
  }
