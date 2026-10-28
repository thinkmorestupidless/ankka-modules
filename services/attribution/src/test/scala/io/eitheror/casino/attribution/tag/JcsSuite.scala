package io.eitheror.casino.attribution.tag

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.Json

class JcsSuite extends munit.FunSuite with LogCapturing:
  private def jcs(text: String): String = Jcs.canonical(Json.parse(text).get)

  test("RFC 8785 §3.2.2: the example's numbers, literals and string escapes") {
    val input  = "{\"numbers\": [333333333.33333329, 1E30, 4.50, 2e-3, 0.000000000000000000000000001], \"string\": \"\\u20ac$\\u000F\\u000aA'\\u0042\\u0022\\u005c\\\\\\\"\\/\", \"literals\": [null, true, false]}"
    val expect = "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27],\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\\\\\"/\"}"
    assertEquals(jcs(input), expect)
  }

  test("RFC 8785 §3.2.3: members sorted by their UTF-16 code units") {
    val input = "{\"\\u20ac\": \"Euro Sign\", \"\\r\": \"Carriage Return\", \"\\ufb33\": \"Dalet\", \"1\": \"One\", \"\\ud83d\\ude00\": \"Grinning Face\", \"\\u0080\": \"Control\", \"\\u00f6\": \"o Umlaut\"}"
    val names = Json.parse(jcs(input)).get.fieldNames
    val order = Iterator.continually(names).takeWhile(_.hasNext).map(_.next()).toVector
    assertEquals(order, Vector("\r", "1", "\u0080", "ö", "€", "😀", "דּ"))
  }

  test("member order does not change the output, nor does whitespace") {
    assertEquals(jcs("""{"b": 1, "a": {"y": [1, 2], "x": "s"}}"""), jcs("""{"a":{"x":"s","y":[1,2]},"b":1}"""))
    assertEquals(jcs("""{"b": 1, "a": 2}"""), """{"a":2,"b":1}""")
  }

  test("a nested array of facts keeps the array's order and sorts each fact's members") {
    val facts = """[{"type":"deposit","amount":5000,"factId":"deposit/p/1"},{"type":"click","factId":"click/c","subIds":["b","a"]}]"""
    assertEquals(jcs(facts), """[{"amount":5000,"factId":"deposit/p/1","type":"deposit"},{"factId":"click/c","subIds":["b","a"],"type":"click"}]""")
  }

  test("ECMAScript's number forms: integers, large and small, negative") {
    val cases = Vector("0" -> "0", "-0" -> "0", "1" -> "1", "-12" -> "-12", "100" -> "100", "1e21" -> "1e+21",
      "123456789012345680000" -> "123456789012345680000", "0.000001" -> "0.000001", "0.0000001" -> "1e-7", "1.5e-7" -> "1.5e-7",
      "9007199254740991" -> "9007199254740991")
    cases.foreach((in, out) => assertEquals(Jcs.number(java.math.BigDecimal(in)), out, in))
  }
