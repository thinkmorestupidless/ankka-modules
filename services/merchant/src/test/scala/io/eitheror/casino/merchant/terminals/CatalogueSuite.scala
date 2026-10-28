package io.eitheror.casino.merchant.terminals

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.merchant.terminals.catalogue.Catalogue

/** FR-009 and research R9: the original's catalogue, secrets marked, and its masked preview. */
class CatalogueSuite extends munit.FunSuite with LogCapturing:

  test("every gateway spec 005 keeps is listed, and ConnecX is not") {
    assertEquals(Catalogue.gateways.keySet, Set("DREAMZPAY", "UNITYFINANCE", "T365", "COZENI", "MORPHOSIS", "EUPAYMENTZ", "GIGADAT", "BOUNCEMONEY"))
    assertEquals(Catalogue.of("connecx").schemes, Vector.empty)
  }

  test("a gateway is found whatever its case; an unknown one is an empty catalogue") {
    assertEquals(Catalogue.of("dreamzpay").schemes.map(_.pspId), Vector("VISA", "MASTERCARD", "BANK_TRANSFER"))
    assertEquals(Catalogue.of("NOPE").gateway, "NOPE")
  }

  test("secret properties are marked; the rest are not") {
    val dz = Catalogue.of("DREAMZPAY").schemes.head.requiredProperties.map(p => p.key -> p.secret).toMap
    assertEquals(dz, Map("memberId" -> false, "totype" -> false, "secureKey" -> true, "terminalId" -> false))
    val bm = Catalogue.of("BOUNCEMONEY").schemes.head.requiredProperties
    assert(bm.exists(p => p.key == "privateKey" && p.secret && p.multiline))
  }

  test("a terminal's properties split into what the entity keeps and what the store keeps") {
    val (kept, secret) = Catalogue.split("DREAMZPAY", "VISA", Map("memberId" -> "m", "secureKey" -> "s", "supportedCurrencies" -> "EUR"))
    assertEquals(kept, Map("memberId" -> "m", "supportedCurrencies" -> "EUR"))
    assertEquals(secret, Map("secureKey" -> "s"))
  }

  test("required keys come from the scheme; a scheme it does not know requires nothing") {
    assertEquals(Catalogue.requiredKeys("UNITYFINANCE", "OPEN_BANKING"), Vector("signKey", "userId", "withdrawalApiKey", "accountId", "purseId"))
    assertEquals(Catalogue.requiredKeys("UNITYFINANCE", "CARD_VISA"), Vector.empty)
  }

  test("a preview reveals at most four characters, proportionally less of a short value, and never the length") {
    assertEquals(Catalogue.preview("sk_live_abcdef"), "sk_l********")
    assertEquals(Catalogue.preview("abc"), "a********")
    assertEquals(Catalogue.preview("ab"), "a********")
    assertEquals(Catalogue.preview("a"), "********")
    assertEquals(Catalogue.preview("a-very-long-secret-value").length, 12)
  }
