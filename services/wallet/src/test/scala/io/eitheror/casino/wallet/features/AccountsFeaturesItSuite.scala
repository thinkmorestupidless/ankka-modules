package io.eitheror.casino.wallet.features

import io.eitheror.casino.wallet.it.Harness
import io.eitheror.casino.wallet.wire.Wire

/**
 * features/wallet/accounts.feature (spec 022 User Story 3): non-player accounts over HTTP against
 * Postgres, with the fold of contracts/facts.md checked against each account's own balance (SC-004).
 */
class AccountsFeaturesItSuite extends LedgerFeatures("accounts.feature"):

  private def accounts(kind: String, name: String) = s"/v1/casino/brands/$brand/accounts/$kind/$name"

  private def accountBody(kind: String, name: String): (Int, String) =
    send("GET", s"${accounts(kind, p(name))}/balance?currency=$currency", as = exchange)

  private var lastAccount = ""

  /** A wallet with money enough to seed an account, never one a scenario names. */
  private def seeder: String =
    val s = p("seeder")
    if amountIn(balanceOf(s), "realBalance") == 0 then open(s, 1_000_000L)
    s

  private def split(caller: String, player: String, market: String, amount: Long): (Int, String) =
    send("POST", s"${accounts("pool", p(market))}/splits",
      Some(s"""{"key":"${nextKey("split")}","currency":"$currency","player":"$player","amount":$amount}"""), as = service(caller))

  private def clearingMove(caller: String, pool: String, player: String, amount: Long, direction: String): (Int, String) =
    send("POST", s"${accounts("clearing", p(pool))}/moves",
      Some(s"""{"key":"${nextKey("move")}","currency":"$currency","player":"$player","amount":$amount,"direction":"$direction"}"""),
      as = service(caller))

  private def holds(kind: String, name: String, amount: String): Unit =
    val (status, body) = accountBody(kind, name)
    assertEquals(status, 200, body)
    assertEquals(amountIn(body, "balance"), amount.toLong, body)
    checkFold(kind, name, amount.toLong)

  /** SC-004: the account's balance is the fold of the facts naming it (contracts/facts.md), whatever order they arrived in. */
  private def checkFold(kind: String, name: String, balance: Long): Unit =
    val n = p(name)
    eventually {
      val all = publisher.publishedTo("money.transactions").map(_.text)
      def sum(family: String, matches: String => Boolean) =
        all.filter(t => t.contains(s""""type":"$family"""") && matches(t))
          .flatMap(t => """"amount(_cents)?":(-?\d+)""".r.findFirstMatchIn(t).map(_.group(2).toLong)).sum
      val named = (t: String) => t.contains(s""""account_kind":"$kind"""") && t.contains(s""""account_name":"$n"""")
      sum("account_debit", named) - sum("account_credit", named) -
        (if kind == "pool" then sum("resolution_payment", t => t.contains(s""""market_id":"$n"""")) else 0L) +
        sum("account_adjusted", named)
    }(_ == balance)
  : Unit

  // ── pool accounts ─────────────────────────────────────────────────────────

  When("the service {string} moves {string} from the wallet of {string} into the pool account of market {string} in {string}") {
    (svc: String, a: String, name: String, market: String, c: String) =>
      acted = true
      currency = c
      last = split(svc, p(name), market, a.toLong)
  }

  Then("the pool account of market {string} in {string} exists, owned by the service {string}") { (market: String, c: String, svc: String) =>
    currency = c
    val (status, body) = accountBody("pool", market)
    assertEquals(status, 200, body)
    assertEquals(field(body, "owner"), Some(s"service:${Harness.Project}/$svc"))
    lastAccount = body
  }

  Then("its balance is {string}") { (a: String) => assertEquals(amountIn(lastAccount, "balance"), a.toLong, lastAccount) }

  /** As a Given, seeded by a split from a funded wallet; as a Then, read. */
  Given("the pool account of market {string} in {string} holds {string}") { (market: String, c: String, a: String) =>
    currency = c
    if !acted then assertEquals(split("exchange", seeder, market, a.toLong)._1, 200)
    holds("pool", market, a)
  }

  When("a resolution payment of {string} is paid to {string} from the pool account of market {string} for position {string}") {
    (a: String, name: String, market: String, position: String) =>
      acted = true
      last = send("POST", s"${accounts("pool", p(market))}/payments",
        Some(s"""{"positionId":"$position","currency":"$currency","player":"${p(name)}","amount":$a}"""), as = exchange)
  }

  Then("a resolution payment transaction of {string} is written") { (a: String) =>
    val paid = moneyFacts(s"${p("ada")}/$currency", "resolution_payment")
    assert(paid.exists(t => t.contains(s""""amount_cents":$a""") && t.contains(s""""position_id":"p1"""")), paid.mkString("\n"))
  }

  // ── clearing accounts ─────────────────────────────────────────────────────

  /** The clearing service sets the line, which creates the account under it; a balance below zero is a move to a wallet. */
  Given("the clearing account of pool {string} in {string} holds {string} with a credit line of {string}") {
    (pool: String, c: String, a: String, line: String) =>
      currency = c
      val (status, body) = send("PUT", s"${accounts("clearing", p(pool))}/credit-line",
        Some(s"""{"currency":"$currency","creditLine":$line}"""), as = service("clearing"))
      assertEquals(status, 200, body)
      val balance = a.toLong
      if balance < 0 then assertEquals(clearingMove("clearing", pool, seeder, -balance, "to-wallet")._1, 200)
      if balance > 0 then assertEquals(clearingMove("clearing", pool, seeder, balance, "from-wallet")._1, 200)
      holds("clearing", pool, a)
  }

  When("the service {string} moves {string} from the clearing account of pool {string} to the wallet of {string}") {
    (svc: String, a: String, pool: String, name: String) =>
      acted = true
      last = clearingMove(svc, pool, p(name), a.toLong, "to-wallet")
  }

  Then("the clearing account of pool {string} in {string} holds {string}") { (pool: String, c: String, a: String) =>
    currency = c
    holds("clearing", pool, a)
  }

  // ── the operator (FR-009) ─────────────────────────────────────────────────

  When("an operator adjusts the clearing account of pool {string} by {string} with the reason {string} and a signature") {
    (pool: String, a: String, reason: String) =>
      acted = true
      last = sendWithToken("POST", s"/v1/casino/backoffice/$brand/accounts/clearing/${p(pool)}/adjustments", operatorToken,
        Some(s"""{"key":"${nextKey("adj")}","currency":"$currency","amount":"${Wire.major(a.toLong)}","reason":"$reason","category":"correction"}"""))
      assertEquals(last._1, 200, last._2)
  }

  /**
   * The operator's text and signature are the account's record (its journal); the fact on the topic
   * carries the category and the actor, never the text (contracts/facts.md).
   */
  Then("an adjustment transaction of {string} with the reason {string} and the actor is written") { (a: String, reason: String) =>
    val key      = s"clearing/${p("north")}/$currency"
    val adjusted = moneyFacts(key, "account_adjusted")
    assert(adjusted.exists(t => t.contains(s""""amount":$a""") && t.contains(""""actor_id":"op-7"""") && t.contains(""""reason":"correction"""")),
      adjusted.mkString("\n"))
    assert(!adjusted.exists(_.contains(reason)), "the operator's text left the service")
    // The reason is required (an adjustment without one is REASON_REQUIRED, AccountEntitySuite) and held in the journal.
    val audit = eventually(facts("platform.audit", s"account/$key"))(_.exists(_.contains(""""action":"adjust"""")))
    assert(audit.exists(t => t.contains(""""actor_id":"op-7"""") && !t.contains(reason)), audit.mkString("\n"))
  }
