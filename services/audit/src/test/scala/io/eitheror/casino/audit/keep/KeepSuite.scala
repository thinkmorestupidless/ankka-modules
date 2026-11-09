package io.eitheror.casino.audit.keep

class KeepSuite extends munit.FunSuite:

  private val categories = Vector("money", "operator", "security", "system", "kyc", "responsible_gambling", "account")
  private val actors     = Vector("service", "system", "player", "operator", "affiliate", "agent", "platform")
  private val outcomes   = Vector("done", "refused", "failed", "accepted")

  test("FR-008a over every category × actor kind × outcome: only an unrefused money event no person caused is passed over") {
    for
      c <- categories
      a <- actors
      o <- outcomes
    do
      val expected =
        if c == "money" && (a == "service" || a == "system") && o != "refused" then Decision.PassOver else Decision.Keep
      assertEquals(Keep.decide(c, a, o), expected, s"$c × $a × $o")
  }

  test("a bet the wallet settles is passed over; an operator's adjustment and a refused bet are kept") {
    assertEquals(Keep.decide("money", "service", "done"), Decision.PassOver)
    assertEquals(Keep.decide("money", "operator", "done"), Decision.Keep)
    assertEquals(Keep.decide("money", "service", "refused"), Decision.Keep)
  }
