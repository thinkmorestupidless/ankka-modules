package io.eitheror.casino.publicapi.wire

import java.time.Instant

/** Original `test/money.test.ts`, over minor units: nothing here touches a float. */
class MoneySuite extends munit.FunSuite:

  test("cents become major units with two places") {
    assertEquals(Money.cents(0), "0.00")
    assertEquals(Money.cents(5), "0.05")
    assertEquals(Money.cents(12345), "123.45")
    assertEquals(Money.cents(-250), "-2.50")
  }

  test("amounts beyond a double's precision survive exactly") {
    assertEquals(Money.of(1234567890123456789L, "EUR"), Right("12345678901234567.89"))
    assertEquals(Money.of(30L, "BRL"), Right("0.30"))
  }

  test("every currency the brands run has two places; one that does not is refused, never rounded") {
    Vector("EUR", "BRL", "USD", "GBP").foreach(c => assertEquals(Money.of(1250, c), Right("12.50"), c))
    assertEquals(Money.of(100, "JPY").left.map(_.exponent), Left(0))
    assertEquals(Money.of(1000, "KWD").left.map(_.exponent), Left(3))
  }

  test("every rendered amount matches the contract's Money pattern") {
    Vector(0L, 1L, -1L, 99L, 100L, 1050L, Long.MaxValue, Long.MinValue + 1).foreach { m =>
      assert("^-?[0-9]+\\.[0-9]{2}$".r.matches(Money.of(m, "EUR").toOption.get), m.toString)
    }
  }

  test("times are ISO 8601 with Z") {
    assertEquals(Times.of(Instant.parse("2026-11-12T10:00:00.120Z")), "2026-11-12T10:00:00.120Z")
    assertEquals(Times.of(Instant.parse("2026-11-12T10:00:00Z")), "2026-11-12T10:00:00Z")
  }
