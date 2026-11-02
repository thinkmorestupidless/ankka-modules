package io.eitheror.casino.rewards.port

import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.port.WireTranslation.*

/** contracts/wire.md, row by row: each recorded request as this service takes it, and its answer read back. */
class WireTranslationSuite extends munit.FunSuite:
  private val ids = Ids()
  ids.pair(Kind.Offer, "orig-o", "id-o")
  ids.pair(Kind.Reward, "orig-c", "id-r")
  ids.pair(Kind.Grant, "orig-g", "id-r")
  ids.pair(Kind.Player, "orig-p", "id-p")
  private val ctx = Context("goldvibe", "id-p", ids)
  private def body(text: String) = Json.parse(text)
  private def tr(method: String, path: String, b: Option[String] = None): Request =
    WireTranslation.request(method, path, b.flatMap(body), ctx) match
      case Translated.Ours(r) => r
      case other              => fail(s"$method $path is not served: $other")

  test("amounts: × 100 for the recordings' currencies; more places than the currency is a failure, never a rounding"):
    assertEquals((minor(BigDecimal("12.34"), "EUR"), minor(BigDecimal("2"), "ZAR"), minor(BigDecimal("0.1"), "GBP")), (1234L, 200L, 10L))
    intercept[AssertionError](minor(BigDecimal("1.234"), "EUR"))
    assertEquals(major(1234L), BigDecimal("12.34"))
    assertEquals(money(1000L, Some(Json.mapper.getNodeFactory.textNode("x"))).asText, "10.00")
    assertEquals(money(50000L, None).decimalValue.toPlainString, "500")

  test("the player's routes move under the brand's rewards, the cancel becomes a POST naming this service's reward"):
    assertEquals(tr("POST", "/v1/casino/bonus/spinvibe/enter-code", Some("""{"code":"A"}""")).path, "/v1/casino/brands/goldvibe/rewards/id-p/codes")
    val c = tr("DELETE", "/v1/casino/bonus/spinvibe/cancel", Some("""{"grantRef":"bonus-claims/orig-c"}"""))
    assertEquals((c.row, c.method, c.path, c.body.flatMap(_.text("grantRef"))),
      (Row.Cancel, "POST", "/v1/casino/brands/goldvibe/rewards/id-p/cancel", Some("id-r")))
    assertEquals(tr("POST", "/v1/casino/bonus/promo-code/spinvibe", Some("""{"action":"preview","code":"A"}""")).row, Row.Preview)

  test("routes wire.md marks not carried are named so, never translated"):
    assert(WireTranslation.request("POST", "/v1/casino/bonus/claim/spinvibe", None, ctx).isInstanceOf[Translated.NotCarried])
    assert(WireTranslation.request("POST", "/v1/casino/bonus/promo-code/spinvibe", body("""{"action":"store"}"""), ctx)
      .isInstanceOf[Translated.NotCarried])
    assertEquals(WireTranslation.request("GET", "/twins/wallet/w", None, ctx), Translated.NotOurs)

  test("an operator's award in minor units, the multiplier whole, the offer by this service's id"):
    val g = tr("POST", "/v1/casino/bonus/grant/spinvibe/orig-p", Some("""{"bonusAmount":"20.00","currency":"EUR","wager":"5","offerId":"orig-o"}"""))
    assertEquals(g.path, "/v1/casino/brands/goldvibe/reward-grants/id-p")
    assertEquals((g.body.flatMap(_.long("bonusAmount")), g.body.flatMap(_.long("wager")), g.body.flatMap(_.text("offerId"))),
      (Some(2000L), Some(5L), Some("id-o")))

  test("the reorder becomes the order, the revoke a POST, the twin reads the reward's read"):
    val o = tr("PUT", "/v1/casino/bonus/offers/spinvibe/reorder", Some("""{"orderedOfferIds":["orig-o"]}"""))
    assertEquals((o.path, o.body.map(_.strings("orderedOfferIds"))), ("/v1/casino/brands/goldvibe/offers/order", Some(Vector("id-o"))))
    assertEquals(tr("PATCH", "/v1/casino/spinvibe/bonuses/bonus-claims/orig-c/revoke", Some("{}")).path,
      "/v1/casino/brands/goldvibe/reward-grants/id-r/revoke")
    assertEquals(tr("GET", "/twins/free-spins-grants/orig-g?include=concepts").path, "/v1/casino/brands/goldvibe/reward-grants/id-r")

  test("a funds award's answer in the original's strings"):
    val g        = tr("POST", "/v1/casino/bonus/grant/spinvibe/orig-p", Some("""{"bonusAmount":"20.00","currency":"EUR","wager":"5"}"""))
    val (s, out) = answer(g, 200, body("""{"status":"granted","bonusAmount":2000,"wageringRequired":10000}"""),
      body("""{"status":"granted","bonusAmount":"20.00","wageringRequired":"100.00"}"""), ctx)
    assertEquals((s, out.flatMap(_.text("bonusAmount")), out.flatMap(_.text("wageringRequired"))), (200, Some("20.00"), Some("100.00")))

  test("the player's list as the original's array, each reward by its claim's reference"):
    val r        = tr("GET", "/v1/casino/backoffice/spinvibe/players/orig-p/bonuses")
    val observed = body("""{"items":[{"rewardId":"id-r","status":"ACTIVE","faceValue":2000,"currency":"EUR","isPrimary":true}],"total":1}""")
    val recorded = body("""[{"grantRef":"bonus-claims/orig-c","status":"ACTIVE","faceValue":"20.00","currency":"EUR","isPrimary":true}]""")
    val (_, out) = answer(r, 200, observed, recorded, ctx)
    val row      = out.get.get(0)
    assertEquals((row.text("grantRef"), row.text("faceValue"), row.bool("isPrimary")), (Some("bonus-claims/orig-c"), Some("20.00"), Some(true)))

  test("the rounds' twin: the grant's word, its spin value and winnings as strings, the reward's claim by reference"):
    val r        = tr("GET", "/twins/free-spins-grants/orig-g")
    val observed = body("""{"rewardId":"id-r","currency":"EUR","freeSpins":{"gameId":"g","spinCount":3,"spinsUsed":1,"spinValue":10,"winnings":1000,"grantStatus":"active"}}""")
    val recorded = body("""{"properties":{"status":"granted","grant_id":"orig-g","claim_ref":"bonus-claims/orig-c","spin_value":"0.10","accumulated_raw_winnings":"10.00","spins_used":1}}""")
    val p        = answer(r, 200, observed, recorded, ctx)._2.get.get("properties")
    assertEquals((p.text("status"), p.text("grant_id"), p.text("claim_ref"), p.text("spin_value"), p.text("accumulated_raw_winnings")),
      (Some("granted"), Some("orig-g"), Some("bonus-claims/orig-c"), Some("0.10"), Some("10.00")))
