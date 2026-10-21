package io.eitheror.casino.pspgateway.http

/** FR-012: forms over the raw body as Go's `url.Values` reads and writes them, and DreamzPay's sniffing. */
class FormSuite extends munit.FunSuite:

  test("a repeated key keeps every value in order; get is the first") {
    val f = Form.decode("a=1&b=2&a=3")
    assertEquals(f.all("a"), Vector("1", "3"))
    assertEquals(f.get("a"), Some("1"))
    assertEquals(f.values("a"), Vector("1", "3"))
  }

  test("an empty value is present, an absent key is not") {
    val f = Form.decode("a=&b")
    assertEquals(f.get("a"), Some(""))
    assertEquals(f.get("b"), Some(""))
    assertEquals(f.text("a"), None)
    assertEquals(f.get("c"), None)
  }

  test("decoding reads + and percent escapes; a pair that does not decode is dropped") {
    val f = Form.decode("name=J%C3%BCrgen+M&bad=%zz&ok=1")
    assertEquals(f.get("name"), Some("Jürgen M"))
    assertEquals(f.get("bad"), None)
    assertEquals(f.get("ok"), Some("1"))
  }

  test("encoding sorts by key as Values.Encode does, values in order") {
    assertEquals(Form.of("b" -> "2", "a" -> "x y", "b" -> "1").encode, "a=x+y&b=2&b=1")
    assertEquals(Form.decode(Form.of("k" -> "a&b=c").encode).get("k"), Some("a&b=c"))
  }

  test("an empty body is told apart from a body of another content type") {
    assert(Raw("").isEmpty)
    assert(Raw("   ").isEmpty)
    assert(!Raw("""{"a":1}""", Some("application/json")).isEmpty)
    assertEquals(Raw("", Some("application/x-www-form-urlencoded")).sniffed, Raw.Body.Empty)
  }

  test("the media type ignores parameters and case") {
    val r = Raw("a=1", Some("Application/X-WWW-Form-Urlencoded; charset=UTF-8"))
    assert(r.isForm)
    assert(!r.isJson)
    assert(Raw("{}", Some("application/vnd.api+json")).isJson)
  }

  test("DreamzPay's reading: by content type, with the other format as the fallback") {
    assertEquals(Raw("paymentId=1&status=Y", Some("application/x-www-form-urlencoded")).sniffed.text("status"), Some("Y"))
    assertEquals(Raw("""{"status":"Y"}""", Some("application/json")).sniffed.text("status"), Some("Y"))
    assertEquals(Raw("""{"status":"Y"}""", Some("application/x-www-form-urlencoded")).sniffed.text("status"), Some("Y"),
      "a JSON body under a form content type")
    assertEquals(Raw("status=Y", Some("application/json")).sniffed.text("status"), Some("Y"), "a form under JSON")
    assertEquals(Raw("status=Y", None).sniffed.text("status"), Some("Y"))
    assertEquals(Raw("""{"status":"Y"}""", Some("text/plain")).sniffed.text("status"), Some("Y"))
  }

  test("a JSON number reads as its text, exactly") {
    assertEquals(Raw("""{"amount":15.50,"n":3}""", Some("application/json")).sniffed.text("amount"), Some("15.50"))
    assertEquals(Raw("""{"amount":15.50,"n":3}""", Some("application/json")).sniffed.text("n"), Some("3"))
  }

  test("headers are read ignoring case") {
    assertEquals(Raw("", headers = Vector("X-Signature" -> "s")).header("x-signature"), Some("s"))
  }
