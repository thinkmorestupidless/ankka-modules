package io.eitheror.casino.wallet.it

/**
 * Spec 030 W3 over HTTP: `PUT /v1/casino/backoffice/<brand>/wallet-config/rewards`, admitted to
 * `config.brand`, replaces the reward settings whole, leaves the turnover terms, records the operator,
 * answers as `GET /wallet-config` reads, and refuses a wrong value 400 naming its field with nothing changed.
 */
class RewardSettingsItSuite extends Harness:

  private def config(rest: String = "") = s"/v1/casino/backoffice/$brand/wallet-config$rest"
  private val admin   = TestTokens.operator("op-admin", s"brand_$brand,${TestTokens.in("brand_admin", brand)}")
  private val finance = TestTokens.operator("op-finance", s"brand_$brand,${TestTokens.in("brand_finance", brand)}")

  private val settings =
    """{"contributionRateByGameType":{"slots":100,"table":10},"bonusWageringCounts":false,"partialSplitsAllowed":false,"maxConcurrentAwards":2}"""

  test("before any write the read answers every value in force, the defaults and no limit included") {
    val (status, read) = sendWithToken("GET", config(), admin)
    assertEquals(status, 200, read)
    assertEquals(read, """{"contributionRateByGameType":{},"bonusWageringCounts":true,"partialSplitsAllowed":true,""" +
      """"maxConcurrentAwards":null,"turnover":{"multiplier":1,"definition":"gross_wagered"},"updatedBy":"","updatedAt":null}""")
  }

  test("an admin's settings replace the switches, keep the turnover terms, and the read answers them") {
    assertEquals(sendWithToken("PUT", config("/turnover"), admin, Some("""{"multiplier":3}"""))._1, 200)
    val (status, put) = sendWithToken("PUT", config("/rewards"), admin, Some(settings))
    assertEquals(status, 200, put)
    val (_, read) = sendWithToken("GET", config(), admin)
    assertEquals(read, put)
    assert(put.contains(""""contributionRateByGameType":{"slots":100,"table":10}"""), put)
    assertEquals((field(put, "bonusWageringCounts"), field(put, "partialSplitsAllowed"), field(put, "maxConcurrentAwards")),
      (Some("false"), Some("false"), Some("2")))
    assertEquals((field(put, "multiplier"), field(put, "updatedBy")), (Some("3"), Some("op-admin")))
    assert(field(put, "updatedAt").nonEmpty, put)

    // Replaced whole: a table without `table` puts it back at the default; no limit is the limit left out.
    val (_, again) = sendWithToken("PUT", config("/rewards"), admin,
      Some("""{"contributionRateByGameType":{"slots":50},"bonusWageringCounts":true,"partialSplitsAllowed":true,"maxConcurrentAwards":null}"""))
    assert(again.contains(""""contributionRateByGameType":{"slots":50},"bonusWageringCounts":true,"partialSplitsAllowed":true,"maxConcurrentAwards":null,"""), again)
    assertEquals((field(again, "maxConcurrentAwards"), field(again, "multiplier")), (None, Some("3")))
  }

  test("a wrong value is refused 400 naming its field, and nothing changes") {
    val before = sendWithToken("GET", config(), admin)._2
    val (status, body) = sendWithToken("PUT", config("/rewards"), admin,
      Some("""{"contributionRateByGameType":{"slots":100,"table":120},"bonusWageringCounts":true,"partialSplitsAllowed":true}"""))
    assertEquals((status, field(body, "error"), field(body, "field")), (400, Some("INVALID_REQUEST"), Some("contributionRateByGameType.table")), body)
    val (s2, b2) = sendWithToken("PUT", config("/rewards"), admin,
      Some("""{"contributionRateByGameType":{},"bonusWageringCounts":true,"partialSplitsAllowed":true,"maxConcurrentAwards":0}"""))
    assertEquals((s2, field(b2, "field")), (400, Some("maxConcurrentAwards")), b2)
    assertEquals(sendWithToken("GET", config(), admin)._2, before)
  }

  test("an operator without config.brand is refused, naming the permission") {
    val (status, body) = sendWithToken("PUT", config("/rewards"), finance, Some(settings))
    assertEquals(status, 403, body)
    assert(body.contains("config.brand"), body)
  }
