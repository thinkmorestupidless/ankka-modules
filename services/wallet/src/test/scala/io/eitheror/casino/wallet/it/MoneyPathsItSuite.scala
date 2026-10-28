package io.eitheror.casino.wallet.it

import com.thinkmorestupidless.ankka.http.Caller

/** US2, US4, US5, US6 and FR-022a over HTTP: each path's facts, rows and audiences. */
class MoneyPathsItSuite extends Harness:

  private val merchant = Caller.Service("payments", "merchant")
  private val rewards  = Caller.Service("local", "rewards")
  private val payouts  = Caller.Service("local", "payouts")

  private def deposit(player: String, id: String, amount: Long) =
    send("POST", s"${wallet(player)}/deposits", Some(s"""{"paymentId":"$id","amount":$amount,"paymentProvider":"dreamzpay","terminal":"t1"}"""), as = merchant)

  test("an award, a split bet that completes the requirement, and the conversion: facts name the award (US2)") {
    deposit("rf", "pay-1", 1000)
    val (s1, awarded) = send("POST", s"${wallet("rf")}/awards", Some("""{"key":"grant-1","bonusRef":"welcome","amount":2000,"wageringMultiplier":1,"maxWin":5000}"""), as = rewards)
    assertEquals((s1, field(awarded, "bonusBalance"), field(awarded, "wageringRequirement")), (200, Some("2000"), Some("2000")))
    val (s2, placed) = bet("rf", "b-1", "r1", 2500)
    assertEquals((s2, field(placed, "walletType")), (200, Some("split")))
    assert(placed.contains("\"conversions\":["), placed)
    val money = eventually(facts("money.transactions", "rf/EUR"))(_.exists(_.contains("restricted_funds_converted")))
    assert(money.exists(f => f.contains("restricted_funds_awarded") && f.contains("\"award_id\":\"grant-1\"")), money.mkString("\n"))
    assert(money.exists(f => f.contains("restricted_funds_converted") && f.contains("\"award_id\":\"grant-1\"")), money.mkString("\n"))
  }

  test("a cancel claws back what was won since the award and publishes it on the forfeit (FR-015a, FR-017)") {
    deposit("cb", "pay-1", 1000)
    send("POST", s"${wallet("cb")}/awards", Some("""{"key":"grant-1","bonusRef":"welcome","amount":2000,"wageringMultiplier":10,"maxWin":5000}"""), as = rewards)
    bet("cb", "b-1", "r1", 500)
    send("POST", s"${wallet("cb")}/wins", Some("""{"key":"w-1","roundId":"r1","amount":1200}"""))
    // real at award 1000; now 1000 − 500 + 1200 = 1700; no deposit since: clawback 700.
    val (status, cancelled) = send("POST", s"${wallet("cb")}/awards/cancel", Some("""{"key":"c-1"}"""), as = rewards)
    assertEquals((status, field(cancelled, "clawbackAmount"), field(cancelled, "realBalance")), (200, Some("700"), Some("1000")))
    val money = eventually(facts("money.transactions", "cb/EUR"))(_.exists(_.contains("\"clawback_cents\":700")))
    assert(money.exists(f => f.contains("restricted_funds_forfeited") && f.contains("\"reason\":\"cancel\"")), money.mkString("\n"))
  }

  test("a hold, its release, a second hold, its confirmation and its reversal: each a movement on the topic (US4)") {
    // The brand's default turnover (once the deposit) would block the hold; the operator sets none (FR-004a).
    assertEquals(sendWithToken("PUT", s"/v1/casino/backoffice/$brand/wallet-config/turnover", TestTokens.operator("op-1"),
      Some("""{"multiplier":0}"""))._1, 200)
    deposit("wd", "pay-1", 5000)
    val (s1, held) = send("POST", s"${wallet("wd")}/holds", Some("""{"key":"w-1","amount":2000,"playerConfirmed":true}"""), as = payouts)
    assertEquals((s1, field(held, "realBalance")), (200, Some("3000")))
    assertEquals(field(send("POST", s"${wallet("wd")}/holds", Some("""{"key":"w-2","amount":100}"""), as = payouts)._2, "error"),
      Some("WITHDRAWAL_REQUEST_ALREADY_PENDING"))
    val (_, released) = send("POST", s"${wallet("wd")}/holds/release", Some("""{"key":"rl-1","requestId":"w-1","outcome":"rejected"}"""), as = payouts)
    assertEquals(field(released, "realBalance"), Some("5000"))
    send("POST", s"${wallet("wd")}/holds", Some("""{"key":"w-3","amount":1500}"""), as = payouts)
    send("POST", s"${wallet("wd")}/holds/confirm", Some("""{"key":"cf-1","requestId":"w-3"}"""), as = payouts)
    val (_, reversed) = send("POST", s"${wallet("wd")}/holds/reverse", Some("""{"key":"rv-1","requestId":"w-3"}"""), as = payouts)
    assertEquals(field(reversed, "realBalance"), Some("5000"))
    val money = eventually(facts("money.transactions", "wd/EUR"))(_.exists(_.contains("withdrawal_reversed")))
    assertEquals(Seq("hold_placed", "hold_released", "withdrawal_reversed").map(f => money.count(_.contains(s"\"$f\""))), Seq(2, 1, 1))
  }

  test("the hold preview answers before the hold, moving nothing (FR-023a)") {
    deposit("pv", "pay-1", 1000)
    val (status, preview) = send("GET", s"${wallet("pv")}/holds/preview?amount=400", as = payouts)
    assertEquals((status, field(preview, "mostWithdrawable")), (200, Some("1000")))
    assertEquals(field(send("GET", s"${wallet("pv")}/balance")._2, "realBalance"), Some("1000"))
  }

  test("an operator's adjustment carries the actor from the token; its fact the category, never the text (FR-026, FR-033)") {
    deposit("adj", "pay-1", 1000)
    val (status, body) = sendWithToken("POST", s"/v1/casino/backoffice/$brand/wallets/adj/EUR/adjustments", TestTokens.operator("op-42"),
      Some("""{"key":"a-1","walletType":"real","amount":"5.00","reason":"refund for ticket 991, J. Doe","category":"goodwill"}"""))
    assertEquals((status, field(body, "realBalance")), (200, Some("15.00")))
    val adjusted = eventually(facts("money.transactions", "adj/EUR"))(_.exists(_.contains("admin_adjusted"))).filter(_.contains("admin_adjusted"))
    assert(adjusted.head.contains("\"reason\":\"goodwill\"") && adjusted.head.contains("\"actor_id\":\"op-42\""), adjusted.head)
    assert(!publisher.published.exists(_.text.contains("J. Doe")), "the operator's text left the wallet")
  }

  test("an operator from another brand, and a player, are refused the backoffice") {
    assertEquals(sendWithToken("GET", s"/v1/casino/backoffice/$brand/wallets/adj/EUR/balance", TestTokens.operator("op-1", "brand_other"))._1, 403)
    assertEquals(sendWithToken("GET", s"/v1/casino/backoffice/$brand/wallets/adj/EUR/balance", TestTokens.player("adj"))._1, 403)
  }

  test("a player reads their own balance and transactions in major units, and no one else's (FR-032)") {
    deposit("pl", "pay-1", 1234)
    val (status, balance) = sendWithToken("GET", s"/v1/casino/players/$brand/wallets/EUR/balance", TestTokens.player("pl"))
    assertEquals((status, field(balance, "realBalance")), (200, Some("12.34")))
    val listed = eventually(sendWithToken("GET", s"/v1/casino/players/$brand/wallets/EUR/transactions", TestTokens.player("pl"))._2)(_.contains("\"deposit\""))
    assert(listed.contains("\"amount\":\"12.34\""), listed)
    assertEquals(field(sendWithToken("GET", s"/v1/casino/players/$brand/wallets/EUR/balance", TestTokens.player("someone-else"))._2, "realBalance"), Some("0.00"))
    assertEquals(sendWithToken("GET", s"/v1/casino/players/$brand/wallets/EUR/balance", TestTokens.player("pl", "casino-otherbrand"))._1, 403)
  }

  test("transactions, sums and lifetime totals answer from the views, and again after a restart (FR-029)") {
    deposit("vw", "pay-1", 3000)
    bet("vw", "b-1", "r1", 1000)
    send("POST", s"${wallet("vw")}/wins", Some("""{"key":"w-1","roundId":"r1","amount":400}"""))
    val page = eventually(send("GET", s"${wallet("vw")}/transactions")._2)(t => field(t, "total").contains("3"))
    assert(page.contains("\"type\":\"bet\"") && page.contains("\"status\":\"settled\""), page)
    assertEquals(field(eventually(send("GET", s"${wallet("vw")}/sums?type=deposit")._2)(field(_, "total").contains("3000")), "count"), Some("1"))
    val totals = eventually(send("GET", s"${wallet("vw")}/lifetime-totals")._2)(field(_, "won").contains("400"))
    assertEquals((field(totals, "deposited"), field(totals, "wagered")), (Some("3000"), Some("1000")))
    testKit.restartService()
    assertEquals(field(send("GET", s"${wallet("vw")}/transactions?type=deposit")._2, "total"), Some("1"))
  }

  test("a payment's outcome is recorded once and published as its family, keyed by the payment (FR-022a)") {
    val outcome = """{"outcome":"failed","playerId":"po","paymentProvider":"dreamzpay","code":"DECLINED","reason":"card"}"""
    val (status, first) = send("POST", s"/v1/casino/brands/$brand/payments/pay-77/outcome", Some(outcome), as = merchant)
    assertEquals((status, field(first, "firstRecorded")), (200, Some("true")))
    assertEquals(field(send("POST", s"/v1/casino/brands/$brand/payments/pay-77/outcome", Some(outcome), as = merchant)._2, "firstRecorded"), Some("false"))
    val published = eventually(facts("payments.payments", "pay-77"))(_.nonEmpty)
    assertEquals(published.size, 1)
    assert(published.head.contains("\"payment.failed\"") && published.head.contains("\"code\":\"DECLINED\""), published.head)
  }

  private def bet(player: String, key: String, round: String, amount: Long) =
    send("POST", s"${wallet(player)}/bets", Some(s"""{"key":"$key","roundId":"$round","gameId":"slot-a","amount":$amount}"""))

  // ── spec 025: the pools on every reply, the preview, the rows and the audiences ──

  /** Every accepted money reply carries both pools, and they sum to its real balance. */
  private def pools(reply: String): (Long, Long) =
    val (d, c, r) = (field(reply, "depositPoolBalance"), field(reply, "convertedPoolBalance"), field(reply, "realBalance"))
    assert(d.isDefined && c.isDefined, s"no pools on $reply")
    assertEquals(d.get.toLong + c.get.toLong, r.get.toLong, reply)
    (d.get.toLong, c.get.toLong)

  test("spec 025 US1: a bet, a win and a rollback answer the pools, and the bet and win their deposit parts") {
    assertEquals(pools(deposit("pb", "pay-1", 1000)._2), (1000L, 0L))
    send("POST", s"${wallet("pb")}/awards", Some("""{"key":"grant-1","bonusRef":"welcome","amount":2000,"wageringMultiplier":1,"maxWin":5000}"""), as = rewards)
    // 1000 real + 1500 from the record completes the requirement: the record's 500 left converts first, so
    // the 1000 real leg is 500 converted and 500 deposit.
    val (_, placed) = bet("pb", "b-1", "r1", 2500)
    assertEquals((field(placed, "fundedDepositAmount"), pools(placed)), (Some("500"), (500L, 0L)))
    val (_, won) = send("POST", s"${wallet("pb")}/wins", Some("""{"key":"w-1","roundId":"r1","amount":1000}"""))
    assert(field(won, "wonDepositAmount").isDefined, won)
    val (wd, wc) = pools(won)
    assertEquals(wd - 500L, field(won, "wonDepositAmount").get.toLong)
    bet("pb", "b-2", "r2", 100)
    val (_, rolled) = send("POST", s"${wallet("pb")}/rollbacks", Some("""{"key":"rb-1","roundId":"r2"}"""))
    assertEquals(pools(rolled), (wd, wc))
    val money = eventually(facts("money.transactions", "pb/EUR"))(_.exists(_.contains("bet_rolled_back")))
    assert(money.forall(_.contains("\"deposit_pool_after\"")), money.mkString("\n"))
    assert(money.exists(f => f.contains("\"bet_placed\"") && f.contains("\"deposit_part_cents\":500")), money.mkString("\n"))
  }

  test("spec 025 US2: every other movement answers the pools, and the preview the deposit part of each movement") {
    assertEquals(sendWithToken("PUT", s"/v1/casino/backoffice/$brand/wallet-config/turnover", TestTokens.operator("op-1"),
      Some("""{"multiplier":0}"""))._1, 200)
    assertEquals(pools(deposit("pm", "pay-1", 5000)._2), (5000L, 0L))
    val (_, preview) = send("GET", s"${wallet("pm")}/holds/preview?amount=2000", as = payouts)
    assertEquals((field(preview, "paidDepositAmount"), field(preview, "voidedDepositAmount"), field(preview, "clawbackDepositAmount")),
      (Some("2000"), Some("0"), Some("0")))
    assertEquals(field(send("GET", s"${wallet("pm")}/balance")._2, "depositPoolBalance"), Some("5000"))
    assertEquals(pools(send("POST", s"${wallet("pm")}/holds", Some("""{"key":"w-1","amount":2000,"playerConfirmed":true}"""), as = payouts)._2), (3000L, 0L))
    assertEquals(pools(send("POST", s"${wallet("pm")}/holds/release", Some("""{"key":"rl-1","requestId":"w-1","outcome":"cancelled"}"""), as = payouts)._2), (5000L, 0L))
    send("POST", s"${wallet("pm")}/holds", Some("""{"key":"w-2","amount":1500}"""), as = payouts)
    send("POST", s"${wallet("pm")}/holds/confirm", Some("""{"key":"cf-1","requestId":"w-2"}"""), as = payouts)
    assertEquals(pools(send("POST", s"${wallet("pm")}/holds/reverse", Some("""{"key":"rv-1","requestId":"w-2"}"""), as = payouts)._2), (5000L, 0L))
    val adjust = s"/v1/casino/backoffice/$brand/wallets/pm/EUR/adjustments"
    val (_, credited) = sendWithToken("POST", adjust, TestTokens.operator("op-1"), Some("""{"key":"a-1","amount":"5.00","reason":"goodwill","category":"goodwill"}"""))
    assertEquals(field(credited, "realBalance"), Some("55.00"))
    sendWithToken("POST", adjust, TestTokens.operator("op-1"), Some("""{"key":"a-2","amount":"-1.00","reason":"fix","category":"correction"}""")): Unit
    assertEquals(pools(send("GET", s"${wallet("pm")}/balance")._2), (5400L, 0L))
    val (_, credit) = send("POST", s"${wallet("pm")}/provider-credits",
      Some("""{"key":"pc-1","amount":700,"providerRef":"fs-1","reason":"free spins","actor":"st8"}"""))
    assertEquals(pools(credit), (5400L, 700L))
    val money = eventually(facts("money.transactions", "pm/EUR"))(_.exists(_.contains("provider_credited")))
    assert(money.forall(_.contains("\"deposit_pool_after\"")), money.mkString("\n"))
  }

  test("spec 025 US3: the transactions view's rows carry the parts and the pools, and both audiences read the pools in major units") {
    deposit("pr", "pay-1", 3000)
    bet("pr", "b-1", "r1", 1000)
    val rows = eventually(send("GET", s"${wallet("pr")}/transactions")._2)(field(_, "total").contains("2"))
    assert(rows.contains("\"depositPart\":1000") && rows.contains("\"depositPoolAfter\":2000"), rows)
    val (_, operator) = sendWithToken("GET", s"/v1/casino/backoffice/$brand/wallets/pr/EUR/balance", TestTokens.operator("op-1"))
    assertEquals((field(operator, "depositPoolBalance"), field(operator, "convertedPoolBalance")), (Some("20.00"), Some("0.00")))
    val (_, player) = sendWithToken("GET", s"/v1/casino/players/$brand/wallets/EUR/balance", TestTokens.player("pr"))
    assertEquals((field(player, "depositPoolBalance"), field(player, "convertedPoolBalance")), (Some("20.00"), Some("0.00")))
    val listed = eventually(sendWithToken("GET", s"/v1/casino/players/$brand/wallets/EUR/transactions", TestTokens.player("pr"))._2)(_.contains("\"bet\""))
    assert(listed.contains("\"depositPart\":\"10.00\""), listed)
  }
