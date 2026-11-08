package io.eitheror.casino.programme.postbacks

class MacrosSuite extends munit.FunSuite:

  test("every macro filled, with the original's defaults") {
    assertEquals(Macros.render("https://t.example/pb?e={event}&c={clickid}&p={payout}&s={status}", "ftd", Some("abc")),
      "https://t.example/pb?e=ftd&c=abc&p=0&s=ok")
  }

  test("a click id cannot add parameters: it is percent-encoded") {
    assertEquals(Macros.render("https://t.example/pb?c={clickid}", "ftd", Some("x&payout=9999")),
      "https://t.example/pb?c=x%26payout%3D9999")
  }

  test("a space is %20, not +") {
    assertEquals(Macros.render("https://t.example/{status}", "e", None, status = Some("a b")), "https://t.example/a%20b")
  }

  test("no click id is an empty value; a payout given is used") {
    assertEquals(Macros.render("c={clickid}&p={payout}", "e", None, payout = Some("5000")), "c=&p=5000")
  }
