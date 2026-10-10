package io.eitheror.casino.wallet.it

import com.thinkmorestupidless.ankka.http.Caller
import io.eitheror.casino.wallet.api.RgAnswer
import io.eitheror.casino.wallet.entity.WalletEntity

import java.util.concurrent.{Executors, TimeUnit}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** quickstart.md section 6: the whole service, over HTTP, against Postgres. */
class WalletItSuite extends Harness:

  private val merchant = Caller.Service("payments", "merchant")

  private def deposit(player: String, paymentId: String, amount: Long): (Int, String) =
    send("POST", s"${wallet(player)}/deposits",
      Some(s"""{"paymentId":"$paymentId","amount":$amount,"terminal":"t1","psp":"dreamzpay","scheme":"card","paymentProvider":"dreamzpay"}"""),
      as = merchant)

  private def bet(player: String, key: String, round: String, amount: Long): (Int, String) =
    send("POST", s"${wallet(player)}/bets", Some(s"""{"key":"$key","roundId":"$round","gameId":"slot-a","sessionId":"s1","amount":$amount}"""))

  test("a deposit, a bet and a win: real balance 10000, 9000, 11500, and each movement is a fact on its topic") {
    assertEquals(field(deposit("q5", "pay-1", 10000)._2, "realBalance"), Some("10000"))
    val (status, placed) = bet("q5", "tx-1", "r1", 1000)
    assertEquals((status, field(placed, "realBalance"), field(placed, "walletType")), (200, Some("9000"), Some("real")))
    val (_, won) = send("POST", s"${wallet("q5")}/wins", Some("""{"key":"tx-2","roundId":"r1","amount":2500}"""))
    assertEquals(field(won, "realBalance"), Some("11500"))
    assertEquals(field(send("GET", s"${wallet("q5")}/balance")._2, "realBalance"), Some("11500"))

    val money = eventually(facts("money.transactions", "q5/EUR"))(_.size >= 3)
    assert(money.exists(f => f.contains("\"deposit_confirmed\"") && f.contains("\"deposit_ordinal\":1") && f.contains("\"real_balance_after\":10000")), money.mkString("\n"))
    assert(money.exists(f => f.contains("\"bet_placed\"") && f.contains("\"real_balance_after\":9000")), money.mkString("\n"))
    assert(money.exists(f => f.contains("\"win_credited\"") && f.contains("\"real_balance_after\":11500")), money.mkString("\n"))
    val captured = eventually(facts("payments.payments", "pay-1"))(_.nonEmpty)
    assert(captured.head.contains("\"payment.captured\"") && captured.head.contains("\"payment_provider\":\"dreamzpay\""), captured.head)
  }

  test("a deposit sent again under its payment id answers the first outcome and credits once") {
    val first = deposit("idem", "pay-9", 5000)
    val again = deposit("idem", "pay-9", 5000)
    assertEquals(again, first)
    assertEquals(field(send("GET", s"${wallet("idem")}/balance")._2, "realBalance"), Some("5000"))
  }

  test("responsible-gambling's silence refuses a deposit as RG_UNAVAILABLE, credits nothing, and the wallet never sees the key (FR-022)") {
    rg.silent()
    val (status, body) = deposit("rg1", "pay-1", 5000)
    assertEquals((status, field(body, "error")), (503, Some("RG_UNAVAILABLE")))
    rg.allow()
    assertEquals(field(send("GET", s"${wallet("rg1")}/balance")._2, "realBalance"), Some("0"))
    assert(testKit.componentClient.forEventSourcedEntity(WalletEntity.id("rg1", "EUR")).call(WalletEntity.outcomeOf).invoke("pay-1").isEmpty,
      "the wallet saw a deposit responsible-gambling refused")
  }

  test("an over-limit deposit credits nothing, names the limit, and is refused the same way when sent again (FR-022)") {
    rg.answerDeposits(RgAnswer.OverLimit("daily", 10000, 8000, 2000))
    val (status, body) = deposit("rg2", "pay-7", 5000)
    assertEquals((status, field(body, "error")), (409, Some("OVER_LIMIT")))
    assert(body.contains("\"limit\":\"10000\"") && body.contains("\"remaining\":\"2000\""), body)
    rg.allow()
    assertEquals(deposit("rg2", "pay-7", 5000), (status, body))
    assertEquals(field(send("GET", s"${wallet("rg2")}/balance")._2, "realBalance"), Some("0"))
  }

  test("the deposit sums handed to responsible-gambling are the wallet's own, exact before the deposit") {
    deposit("sums", "pay-1", 1000)
    deposit("sums", "pay-2", 2500)
    assertEquals(rg.sumsAsked.lastOption.map(_.last24h), Some(1000L))
  }

  test("an amount that is not whole minor units is refused naming it, and a command without a key is refused") {
    val (status, body) = send("POST", s"${wallet("bad")}/bets", Some("""{"key":"tx-1","roundId":"r1","gameId":"g","amount":10.50}"""))
    assertEquals((status, field(body, "error")), (400, Some("INVALID_REQUEST")))
    assert(body.contains("'10.50'"), body)
    assertEquals(field(send("POST", s"${wallet("bad")}/bets", Some("""{"roundId":"r1","gameId":"g","amount":100}"""))._2, "error"), Some("MISSING_KEY"))
  }

  test("another brand's path is not this project's route") {
    assertEquals(send("GET", "/v1/casino/brands/otherbrand/wallets/q5/EUR/balance")._1, 404)
  }

  test("SC-002: 200 concurrent bets on one wallet leave real = start − Σ accepted, and none the balance could not fund") {
    deposit("race", "pay-1", 100000)
    val pool = Executors.newFixedThreadPool(32)
    given ExecutionContext = ExecutionContext.fromExecutor(pool)
    try
      val answers = Await.result(Future.sequence((1 to 200).map(i => Future(bet("race", s"tx-$i", s"r$i", 1000)))), 2.minutes)
      val accepted = answers.count(_._1 == 200)
      val refused  = answers.filter(_._1 != 200).map(a => field(a._2, "error")).distinct
      assertEquals(accepted, 100)
      assertEquals(refused.toVector, Vector[Option[String]](Some("INSUFFICIENT_FUNDS")))
      assertEquals(field(send("GET", s"${wallet("race")}/balance")._2, "realBalance"), Some((100000 - accepted * 1000).toString))
    finally
      pool.shutdown()
      pool.awaitTermination(10, TimeUnit.SECONDS): Unit
  }

  test("a wallet's balance and keys survive losing every entity from memory") {
    deposit("durable", "pay-1", 7000)
    bet("durable", "tx-1", "r1", 2000)
    testKit.restartService()
    val client = testKit.componentClient.forEventSourcedEntity(WalletEntity.id("durable", "EUR"))
    assertEquals(client.call(WalletEntity.balance).invoke().realBalance, 5000L)
    assert(client.call(WalletEntity.outcomeOf).invoke("tx-1").isDefined)
  }

  test("the players service's erasure request is confirmed, and no other caller may make one") {
    val (status, body) = send("POST", s"/v1/casino/brands/$brand/erasure/players/p1", as = Caller.Service("local", "players"))
    assertEquals((status, field(body, "confirmed")), (200, Some("true")))
    assertEquals(send("POST", s"/v1/casino/brands/$brand/erasure/players/p1")._1, 403)
  }
