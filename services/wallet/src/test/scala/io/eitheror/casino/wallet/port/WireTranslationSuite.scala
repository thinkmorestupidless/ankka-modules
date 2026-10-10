package io.eitheror.casino.wallet.port

import io.eitheror.casino.contract.port.{Recording, Replay}

import java.nio.file.Paths

/** The translation on a hand-written recording, and Replay's verdict on it, with no runtime. */
class WireTranslationSuite extends munit.FunSuite:

  private def json(s: String) = Some(WireTranslation.parse(s))

  private val recording = Recording.parse(Paths.get("hand-written.json"), WireTranslation.parse(
    """{"scenario":"a deposit, a bet and a win","steps":[
      |{"request":{"method":"POST","path":"/internal/v1/wallet/spinvibe/p1/EUR/deposit","body":{"paymentRef":"pay-1","amount":"100.00","terminalId":"t1"}},
      | "response":{"status":200,"body":{"realBalance":"100.00","bonusBalance":"0.00","currency":"EUR","txnId":"x"}}},
      |{"request":{"method":"POST","path":"/v1/wallet/spinvibe/p1/EUR/bet","body":{"roundId":"r1","gameId":"g","amount":"10.00"}},
      | "response":{"status":200,"body":{"realBalance":"90.00","bonusBalance":"0.00","walletType":"real"}}},
      |{"request":{"method":"POST","path":"/v1/wallet/spinvibe/p1/EUR/bet","body":{"roundId":"r2","gameId":"g","amount":"1000.00"}},
      | "response":{"status":409,"body":{"error":"INSUFFICIENT_FUNDS"}}}
      |],"vary":{"$.txnId":"generated"}}""".stripMargin)).fold(e => fail(e), identity)

  test("decimals become minor units and keep their sign") {
    assertEquals(Seq("100.00", "10.5", "0.07", "-2.50").map(s => WireTranslation.minor(WireTranslation.parse(s"\"$s\""))),
      Seq(Some(10000L), Some(1050L), Some(7L), Some(-250L)))
  }

  test("each original route becomes the rebuild's, the brand's own, with the original's fields renamed and a key minted per step") {
    val deposit = WireTranslation.request("s", 0, recording.steps(0).request, "goldvibe")
    assertEquals(deposit.request.path, "/v1/casino/brands/goldvibe/wallets/p1/EUR/deposits")
    assertEquals(deposit.request.body.get.toString, """{"paymentId":"pay-1","amount":10000,"terminal":"t1"}""")
    val bet = WireTranslation.request("s", 1, recording.steps(1).request, "goldvibe")
    assertEquals((bet.request.path, bet.key), ("/v1/casino/brands/goldvibe/wallets/p1/EUR/bets", Some("s#1")))
    assertEquals(bet.request.body.get.get("amount").asLong(), 1000L)
  }

  test("an answer is read back in the recording's terms: its fields only, its decimals; and Replay agrees") {
    // What the rebuild answers to each step, in its own terms.
    val rebuild = Vector(
      Recording.Response(200, json("""{"realBalance":10000,"bonusBalance":0,"currency":"EUR","txnId":"t-9","depositOrdinal":1}""")),
      Recording.Response(200, json("""{"realBalance":9000,"bonusBalance":0,"currency":"EUR","walletType":"real","realLeg":1000}""")),
      Recording.Response(409, json("""{"error":"INSUFFICIENT_FUNDS","message":"Insufficient funds in real and bonus wallets"}"""))
    )
    var i = 0
    val differences = Replay.run(recording, { request =>
      val t = WireTranslation.request(recording.scenario, i, request, "goldvibe")
      val answer = WireTranslation.answer(t, recording.steps(i).response, rebuild(i))
      i += 1
      answer
    })
    assertEquals(differences, Vector.empty)
  }

  test("a real difference survives the translation and is named by its path") {
    val wrong = Recording.Response(200, json("""{"realBalance":9100,"bonusBalance":0,"walletType":"real"}"""))
    val t     = WireTranslation.request("s", 1, recording.steps(1).request, "goldvibe")
    val read  = WireTranslation.answer(t, recording.steps(1).response, wrong)
    assertEquals(read.body.get.get("realBalance").asText(), "91.00")
    assertNotEquals(read.body, recording.steps(1).response.body)
  }
