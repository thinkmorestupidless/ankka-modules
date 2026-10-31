package io.eitheror.casino.payouts.features

import io.eitheror.casino.contract.facts.WithdrawalRequested
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.player.{DeleteDestination, PlayerPayouts}

/**
 * features/payouts/requests.feature (US1, US2; FR-003 to FR-007). The request is made through the player's
 * route; the wallet's balance, preview and hold are the fake's, and a saved destination is seeded on the
 * player's record. Amounts are minor units.
 */
class RequestsFeatures extends PayoutsFeatures("requests.feature"):

  private var balanceBefore: Option[Long] = None
  private var openedBefore                = 0
  private var named: Option[String]       = None
  private var target                      = bank

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    balanceBefore = None; openedBefore = 0; named = None; target = bank

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
