package io.eitheror.casino.contract.money

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString, JsonValueCodec}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

class MoneySuite extends munit.FunSuite:

  final case class Bet(amount: MinorUnits, currency: Currency, key: Key)
  private given JsonValueCodec[Bet] = JsonCodecMaker.make

  test("a whole number of minor units is accepted and written back as one") {
    val bet = readFromString[Bet]("""{"amount":1050,"currency":"EUR","key":"t1"}""")
    assertEquals(bet.amount.value, 1050L)
    assertEquals(writeToString(bet), """{"amount":1050,"currency":"EUR","key":"t1"}""")
  }

  for amount <- Vector("10.50", "10.5", "1050.0", "1.05e3", "\"1050\"", "-1") do
    test(s"$amount is refused as an amount") {
      intercept[Exception](readFromString[Bet](s"""{"amount":$amount,"currency":"EUR","key":"t1"}"""))
    }

  test("text at a boundary is refused naming the amount") {
    assertEquals(MinorUnits.parse("10.50"), Left(AmountNotMinorUnits("10.50")))
    assertEquals(MinorUnits.parse("10.50").left.map(_.message), Left("the amount '10.50' is not a whole number of minor units"))
    assertEquals(MinorUnits.parse("1050").map(_.value), Right(1050L))
  }

  test("a win above 2^31 minor units is carried without loss (the original's #662)") {
    val big = 50000L * 500000L
    assert(big > Int.MaxValue)
    val bet = readFromString[Bet](s"""{"amount":$big,"currency":"EUR","key":"t1"}""")
    assertEquals(bet.amount.value, big)
  }

  test("Long.MaxValue round-trips; overflow is refused, not wrapped") {
    assertEquals(readFromString[Bet](s"""{"amount":${Long.MaxValue},"currency":"EUR","key":"t1"}""").amount.value, Long.MaxValue)
    intercept[ArithmeticException](MinorUnits.unsafe(Long.MaxValue) + MinorUnits.unsafe(1))
  }

  test("a signed amount may be negative; an unsigned one may not") {
    assertEquals(SignedMinorUnits.parse("-1050").map(_.value), Right(-1050L))
    assert(MinorUnits.from(-1).isLeft)
  }

  test("a currency is three upper-case letters") {
    assert(Currency.from("EUR").isRight)
    assert(Currency.from("eur").isLeft)
    intercept[Exception](readFromString[Bet]("""{"amount":1,"currency":"EURO","key":"t1"}"""))
  }

  test("a money command without a key, or with an empty one, is refused") {
    assertEquals(Key.from(None), Left(MissingKey))
    assertEquals(Key.from("  "), Left(MissingKey))
    intercept[Exception](readFromString[Bet]("""{"amount":1,"currency":"EUR","key":""}"""))
  }

  test("a command answered once under its key is answered the same way again, and decided once") {
    var decided = 0
    val digest  = Digest.of(Seq("type" -> "bet", "amount" -> "1050"))
    val outcomes0 = KeyedOutcomes[String]()
    val (outcomes1, first) = outcomes0.answer(Key.unsafe("tx-1"), digest) { decided += 1; "debited 1050" }
    val (_, again)         = outcomes1.answer(Key.unsafe("tx-1"), digest) { decided += 1; "debited 1050 again" }
    assertEquals((first, again, decided), (Answer.First("debited 1050"), Answer.Repeated("debited 1050"), 1))
  }

  test("another command under a seen key is told apart from a repeat, and decided never") {
    var decided = 0
    val first   = Digest.of(Seq("type" -> "bet", "amount" -> "1000"))
    val other   = Digest.of(Seq("type" -> "bet", "amount" -> "2000"))
    val (seen, _)    = KeyedOutcomes[String]().answer(Key.unsafe("tx-1"), first)("debited 1000")
    val (after, ans) = seen.answer(Key.unsafe("tx-1"), other) { decided += 1; "debited 2000" }
    assertEquals(ans, Answer.Reused(Seen(first, "debited 1000")))
    assertEquals((after, decided), (seen, 0))
  }

  test("a digest is the same whatever order the fields are given in, and differs when any field does") {
    val a = Digest.of(Seq("type" -> "bet", "amount" -> "1000", "round" -> "r1"))
    assertEquals(Digest.of(Seq("round" -> "r1", "amount" -> "1000", "type" -> "bet")), a)
    assertNotEquals(Digest.of(Seq("type" -> "bet", "amount" -> "1000", "round" -> "r2")), a)
    // a field boundary cannot be forged by moving text between a name and a value
    assertNotEquals(Digest.of(Seq("ab" -> "c")), Digest.of(Seq("a" -> "bc")))
  }
