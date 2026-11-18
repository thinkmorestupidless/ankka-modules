package io.eitheror.casino.contract.money

import java.nio.file.{Files, Paths}

/** The published currency table (spec 027 FR-037): the rule `Amounts` writes by, and the file is current. */
class CurrenciesSuite extends munit.FunSuite:

  private def exp(code: String) = Currencies.table.collectFirst { case (`code`, e) => e }

  test("each currency carries the exponent Amounts renders by") {
    assertEquals(exp("EUR"), Some(2))
    assertEquals(exp("JPY"), Some(0))
    assertEquals(exp("KWD"), Some(3))
    assertEquals(exp("BRL"), Some(2))
    Currencies.table.foreach((code, e) => assertEquals(e, Amounts.exponent(Currency.unsafe(code)), code))
  }

  test("a code the JDK gives no digits is 2, as the wallet treats it") {
    assertEquals(exp("XAU"), Some(2))
    assertEquals(exp("XXX"), Some(2))
  }

  test("the codes are sorted and each appears once") {
    val codes = Currencies.table.map(_._1)
    assertEquals(codes, codes.sorted.distinct)
  }

  test("contract/schemas/currencies.v1.json is the table") {
    val root = Iterator.iterate(Paths.get("").toAbsolutePath)(_.getParent).takeWhile(_ != null)
      .find(p => Files.exists(p.resolve("contract/schemas"))).get
    assertEquals(Files.readString(root.resolve("contract/schemas/currencies.v1.json")), Currencies.render())
  }
