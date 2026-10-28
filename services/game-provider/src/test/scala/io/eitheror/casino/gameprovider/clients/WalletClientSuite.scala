package io.eitheror.casino.gameprovider.clients

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.gameprovider.Json.*
import io.eitheror.casino.gameprovider.it.{DirectServices, FakeNeighbours, FakeWallet}

/** research R7's mapping, row by row, against spec 002's routes as built (camelCase) as the fake wallet serves them. */
class WalletClientSuite extends munit.FunSuite with LogCapturing:

  private val wallet     = FakeWallet()
  private val neighbours = FakeNeighbours("goldvibe")
  private val services   = DirectServices(Map("wallet" -> wallet.address, "players" -> neighbours.address, "kyc" -> neighbours.address,
    "responsible-gambling" -> neighbours.address, "rewards" -> neighbours.address))
  private val client = WalletClient(services, "goldvibe")

  override def afterAll(): Unit = { wallet.stop(); neighbours.stop() }
  override def beforeEach(context: BeforeEach): Unit = { wallet.reset(); neighbours.reset() }

  private def bet(key: String, amount: Long, allowNegative: Boolean = false) =
    client.placeBet("p-1", "EUR", key, key, "book-of-ra", "s-1", amount, allowNegative, Some("slots"), "st8")

  test("a bet moves money and answers the wallet's transaction id and balances") {
    wallet.set("p-1", "EUR", 1000, 500)
    bet("t-1", 200) match
      case WalletAnswer.Moved(id, Some(b)) => assert(id.nonEmpty); assertEquals(b.total, 1300L)
      case other                          => fail(other.toString)
  }

  test("the bet carries the key, round, game, category, provider and amount in minor units, camelCase as the wallet reads it") {
    wallet.set("p-1", "EUR", 1000)
    bet("t-1", 200)
    val sent = wallet.calls("bets").head
    assertEquals(sent.path, "/v1/casino/brands/goldvibe/wallets/p-1/EUR/bets")
    val body = sent.json.get
    assertEquals(body.text("key"), Some("t-1"))
    assertEquals(body.text("roundId"), Some("t-1"))
    assertEquals(body.long("amount"), Some(200L))
    assertEquals(body.text("gameId"), Some("book-of-ra"))
    assertEquals(body.text("category"), Some("slots"))
    assertEquals(body.text("gameType"), Some("slots"))
    assertEquals(body.text("provider"), Some("st8"))
    assertEquals(body.bool("allowNegative"), Some(false))
  }

  test("INSUFFICIENT_FUNDS is not_enough_money") {
    assertEquals(bet("t-1", 200), WalletAnswer.Refused("INSUFFICIENT_FUNDS", WalletRefusal.InsufficientFunds, "Insufficient funds in real and bonus wallets"))
    assertEquals(WalletRefusal.InsufficientFunds.word, Some("not_enough_money"))
  }

  // The bet workflow asks `may-bet` itself since 2026-10-10 (research R18); a wallet that still answered these is read the same.
  test("PLAYER_LOCKED, EXCLUDED and COOLING_OFF are player_locked") {
    for code <- Seq("PLAYER_LOCKED", "EXCLUDED", "COOLING_OFF") do
      wallet.refuse(s"t-$code", code)
      bet(s"t-$code", 1) match
        case WalletAnswer.Refused(`code`, r, _) => assertEquals(r.word, Some("player_locked"), code)
        case other                             => fail(s"$code: $other")
  }

  test("SCOPE_BLOCKED, MAX_STAKE_EXCEEDED and OVER_LIMIT are spending_limit") {
    for code <- Seq("SCOPE_BLOCKED", "MAX_STAKE_EXCEEDED", "OVER_LIMIT") do
      wallet.refuse(s"t-$code", code)
      bet(s"t-$code", 1) match
        case WalletAnswer.Refused(`code`, r, _) => assertEquals(r.word, Some("spending_limit"), code)
        case other                             => fail(s"$code: $other")
  }

  test("ROUND_NOT_FOUND is its own case, answered by no word") {
    client.creditWin("p-1", "EUR", "c-1/t-9", "t-9", 100) match
      case WalletAnswer.Refused("ROUND_NOT_FOUND", WalletRefusal.RoundNotFound, _) => ()
      case other                                                                  => fail(other.toString)
    assertEquals(WalletRefusal.RoundNotFound.word, None)
  }

  test("RG_UNAVAILABLE, KEY_REUSED, INVALID_REQUEST and an unknown code are unacknowledged") {
    for (code, status) <- Seq("RG_UNAVAILABLE" -> 503, "KEY_REUSED" -> 409, "INVALID_REQUEST" -> 400, "SOMETHING_NEW" -> 409) do
      wallet.refuse(s"t-$code", code, status)
      assert(bet(s"t-$code", 1).isInstanceOf[WalletAnswer.Unacknowledged], code)
  }

  test("a wallet that is down, or answers 5xx, is unacknowledged") {
    wallet.down = true
    assert(bet("t-1", 1).isInstanceOf[WalletAnswer.Unacknowledged])
    assert(client.balance("p-1", "EUR").isLeft)
    wallet.down = false
    wallet.failNext = 1
    assert(bet("t-2", 1).isInstanceOf[WalletAnswer.Unacknowledged])
  }

  test("a wallet that cannot be resolved at all is unacknowledged") {
    val nowhere = WalletClient(DirectServices(Map("wallet" -> "http://127.0.0.1:1")), "goldvibe")
    assert(nowhere.placeBet("p", "EUR", "k", "k", "g", "s", 1, false, None, "st8").isInstanceOf[WalletAnswer.Unacknowledged])
  }

  test("the balance is real plus restricted") {
    wallet.set("p-1", "EUR", 9000, 500)
    assertEquals(client.balance("p-1", "EUR").map(_.total), Right(9500L))
  }

  test("win, loss, rollback and provider credit reach their own routes") {
    wallet.set("p-1", "EUR", 1000)
    bet("t-1", 100); bet("t-2", 100); bet("t-3", 100)
    assert(client.creditWin("p-1", "EUR", "c/t-1", "t-1", 500).isInstanceOf[WalletAnswer.Moved])
    assert(client.settleLoss("p-1", "EUR", "c/t-2", "t-2").isInstanceOf[WalletAnswer.Moved])
    assert(client.rollbackBet("p-1", "EUR", "x/t-3", "t-3").isInstanceOf[WalletAnswer.Moved])
    assert(client.providerCredit("p-1", "EUR", "pc-1", 50, "pc-1", "standalone credit").isInstanceOf[WalletAnswer.Moved])
    assertEquals(wallet.openBets("p-1", "EUR"), Vector.empty)
    assertEquals(wallet.balance("p-1", "EUR")._1, 1000L - 300 + 500 + 100 + 50)
  }

  test("the neighbour fakes serve the paths the clients call") {
    assert(neighbours.servesThePathsTheClientsCall)
  }
