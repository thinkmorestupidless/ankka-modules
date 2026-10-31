package io.eitheror.casino.payouts.terminals

/** The original's `WithdrawalTerminalEligibility` cases (R5), and SC-007's stable order. */
class EligibilitySuite extends munit.FunSuite:
  private def t(id: String, name: String = "", scheme: String = "EUBANKING", gateway: String = "unityfinance",
      active: Boolean = true, withdraws: Boolean = true) =
    Terminal(id, "sb-1", if name.isEmpty then id else name, scheme, gateway, active, withdraws, false)

  test("an active terminal that supports withdrawal is offered"):
    assertEquals(Eligibility.eligible(Vector(t("a")), Map.empty).map(_.id), Vector("a"))

  test("an inactive terminal, or one without withdrawal, is not offered"):
    assertEquals(Eligibility.eligible(Vector(t("a", active = false), t("b", withdraws = false)), Map.empty), Vector.empty)

  test("a VISA terminal is offered only to a player with a VISA deposit, matched as a substring in any case"):
    val visa = Vector(t("v", scheme = "UNITYFINANCE_card_visa"))
    assertEquals(Eligibility.eligible(visa, Map.empty), Vector.empty)
    assertEquals(Eligibility.eligible(visa, Map("unityfinance" -> Set("MASTERCARD"))), Vector.empty)
    assertEquals(Eligibility.eligible(visa, Map("dreamzpay" -> Set("CARD_VISA"))).map(_.id), Vector("v"))

  test("a deposit recorded without a scheme counts as not VISA"):
    assertEquals(Eligibility.eligible(Vector(t("v", scheme = "VISA")), Map("unityfinance" -> Set.empty)), Vector.empty)

  test("a gateway the player deposited with ranks first, then name, then id"):
    val terminals = Vector(t("3", "Bank"), t("2", "Crypto", gateway = "COZENI"), t("1", "Bank"), t("0", "Alpha"))
    assertEquals(Eligibility.eligible(terminals, Map("cozeni" -> Set("USDT"))).map(_.id), Vector("2", "0", "1", "3"))
    assertEquals(Eligibility.eligible(terminals, Map.empty).map(_.id), Vector("0", "1", "3", "2"))

  test("resolve finds the named terminal only when it is eligible; a blank id is never one"):
    val terminals = Vector(t("a"), t("b", active = false))
    assertEquals(Eligibility.resolve("a", terminals, Map.empty).map(_.id), Some("a"))
    assertEquals(Eligibility.resolve("b", terminals, Map.empty), None)
    assertEquals(Eligibility.resolve("", Vector(t("")), Map.empty), None)

  test("SC-007: 1,000 reads of shuffled terminals answer one order"):
    val terminals = (1 to 20).toVector.map(i => t(s"id-${i % 7}-$i", s"T${i % 5}", gateway = if i % 3 == 0 then "gigadat" else "cozeni"))
    val deposits  = Map("gigadat" -> Set("ETO"))
    val first     = Eligibility.eligible(terminals, deposits)
    val random    = scala.util.Random(7)
    for _ <- 1 to 1000 do assertEquals(Eligibility.eligible(random.shuffle(terminals), deposits), first)
