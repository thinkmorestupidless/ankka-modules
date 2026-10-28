package io.eitheror.casino.simulator.st8

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.simulator.core.{Call, RoundRole}

/**
 * Byte fidelity (plan, "What the next command needs to know"): every body and answer of
 * contracts/st8-wire.md asserted against a literal, never computed.
 */
class WireSuite extends munit.FunSuite with LogCapturing:

  private def call(kind: String, amount: Option[String] = Some("1.50"), providerKind: Option[String] = None) =
    Call(kind, Some("w1"), Some("t1"), Some("r1"), Some("EUR"), amount, providerKind, Some("book-of-ra"), Some("c1"), Some("p1"), Some("goldvibe"))

  private def stub(c: Call)    = String(St8Protocol.body("stub", c), "UTF-8")
  private def harness(c: Call) = String(St8Protocol.body("harness", c), "UTF-8")

  test("stub: a check is the launch ticket alone") {
    assertEquals(stub(Call("check", Some("L1"), None, None, None, None, None, None, None, None, None)), """{"token":"L1"}""")
  }

  test("stub: a debit, keys sorted, strings only, no trailing newline") {
    assertEquals(stub(call("debit")),
      """{"amount":"1.50","currency":"EUR","game_code":"book-of-ra","provider_kind":"debit","round":"r1","token":"w1","transaction_id":"t1"}""")
  }

  test("stub: a free-round debit is 0.00 of kind free_debit") {
    assertEquals(stub(call("debit", Some("0.00"), Some("free_debit"))),
      """{"amount":"0.00","currency":"EUR","game_code":"book-of-ra","provider_kind":"free_debit","round":"r1","token":"w1","transaction_id":"t1"}""")
  }

  test("stub: a loss is a credit of 0.00") {
    assertEquals(stub(call("credit", Some("0.00"))),
      """{"amount":"0.00","currency":"EUR","game_code":"book-of-ra","provider_kind":"credit","round":"r1","token":"w1","transaction_id":"t1"}""")
  }

  test("stub: a cancel has no amount, kind or game code") {
    assertEquals(stub(call("cancel")), """{"cancel_id":"c1","currency":"EUR","round":"r1","token":"w1","transaction_id":"t1"}""")
  }

  test("stub: the amount is the instruction's string, unchanged") {
    assert(stub(call("debit", Some("1.5"))).contains("\"amount\":\"1.5\""))
  }

  test("harness: a debit carries round_closed, bonus null and the provider object, in the harness's order") {
    assertEquals(harness(call("debit", Some("10.00"))),
      """{"token":"w1","transaction_id":"t1","round":"r1","round_closed":false,"currency":"EUR","amount":"10.00","provider_kind":"debit","player":"p1","site":"goldvibe","developer_code":"some","game_code":"book-of-ra","bonus":null,"provider":{"amount":"12.345","currency":"USD","metadata":null,"player":"external","round":"r1","transaction_id":"t1"}}""")
  }

  test("harness: a payout carries the finished bonus and kind free_credit") {
    val body = harness(call("payout", Some("50.00")).copy(token = None, round = None))
    assert(body.contains("\"provider_kind\":\"free_credit\""), body)
    assert(body.contains("""{"bonus_id":"bonus_1234","instance_id":"player_1234_bonus_1234","status":"finished"}"""), body)
    assert(!body.contains("\"token\""), body)
  }

  test("harness: unknown fields are added (NewParams)") {
    val extra = Map("new_future_field" -> com.fasterxml.jackson.databind.node.TextNode("some_value"))
    assert(harness(call("balance").copy(extra = extra)).endsWith(""","new_future_field":"some_value"}"""))
  }

  test("a malformed body is not a JSON object") {
    val bytes = St8Protocol.body("harness", call("debit").copy(malformed = true))
    assert(scala.util.Try(com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes)).isFailure)
  }

  test("Go's encoder: a trailing newline, sorted map keys, HTML-escaped <, > and &") {
    assertEquals(GoJson.encode(GoJson.strings("b" -> "<a&b>", "a" -> "x")), "{\"a\":\"x\",\"b\":\"\\u003ca\\u0026b\\u003e\"}\n")
    assertEquals(GoJson.marshal(GoJson.strings("k" -> "v")), """{"k":"v"}""")
  }

  test("the games listing: three games in seed order; developers and categories sorted by id") {
    val listing = GoJson.encode(Catalogue.load(None).listing)
    assert(listing.startsWith("""{"categories":[{"id":"slots","name":"Slots"},{"id":"table","name":"Table Games"}],"developers":[{"developer_id":"fake-studio-alpha","name":"Fake Studio Alpha"},{"developer_id":"fake-studio-beta","name":"Fake Studio Beta"},{"developer_id":"fake-table-works","name":"Fake Table Works"}],"games":[{"bonus_types":["free_bets","bonus_buy"],"category":"slots","code":"book-of-ra","developer":"fake-studio-alpha","enabled":true,"features":["expanding_symbol","gamble"],"fun_mode":true,"name":"Book of Ra","prohibited_territories":[],"restricted_territories":[],"themes":["egypt","adventure"]}"""), listing)
    assert(listing.endsWith("}]}\n"))
  }

  test("the bonus envelope: instances in the players' order") {
    val bonus = Bonus("b1", Vector("p2", "p1"), 3, "0.10", "free_bets", "EUR", Vector("book-of-ra"), "s", Map("p1" -> Instance("active", 3), "p2" -> Instance("used", 0)))
    val text  = String(OperatorApi.envelope(bonus).body.body, "UTF-8")
    assertEquals(text, """{"bonus":{"bonus_id":"b1","instances":[{"player":"p2","remaining":0,"status":"used"},{"player":"p1","remaining":3,"status":"active"}]},"status":"ok"}""" + "\n")
  }

  test("a plain-text error is http.Error's: the message, a newline, nosniff") {
    val answer = Http.error(409, "wallet token not set — game page not loaded yet")
    assertEquals(answer.status, 409)
    assertEquals(String(answer.body.body, "UTF-8"), "wallet token not set — game page not loaded yet\n")
    assert(answer.headers.contains("X-Content-Type-Options" -> "nosniff"))
  }

  test("answers: HTTP 200 with a word; HTTP 400 is a malformed body, never retried; anything else is retried") {
    val ok = St8Protocol.read(200, """{"status":"ok","balance":"98.50","currency":"EUR"}""".getBytes)
    assertEquals((ok.status, ok.balance, ok.retryable), (Some("ok"), Some("98.50"), false))
    assert(St8Protocol.read(200, """{"status":"error"}""".getBytes).retryable)
    val bad = St8Protocol.read(400, """{"status":"session_expired"}""".getBytes)
    assert(bad.malformedBody && !bad.retryable)
    assert(St8Protocol.read(503, "upstream".getBytes).retryable)
    assertEquals(St8Protocol.read(200, """{"status":"ok","balance":98.5}""".getBytes).balance, Some("98.50"))
  }

  test("a profile's personal fields are redacted before they are recorded (FR-023)") {
    val body = """{"status":"ok","id":"p1","birth_date":"1990-01-01","reg_country":"MT","reg_date":"2026-01-01","bet_limits":"low"}"""
    val text = String(St8Protocol.redact("player_profile", body.getBytes), "UTF-8")
    assert(!text.contains("1990") && !text.contains("MT") && text.contains("\"id\":\"p1\""), text)
  }

  test("what a callback does to its round") {
    assertEquals(St8Protocol.roleOf("debit", None, Some("1.00")), RoundRole.Stake)
    assertEquals(St8Protocol.roleOf("credit", None, Some("0.00")), RoundRole.Loss)
    assertEquals(St8Protocol.roleOf("credit", None, Some("3.00")), RoundRole.Win)
    assertEquals(St8Protocol.roleOf("cancel", None, None), RoundRole.Cancel)
    assertEquals(St8Protocol.roleOf("balance", None, None), RoundRole.Outside)
  }

  test("a signature verifies under the key that made it and no other") {
    val pair  = Signer.generate()
    val other = Signer.generate()
    val body  = """{"token":"L1"}""".getBytes
    val sig   = Signer.sign(body, pair.getPrivate)
    assert(Signer.verify(body, sig, Signer.publicKey(Signer.publicKeyText(pair.getPublic))))
    assert(!Signer.verify(body, sig, other.getPublic))
    assert(!Signer.verify("""{"token":"L2"}""".getBytes, sig, pair.getPublic))
  }

  test("a signature is ASN.1 DER, as Go's SignASN1 writes it") {
    val der = java.util.Base64.getDecoder.decode(Signer.sign("x".getBytes, Signer.generate().getPrivate))
    assertEquals(der(0).toInt, 0x30, "a DER SEQUENCE")
  }
