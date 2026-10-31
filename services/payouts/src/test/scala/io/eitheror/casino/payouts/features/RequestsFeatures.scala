package io.eitheror.casino.payouts.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts.WithdrawalRequested
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.it.{Answer, FakeWallet}
import io.eitheror.casino.payouts.limits.{Counted, Pair, SetLimits, WithdrawalLimits}
import io.eitheror.casino.payouts.player.{CountRequest, DeleteDestination, Kyc, PlayerPayouts, Uncount}
import io.eitheror.casino.payouts.workflow.WithdrawalWorkflow

import java.time.{Duration, Instant, ZoneOffset}
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * features/payouts/requests.feature (US1, US2; FR-003 to FR-007). The request is made through the player's
 * route; the wallet's balance, preview and hold are the fake's, and a saved destination is seeded on the
 * player's record. Amounts are minor units. What the wallet's preview says of turnover and restricted funds
 * is scripted on the fake, as the wallet's own suites (spec 002) hold it to the records.
 */
class RequestsFeatures extends PayoutsFeatures("requests.feature"):

  private var balanceBefore: Option[Long] = None
  private var openedBefore                = 0
  private var named: Option[String]       = None
  private var target                      = bank
  private var key                         = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    balanceBefore = None; openedBefore = 0; named = None; target = bank; key = newKey()

  private def balance(amount: Long): Unit =
    kit.wallet.balanceFor(player, amount)

  /** A request to the scenario's terminal, naming the saved destination when it needs one. */
  private def requestOf(amount: Long, extra: (String, Any)*) =
    remember()
    ask((Vector[(String, Any)]("amount" -> amount, "currency" -> currency, "terminalId" -> target.id) ++
      Option.when(target.requiresPayoutDestination)("destinationId" -> named.getOrElse(saved())) ++ extra)*)

  private def saved(): String =
    val d = destination()
    named = Some(d)
    d

  private def remember(): Unit =
    balanceBefore = kit.wallet.balance(player)
    openedBefore = requests().objects("requests").size

  private def ready(amount: Long = 10000L): Unit =
    verified()
    balance(amount)
    terminals(bank)

  Given("a player with a wallet in {string}") { (c: String) => currency = c }

  Given("the player is verified, with a real balance of {string}, no deposit turnover pending and no restricted funds") { (b: String) =>
    ready(b.toLong)
  }

  Given("the player is verified with a real balance of {string}") { (b: String) => ready(b.toLong) }

  When("the player requests a withdrawal of {string} to an eligible bank terminal naming their saved destination") { (a: String) =>
    requestOf(a.toLong)
  }

  Then("the withdrawal request is created pending review") { () =>
    assertEquals((last.status, last.str("status")), (201, "PENDING_REVIEW"), last.text)
  }

  Then("the real balance is {string}") { (b: String) => assertEquals(kit.wallet.balance(player), Some(b.toLong)) }

  Then("the request is listed in the player's account and in the brand's queue") { () =>
    val id = last.str("requestId")
    eventually("listed") {
      Option.when(requests().objects("requests").exists(_.text("requestId").contains(id)) && queue.rows.exists(_.requestId == id))(())
    }
    assertEquals(requests().objects("requests").head.text("status"), Some("PENDING_REVIEW"))
  }

  Then("a {string} fact names the request, {string} and {string}") { (family: String, amount: String, c: String) =>
    assertEquals(family, "withdrawal_requested")
    val id = last.str("requestId")
    val f  = eventually("published")(withdrawals.collectFirst { case f: WithdrawalRequested if f.payoutId == id => f })
    assertEquals((f.amountCents.value, f.currency.code), (amount.toLong, c))
  }

  Given("a withdrawal request pending review") { () =>
    ready()
    requestOf(5000L)
    assertEquals(last.status, 201, last.text)
  }

  When("the player requests a withdrawal again") { () => requestOf(1000L) }

  Then("it is refused as already pending") { () => refusedWith(409, "WITHDRAWAL_REQUEST_ALREADY_PENDING") }

  Then("nothing moves") { () =>
    assertEquals(kit.wallet.balance(player), balanceBefore)
    assertEquals(requests().objects("requests").size, openedBefore)
  }

  When("the player requests a withdrawal of {string}") { (a: String) =>
    if a.toLongOption.isDefined then requestOf(a.toLong)
    else
      remember()
      askRaw(a, "currency" -> currency, "terminalId" -> target.id, "destinationId" -> saved())
  }

  Then("it is refused naming the amount") { () => refusedWith(422, "INVALID_AMOUNT") }

  Given("a terminal that requires a destination") { () =>
    ready()
    target = terminal("t-dest", "unityfinance", destination = true)
    terminals(target)
  }

  When("the player requests a withdrawal to it naming no destination") { () =>
    remember()
    ask("amount" -> 1000L, "currency" -> currency, "terminalId" -> target.id)
  }

  Then("it is refused as destination required") { () => refusedWith(422, "DESTINATION_REQUIRED") }

  Given("a crypto terminal") { () =>
    ready()
    target = terminal("t-crypto", "cozeni", scheme = "USDT", coins = Vector("USDT"))
    terminals(target)
  }

  When("the player requests a withdrawal to it naming no crypto address") { () =>
    remember()
    ask("amount" -> 1000L, "currency" -> currency, "terminalId" -> target.id, "cryptoCurrency" -> "USDT")
  }

  Then("it is refused as crypto address required") { () => refusedWith(422, "WALLET_ADDRESS_REQUIRED") }

  Given("a crypto terminal supporting coins {string} and {string}") { (a: String, b: String) =>
    ready()
    target = terminal("t-coins", "cozeni", scheme = "CRYPTO", coins = Vector(a, b))
    terminals(target)
  }

  When("the player requests a withdrawal to it naming coin {string}") { (coin: String) =>
    remember()
    ask("amount" -> 1000L, "currency" -> currency, "terminalId" -> target.id, "walletAddress" -> "0xabc", "cryptoCurrency" -> coin)
  }

  Then("it is refused naming {string} and {string}") { (a: String, b: String) =>
    val body = refusedWith(422, "CRYPTO_CURRENCY_NOT_SUPPORTED")
    assertEquals(body.strings("supported"), Vector(a, b))
  }

  Given("a destination that is another player's") { () =>
    ready()
    val other = newPlayer()
    verified(other)
    named = Some(destination(other))
  }

  Given("a destination the player deleted") { () =>
    ready()
    val d = destination()
    record().call(PlayerPayouts.deleteDestination).invoke(DeleteDestination(d, kit.now())): Unit
    named = Some(d)
  }

  When("the player requests a withdrawal naming it") { () => requestOf(1000L) }

  Then("it is refused as an invalid destination") { () => refusedWith(422, "INVALID_PAYOUT_DESTINATION") }

  Then("it is refused as insufficient funds") { () => refusedWith(402, "INSUFFICIENT_FUNDS") }

  // ── US2: identity ────────────────────────────────────────────────────────────────────────────

  private def documents(status: Kyc): Unit =
    named()
    kyc(status)
    balance(10000L)
    terminals(bank)

  Given("the player's identity documents are not submitted") { () => documents(Kyc.Unverified) }
  Given("the player's identity documents are under review") { () => documents(Kyc.Pending) }
  Given("the player's identity documents are rejected") { () => documents(Kyc.Rejected) }
  Given("the player's identity is verified") { () => ready() }

  When("the player requests a withdrawal") { () => requestOf(5000L, "key" -> key) }

  private def toldKyc(status: String, message: String): Unit =
    val body = refusedWith(402, "WITHDRAWAL_BLOCKED_KYC")
    assertEquals((body.text("kycStatus"), body.text("message")), (Some(status), Some(message)))

  Then("it is refused telling them to verify their identity") { () =>
    toldKyc("unverified", "Please verify your identity before withdrawing")
  }
  Then("it is refused telling them that the documents are under review") { () =>
    toldKyc("pending", "Your identity documents are under review")
  }
  Then("it is refused telling them that the documents were not accepted and must be sent again") { () =>
    toldKyc("rejected", "Your identity documents were not accepted — please re-submit")
  }

  Then("the identity gate passes") { () => assertEquals(last.status, 201, last.text) }

  // ── US2: the limits ──────────────────────────────────────────────────────────────────────────

  private def limits(c: String, pair: Pair): Unit =
    val a = kit.components.forEventSourcedEntity(EntityId(kit.slug)).call(WithdrawalLimits.set)
      .invoke(SetLimits(Map(c -> pair), "op-features", kit.now()))
    assertEquals(a.refusal, None)

  Given("the brand's weekly withdrawal limit in {string} is {string}") { (c: String, l: String) =>
    limits(c, Pair(weekly = Some(l.toLong)))
  }
  Given("the brand's monthly withdrawal limit in {string} is {string}") { (c: String, l: String) =>
    limits(c, Pair(monthly = Some(l.toLong)))
  }

  /** A request counted on the player's record, as the route counts one it opened; its id. */
  private def counted(amount: Long, at: Instant): String =
    val id = s"earlier-${UUID.randomUUID().toString.take(8)}"
    record().call(PlayerPayouts.countRequest).invoke(CountRequest(Counted(id, amount, Currency.unsafe(currency), at), kit.now())): Unit
    id

  Given("the player's withdrawal requests in the last seven days come to {string}") { (a: String) =>
    counted(a.toLong, kit.now().minus(Duration.ofDays(1))): Unit
  }
  Given("the player's withdrawal requests this calendar month come to {string}") { (a: String) =>
    counted(a.toLong, kit.now().atZone(ZoneOffset.UTC).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).toInstant): Unit
  }

  private def pastLimit(code: String, word: String, limit: String, used: String, remaining: String): Unit =
    val body = refusedWith(402, code)
    assertEquals((body.long(s"${word}Limit"), body.long(s"${word}Used"), body.long(s"${word}Remaining")),
      (Some(limit.toLong), Some(used.toLong), Some(remaining.toLong)))

  Then("it is refused as past the weekly withdrawal limit, naming {string}, {string} used and {string} remaining") {
    (l: String, u: String, r: String) => pastLimit("WITHDRAWAL_BLOCKED_WEEKLY_LIMIT", "weekly", l, u, r)
  }
  Then("it is refused as past the monthly withdrawal limit, naming {string}, {string} used and {string} remaining") {
    (l: String, u: String, r: String) => pastLimit("WITHDRAWAL_BLOCKED_MONTHLY_LIMIT", "monthly", l, u, r)
  }

  private def closedUnpaid(amount: String): Unit =
    val id = counted(amount.toLong, kit.now().minus(Duration.ofDays(1)))
    record().call(PlayerPayouts.uncount).invoke(Uncount(id, kit.now())): Unit

  Given("a withdrawal request of {string} this week that was cancelled") { (a: String) => closedUnpaid(a) }
  Given("a withdrawal request of {string} this week that was rejected") { (a: String) => closedUnpaid(a) }
  Given("a withdrawal request of {string} this week that was failed") { (a: String) => closedUnpaid(a) }

  // ── US2: turnover and restricted funds, as the wallet's preview says them ────────────────────

  private def previewSays(refusal: Option[String] = None, confirmationRequired: Boolean = false, bonusBalance: Long = 0L,
      clawback: Long = 0L, totalForfeit: Long = 0L, mostWithdrawable: Long = 1000000L): Unit =
    kit.wallet.previewFor(player, FakeWallet.preview(refusal, confirmationRequired, bonusBalance, clawback, totalForfeit, mostWithdrawable))

  Given("deposit turnover pending of {string}") { (_: String) =>
    ready()
    previewSays(refusal = Some("WITHDRAWAL_BLOCKED_TURNOVER"))
  }

  Then("it is refused as blocked by turnover") { () =>
    val body = refusedWith(402, "WITHDRAWAL_BLOCKED_TURNOVER")
    assertEquals(body.long("totalRemaining"), None)
  }

  Given("an active restricted funds record under withdrawal policy {string} whose clawback would leave real balance below the amount") {
    (_: String) =>
      ready()
      previewSays(refusal = Some("WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT"), confirmationRequired = true, bonusBalance = 2000L,
        clawback = 7000L, totalForfeit = 9000L, mostWithdrawable = 3000L)
  }

  Then("it is refused as insufficient after forfeit, naming the most they could withdraw") { () =>
    val body = refusedWith(422, "WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT")
    assertEquals((body.long("maxWithdrawable"), body.long("totalForfeit")), (Some(3000L), Some(9000L)))
  }

  Given("an active restricted funds record whose withdrawal policy forfeits it, with a clawback that still leaves enough") { () =>
    ready()
    previewSays(confirmationRequired = true, bonusBalance = 2000L, clawback = 500L, totalForfeit = 2500L)
  }

  Given("an active restricted funds record under withdrawal policy {string}") { (_: String) =>
    ready()
    previewSays(confirmationRequired = true, bonusBalance = 2000L, clawback = 0L, totalForfeit = 2000L)
  }

  When("the player requests a withdrawal without confirming the forfeit") { () => requestOf(5000L, "key" -> key) }

  When("the player requests a withdrawal confirming the forfeit") { () =>
    kit.wallet.holdFor(WithdrawalWorkflow.requestId(player, key), Answer.ok(Json.obj("paidAmount" -> 5000L, "voidedAmount" -> 0L,
      "clawbackAmount" -> 500L, "forfeited" -> Json.arr("rec-1"), "txnId" -> "txn-1", "realBalance" -> 4500L, "currency" -> currency)))
    requestOf(5000L, "key" -> key, "confirmForfeit" -> true)
  }

  Then("it is refused as requiring forfeit confirmation, naming the restricted balance, the clawback and the total forfeit") { () =>
    val body = refusedWith(402, "WITHDRAWAL_REQUIRES_BONUS_FORFEIT_CONFIRMATION")
    assertEquals((body.long("bonusBalance"), body.long("clawbackAmount"), body.long("totalForfeit")), (Some(2000L), Some(500L), Some(2500L)))
  }

  Then("it is refused as requiring forfeit confirmation, naming the restricted balance and a clawback of {string}") { (c: String) =>
    val body = refusedWith(402, "WITHDRAWAL_REQUIRES_BONUS_FORFEIT_CONFIRMATION")
    assertEquals((body.long("bonusBalance"), body.long("clawbackAmount")), (Some(2000L), Some(c.toLong)))
  }

  private def holds = kit.wallet.calls("").flatMap(_.json)

  Then("the hold is placed") { () =>
    assertEquals(last.status, 201, last.text)
    assertEquals(holds.map(h => (h.long("amount"), h.bool("playerConfirmed"))), Vector((Some(5000L), Some(true))))
  }

  Then("the record is forfeited") { () =>
    assert(requestState(last.str("requestId")).forfeit.bonusForfeited, "the request records the forfeit")
  }

  Then("the clawback is applied once") { () =>
    assertEquals(holds.size, 1)
    assertEquals(requestState(last.str("requestId")).forfeit.clawbackAmount, 500L)
  }

  Then("the answer says what was forfeited") { () =>
    assertEquals((last.json.bool("bonusForfeited"), last.json.long("forfeitedAmount"), last.json.long("clawbackAmount")),
      (Some(true), Some(2000L), Some(500L)))
  }

  Given("a real balance of {string} and an active restricted funds record") { (b: String) =>
    ready(b.toLong)
    previewSays(refusal = Some("INSUFFICIENT_FUNDS"), confirmationRequired = true, bonusBalance = 2000L, clawback = 500L,
      totalForfeit = 2500L, mostWithdrawable = 0L)
  }

  Then("not as insufficient after forfeit") { () =>
    assertNotEquals(last.str("error"), "WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT")
  }
