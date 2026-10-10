package io.eitheror.casino.tools.tlfixtures

import com.fasterxml.jackson.databind.ObjectMapper

class ParserSuite extends munit.FunSuite:

  private val mapper = ObjectMapper()

  /** The first case of the original's credit-win.test.tl, verbatim. */
  private val creditWin =
    """# credit_win — a comment, as the original's files open
      |
      |test "real-funded win — credits real balance" {
      |  given twin {
      |    id: "wallet/test1"
      |    concepts: ["Wallet"]
      |    real_balance: 10000
      |    restricted_balance: 0
      |    player_id: "p1"
      |    brand_id: "testbrand"
      |    currency: "GBP"
      |  }
      |  given twin {
      |    id: "transaction/bet-001"
      |    concepts: ["Transaction"]
      |    status: "open"
      |  }
      |  when CreditWin { round_id: "r1", amount: 3000, amount_str: "30.00", currency: "GBP", bet_tx_id: "transaction/bet-001", settled_bet_tx_id: "transaction/bet-001", created_at: "2026-09-22T10:00:00Z", funded_breakdown: "" }
      |  then {
      |    mutation 0: set_properties("wallet/test1", { real_balance: 13000, open_rounds: "" })
      |    mutation 1: set_properties("transaction/bet-001", { status: "settled" })
      |    mutation 2: add_concept("transaction.new-id", { concept: "ent.wallet.transaction_v1" })
      |    mutation 3: add_edge("wallet/test1", "HAS_TRANSACTION", _)
      |    count mutation: 4
      |    result: { real_balance: 130.00, wallet_type: "real" }
      |  }
      |}
      |""".stripMargin

  test("the first credit-win case parses to its given, when and then") {
    val cases = Parser.parse("credit-win.test.tl", creditWin)
    assertEquals(cases.map(_.name), Vector("real-funded win — credits real balance"))
    val c = mapper.readTree(mapper.writeValueAsString(cases.head.json))
    assertEquals(c.get("line").asInt(), 3)

    val givens = c.get("given")
    assertEquals(givens.size(), 2)
    assertEquals(givens.get(0).get("id").asText(), "wallet/test1")
    assertEquals(givens.get(0).get("concepts").get(0).asText(), "Wallet")
    assertEquals(givens.get(0).get("real_balance").asLong(), 10000L)
    assertEquals(givens.get(1).get("status").asText(), "open")

    assertEquals(c.get("when").get("command").asText(), "CreditWin")
    assertEquals(c.get("when").get("params").get("amount").asLong(), 3000L)
    assertEquals(c.get("when").get("params").get("funded_breakdown").asText(), "")

    val t = c.get("then")
    assertEquals(t.get("mutations").size(), 4)
    val m0 = t.get("mutations").get(0)
    assertEquals((m0.get("index").asInt(), m0.get("op").asText(), m0.get("target").asText()), (0, "set_properties", "wallet/test1"))
    assertEquals(m0.get("properties").get("real_balance").asLong(), 13000L)
    val edge = t.get("mutations").get(3)
    assertEquals(edge.get("args").get(0).asText(), "HAS_TRANSACTION")
    assert(edge.get("args").get(1).get("$any").asBoolean())
    assertEquals(t.get("mutation_count").asInt(), 4)
    assertEquals(t.get("result").get("real_balance").decimalValue().compareTo(java.math.BigDecimal("130.00")), 0)
    assert(t.get("error").isNull)
  }

  test("a decimal keeps its exact text") {
    val c = Parser.parse("x", creditWin).head
    assertEquals(mapper.writeValueAsString(c.json.get("then").get("result").get("real_balance")), "130.00")
  }

  test("then error(\"CODE\") is an error with that code and no mutations") {
    val src = """test "t" { given twin { id: "w" } when Reverse { request_id: "r" } then error("WITHDRAWAL_NOT_IN_COMPLETED_STATE") }"""
    val t   = Parser.parse("x", src).head.json.get("then")
    assertEquals(t.get("error").get("code").asText(), "WITHDRAWAL_NOT_IN_COMPLETED_STATE")
    assertEquals(t.get("mutations").size(), 0)
  }

  test("a malformed file fails naming the file and the line") {
    val src =
      """test "ok" {
        |  given twin { id: "w" }
        |  when Deposit { amount: 100 }
        |  then {
        |    mutation 0 set_properties("w", { real_balance: 100 })
        |  }
        |}
        |""".stripMargin
    val e = intercept[Parser.ParseError](Parser.parse("deposit.test.tl", src))
    assertEquals((e.file, e.line), ("deposit.test.tl", 5))
    assert(e.getMessage.contains("expected ':'"), e.getMessage)
  }

  test("an unknown statement in a then block fails rather than being skipped") {
    val src = """test "t" { given twin { id: "w" } when X { } then { expect: 1 } }"""
    val e   = intercept[Parser.ParseError](Parser.parse("x", src))
    assert(e.getMessage.contains("unknown statement 'expect'"), e.getMessage)
  }
