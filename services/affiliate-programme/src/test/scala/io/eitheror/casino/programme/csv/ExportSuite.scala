package io.eitheror.casino.programme.csv

import java.time.Instant

class ExportSuite extends munit.FunSuite:

  private val row = Export.Row("aff-1", "Ada", "ada@example.com", "IBAN GB33", "goldvibe", "EUR", "2026-08",
    5000, 2000, 300, -100, 7200, "m-1", Some(Instant.parse("2026-09-02T10:00:00Z")))

  test("the header, then one row per payout, amounts in minor units") {
    assertEquals(Export.render(Seq(row)),
      "affiliateId,displayName,email,paymentMethod,brand,currency,period,cpa,revShare,override,adjustments,total,approvedBy,approvedAt\r\n" +
        "aff-1,Ada,ada@example.com,IBAN GB33,goldvibe,EUR,2026-08,5000,2000,300,-100,7200,m-1,2026-09-02T10:00:00Z\r\n")
  }

  test("commas and quotes are quoted") {
    assertEquals(Export.field("Lovelace, Ada"), "\"Lovelace, Ada\"")
    assertEquals(Export.field("the \"engine\""), "\"the \"\"engine\"\"\"")
  }

  test("a value a spreadsheet would run as a formula is defused; a negative amount is not") {
    assertEquals(Export.field("=HYPERLINK(\"x\")"), "\"'=HYPERLINK(\"\"x\"\")\"")
    assertEquals(Export.field("@SUM(A1)"), "'@SUM(A1)")
    assertEquals(Export.field("-cmd"), "'-cmd")
    assertEquals(Export.field("-100"), "-100")
  }
