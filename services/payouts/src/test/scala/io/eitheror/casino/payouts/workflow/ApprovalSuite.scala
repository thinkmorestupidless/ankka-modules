package io.eitheror.casino.payouts.workflow

import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.request.{Holder, Method, Payee}
import io.eitheror.casino.payouts.workflow.Approval.Decision

/** `resolveApprovalOutcome`'s branches (R7): each gateway, and each missing field leading to manual payout. */
class ApprovalSuite extends munit.FunSuite:
  private def terminal(gateway: String, scheme: String = "EUBANKING", withdraws: Boolean = true, subBrand: String = "sb-1") =
    PayoutTerminal("t-1", subBrand, gateway, scheme, withdraws)
  private val p      = Personal[String]
  private val names  = Some((p("Ana"), p("Silva")))
  private val email  = Some(p("ana@example.test"))
  private val bank   = Payee(destinationId = Some("d-1"), holder = Some(Holder(p("Ana"), p("Silva"))), iban = Some(p("DE89370400440532013000")))
  private val crypto = Payee(walletAddress = Some(p("0xabc")), coin = Some("USDT"))
  private val eto    = Payee(method = Some(Method.Eto), mobile = Some(p("+15551234567")))
  private val ach    = Payee(method = Some(Method.Ach), account = Some(p("123")), institution = Some(p("001")), transit = Some(p("45678")))

  private def decide(t: PayoutTerminal, payee: Payee, n: Option[(Personal[String], Personal[String])] = names,
      e: Option[Personal[String]] = email) = Approval.decide(t, payee, "p-1", n, e)
  private def details(d: Decision): Map[String, String] = d match
    case Decision.Automated(payout) => payout.details.view.mapValues(Personal.reveal).toMap
    case other                      => fail(s"not automated: $other")

  test("Unity Finance: the destination's names and IBAN and the player's email"):
    val d = decide(terminal("UNITYFINANCE"), bank)
    assertEquals(details(d), Map("firstName" -> "Ana", "lastName" -> "Silva", "iban" -> "DE89370400440532013000", "email" -> "ana@example.test"))
    d match
      case Decision.Automated(payout) => assertEquals((payout.subBrandId, payout.method, payout.customerId), ("sb-1", "EUBANKING", "p-1"))
      case _                          => fail("automated")

  test("Unity Finance without a destination, or without an email, is manual"):
    assertEquals(decide(terminal("unityfinance"), Payee()), Decision.Manual("destination_missing"))
    assertEquals(decide(terminal("unityfinance"), bank, e = None), Decision.Manual("email_missing"))
    assertEquals(decide(terminal("unityfinance"), bank, e = Some(p(" "))), Decision.Manual("email_missing"))

  test("Cozeni and Morphosis: the coin and the address; the terminal's scheme when no coin was named"):
    assertEquals(details(decide(terminal("COZENI"), crypto)), Map("coin" -> "USDT", "address" -> "0xabc"))
    assertEquals(details(decide(terminal("morphosis", scheme = "BTC"), crypto.copy(coin = None))), Map("coin" -> "BTC", "address" -> "0xabc"))

  test("Cozeni and Morphosis without an address are manual"):
    assertEquals(decide(terminal("cozeni"), Payee()), Decision.Manual("wallet_address_missing"))
    assertEquals(decide(terminal("morphosis"), Payee()), Decision.Manual("wallet_address_missing"))

  test("Gigadat ETO: the mobile, the email and the player's names; ACH: the three bank numbers and the names"):
    assertEquals(details(decide(terminal("GIGADAT"), eto)),
      Map("mobile" -> "+15551234567", "email" -> "ana@example.test", "firstName" -> "Ana", "lastName" -> "Silva"))
    assertEquals(details(decide(terminal("gigadat"), ach)),
      Map("account" -> "123", "institution" -> "001", "transit" -> "45678", "firstName" -> "Ana", "lastName" -> "Silva"))

  test("Gigadat without a method, ETO without a mobile, ACH missing any bank number are manual"):
    assertEquals(decide(terminal("gigadat"), Payee()), Decision.Manual("payout_method_missing"))
    assertEquals(decide(terminal("gigadat"), eto.copy(mobile = None)), Decision.Manual("mobile_missing"))
    for missing <- Vector(ach.copy(account = None), ach.copy(institution = None), ach.copy(transit = None)) do
      assertEquals(decide(terminal("gigadat"), missing), Decision.Manual("bank_fields_missing"))

  test("a terminal without withdrawal, without a sub-brand, or on another gateway is manual"):
    assertEquals(decide(terminal("unityfinance", withdraws = false), bank), Decision.Manual("terminal_not_withdrawal"))
    assertEquals(decide(terminal("unityfinance", subBrand = ""), bank), Decision.Manual("no_sub_brand"))
    assertEquals(decide(terminal("dreamzpay"), bank), Decision.Manual("no_automated_payout"))
