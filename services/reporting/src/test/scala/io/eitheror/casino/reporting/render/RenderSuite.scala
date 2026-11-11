package io.eitheror.casino.reporting.render

class RenderSuite extends munit.FunSuite:

  test("money is the original's decimal string by the currency's places") {
    assertEquals(Money(9500, "EUR"), "95.00")
    assertEquals(Money(-250, "EUR"), "-2.50")
    assertEquals(Money(0, "EUR"), "0.00")
    assertEquals(Money(100, "JPY"), "100")
    assertEquals(Money(1234, "KWD"), "1.234")
  }

  test("an email is cut at n characters with an ellipsis") {
    assertEquals(Money.truncated("someone.with.a.long.address@example.com", 25), "someone.with.a.long.addre…")
    assertEquals(Money.truncated("short@x.io", 25), "short@x.io")
  }

  test("a cell is quoted only when it holds a comma, a quote or a line break, quotes doubled") {
    assertEquals(Csv.cell("plain"), "plain")
    assertEquals(Csv.cell("a,b"), "\"a,b\"")
    assertEquals(Csv.cell("say \"hi\""), "\"say \"\"hi\"\"\"")
    assertEquals(Csv.cell("two\nlines"), "\"two\nlines\"")
  }

  test("rows end in CRLF, the header first") {
    assertEquals(Csv(Seq("date", "ggr"), Seq(Seq("2026-11-01", "1.00"))), "date,ggr\r\n2026-11-01,1.00\r\n")
  }
