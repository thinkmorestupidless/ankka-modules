package io.eitheror.casino.pspgateway.providers

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.pspgateway.sessions.Provider

import java.time.Duration

/** FR-006, FR-009a, R10: what the gateway knows of a provider without asking it. */
class CatalogueSuite extends munit.FunSuite with LogCapturing:

  private def c(code: String) = Currency.unsafe(code)

  test("every provider is listed, with its deposit base URL setting") {
    assertEquals(Catalogue.entries.keySet, Provider.values.toSet)
    Provider.values.foreach(p => assert(Catalogue.baseUrlSettings.contains(Catalogue(p).depositUrl), p))
  }

  test("payouts are offered by Unity Finance, Cozeni, Morphosis and Gigadat only") {
    val payouts = Provider.values.filter(Catalogue(_).offers(Operation.Payout)).toSet
    assertEquals(payouts, Set(Provider.UnityFinance, Provider.Cozeni, Provider.Morphosis, Provider.Gigadat))
  }

  test("Interac outside CAD is unsupported_currency, both ways") {
    val d = Catalogue.check(Provider.Gigadat, Operation.Deposit, "INTERAC", c("USD"))
    assertEquals(d.left.map(_.error), Left("unsupported_currency"))
    assert(d.left.exists(_.message.contains("USD")), "the refusal names the currency")
    assertEquals(Catalogue.check(Provider.Gigadat, Operation.Payout, "ETO", c("EUR")).left.map(_.error), Left("unsupported_currency"))
    assertEquals(Catalogue.check(Provider.Gigadat, Operation.Deposit, "INTERAC", c("CAD")), Right(()))
  }

  test("a Digitarium three- or zero-decimal currency is unsupported_currency") {
    assertEquals(Catalogue.check(Provider.Digitarium, Operation.Deposit, "BASIC_CARD", c("KWD")).left.map(_.error), Left("unsupported_currency"))
    assertEquals(Catalogue.check(Provider.Digitarium, Operation.Deposit, "BASIC_CARD", c("JPY")).left.map(_.error), Left("unsupported_currency"))
    assertEquals(Catalogue.check(Provider.Digitarium, Operation.Deposit, "BASIC_CARD", c("EUR")), Right(()))
  }

  test("Morphosis payouts are USD only") {
    assertEquals(Catalogue.check(Provider.Morphosis, Operation.Payout, "USDT", c("EUR")).left.map(_.error), Left("unsupported_currency"))
    assertEquals(Catalogue.check(Provider.Morphosis, Operation.Payout, "USDT", c("USD")), Right(()))
    assertEquals(Catalogue.check(Provider.Morphosis, Operation.Deposit, "USDT", c("EUR")), Right(()))
  }

  test("a method a provider lacks is unsupported_method, naming the provider and the method") {
    val r = Catalogue.check(Provider.T365, Operation.Deposit, "VISA", c("EUR"))
    assertEquals(r.left.map(_.error), Left("unsupported_method"))
    assert(r.left.exists(m => m.message.contains("T365") && m.message.contains("VISA")))
    assertEquals(Catalogue.check(Provider.UnityFinance, Operation.Deposit, "PIX", c("EUR")).left.map(_.error), Left("unsupported_method"))
    assertEquals(Catalogue.check(Provider.T365, Operation.Deposit, "PIX", c("BRL")), Right(()))
  }

  test("a payout from a deposit-only provider is unsupported_method") {
    assertEquals(Catalogue.check(Provider.DreamzPay, Operation.Payout, "VISA", c("EUR")).left.map(_.error), Left("unsupported_method"))
  }

  test("T365 maps 16 method codes and Unity Finance its ten (with the legacy card names and the test card)") {
    assertEquals(Catalogue(Provider.T365).depositMethods.map(_.size), Some(16))
    val uf = Catalogue(Provider.UnityFinance).depositMethods.get
    assertEquals((uf -- Set("CARD_VISA", "CARD_MASTERCARD", "CARD_TEST")).size, 10)
  }

  test("BounceMoney's minimum is 1,500 minor units with the player's message") {
    assertEquals(Catalogue.belowMinimum(Provider.BounceMoney, 1499), Some("minimum deposit amount is 15.00"))
    assertEquals(Catalogue.belowMinimum(Provider.BounceMoney, 1500), None)
    assertEquals(Catalogue.belowMinimum(Provider.DreamzPay, 1), None)
  }

  test("only Unity Finance has a status to read; the review windows are Cozeni 24 h, Gigadat and Morphosis 5 days") {
    assertEquals(Provider.values.filter(Catalogue(_).hasStatus).toSet, Set(Provider.UnityFinance))
    assertEquals(Catalogue(Provider.Cozeni).reviewWindow, Some(Duration.ofHours(24)))
    assertEquals(Catalogue(Provider.Gigadat).reviewWindow, Some(Duration.ofDays(5)))
    assertEquals(Catalogue(Provider.Morphosis).reviewWindow, Some(Duration.ofDays(5)))
    assertEquals(Catalogue(Provider.UnityFinance).reviewWindow, None)
  }

  test("Digitarium's base URL follows the credential's environment; Unity Finance pays out on its API URL") {
    val prod = Credential(Map("environment" -> "production"))
    assertEquals(Catalogue.baseUrlSetting(Provider.Digitarium, Operation.Deposit, prod), Some("DIGITARIUM_PRODUCTION_URL"))
    assertEquals(Catalogue.baseUrlSetting(Provider.Digitarium, Operation.Deposit, Credential.empty), Some("DIGITARIUM_SANDBOX_URL"))
    assertEquals(Catalogue.baseUrlSetting(Provider.UnityFinance, Operation.Payout, Credential.empty), Some("UNITYFINANCE_API_URL"))
    assertEquals(Catalogue.baseUrlSetting(Provider.T365, Operation.Payout, Credential.empty), None)
  }

  test("the webhook and return paths are contracts/routes.md's") {
    assertEquals(Catalogue.webhookPath(Provider.DreamzPay, Map.empty), "/v1/webhooks/dreamzpay")
    assertEquals(Catalogue.webhookPath(Provider.Morphosis, Map("token" -> "t")), "/v1/webhooks/morphosis/deposit/t")
    assertEquals(Catalogue.payoutWebhookPath(Provider.UnityFinance, Map.empty), "/v1/webhooks/unityfinance/withdrawal")
    assertEquals(Catalogue.payoutWebhookPath(Provider.Gigadat, Map.empty), "/v1/webhooks/gigadat")
    assertEquals(Catalogue.returnPath(Provider.UnityFinance, "p1"), "/v1/psp/checkout/p1/return")
    assertEquals(Catalogue.returnPath(Provider.T365, "p1"), "/v1/psp/checkout/t365/p1/return")
  }

  test("a credential lacking a required key is named by key; Unity Finance's checkout id by method") {
    assertEquals(Catalogue.lacking(Provider.DreamzPay, "VISA", Credential(Map("memberId" -> "m", "totype" -> "t"))), Vector("secureKey"))
    assertEquals(Catalogue.lacking(Provider.UnityFinance, "visa", Credential(Map("signKey" -> "k", "checkoutId.VISA" -> "c"))), Vector.empty)
    assertEquals(Catalogue.lacking(Provider.UnityFinance, "SKRILL", Credential(Map("signKey" -> "k", "checkoutId.VISA" -> "c"))),
      Vector("checkoutId.SKRILL"))
    assertEquals(Catalogue.lacking(Provider.Morphosis, "USDT", Credential(Map("user" -> " ", "pass" -> "p"))), Vector("user"), "blank is absent")
    Provider.values.foreach(p => assert(Catalogue.requiredForDeposit(p, "VISA").nonEmpty, p))
  }
