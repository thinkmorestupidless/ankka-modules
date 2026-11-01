package io.eitheror.casino.rewards.api

import io.eitheror.casino.rewards.Json
import io.eitheror.casino.rewards.Json.*
import io.eitheror.casino.rewards.offers.Refusal
import io.eitheror.casino.rewards.reward.Refused

import java.nio.charset.StandardCharsets.UTF_8

/** contracts/routes.md: the refusal shapes, the status tables per endpoint, the original's shapes, and FR-032's whole amounts. */
class AnswersSuite extends munit.FunSuite:
  private def body(r: Answers.Reply) = Json.parse(String(r.body.body, UTF_8)).get

  test("a refusal is {error, …fields} at its status"):
    val r = Answers.refusal(404, "grant_not_found", "grantRef" -> "g-1", "absent" -> None)
    assertEquals((r.status, body(r).text("error"), body(r).text("grantRef"), body(r).has("absent")), (404, Some("grant_not_found"),
      Some("g-1"), false))

  test("the original's shapes: ineligible, and a code preview that matches nothing"):
    val i = Answers.ineligible("MAX_CLAIMS_REACHED")
    assertEquals((i.status, body(i).text("status"), body(i).text("reason")), (409, Some("ineligible"), Some("MAX_CLAIMS_REACHED")))
    val c = Answers.codeNotFound
    assertEquals((c.status, body(c).bool("valid"), body(c).text("error")), (200, Some(false), Some("code_not_found")))

  test("each endpoint's table"):
    val cases = Vector(
      (Answers.Rewards, "offer_not_visible", 404), (Answers.Rewards, "invalid_code", 422), (Answers.Rewards, "BELOW_MIN_DEPOSIT", 409),
      (Answers.Rewards, "cancel_failed", 503), (Answers.Rewards, "grantRef_required", 400),
      (Answers.Offers, "offer_not_found", 404), (Answers.Offers, "spin_value_not_offered", 400), (Answers.Offers, "provider_unavailable", 502),
      (Answers.Grants, "CLAIM_NOT_FOUND", 404), (Answers.Grants, "MISSING_BASELINE", 409), (Answers.Grants, "spin_value_not_offered", 422),
      (Answers.Grants, "PROVIDER_UNAVAILABLE", 503), (Answers.Grants, "provider_rejected", 502), (Answers.Grants, "INVALID_MODE", 400),
      (Answers.Campaigns, "role_not_found", 422), (Answers.Campaigns, "ELIGIBILITY_CHECK_UNAVAILABLE", 503),
      (Answers.Campaigns, "provider_rejected", 502),
      (Answers.Grants, "wrong_brand", 404), (Answers.Campaigns, "service_unavailable", 503), (Answers.Offers, "forbidden", 403)
    )
    cases.foreach((t, code, status) => assertEquals(t.status(code), status, code))

  test("an offer's refusal keeps its own status and fields; a reward's its message"):
    val o = Answers.refused(Refusal(409, "code_already_in_use", Vector("code" -> "welcome")))
    assertEquals((o.status, body(o).text("error"), body(o).text("code")), (409, Some("code_already_in_use"), Some("welcome")))
    val r = Answers.refused(Refused("CLAIM_NOT_FOUND", "no such reward"), Answers.Grants)
    assertEquals((r.status, body(r).text("message")), (404, Some("no such reward")))

  test("FR-032: an amount is a whole, non-negative number of minor units; anything else is 400 naming the field"):
    val b = Json.parseObject("""{"a": 1000, "b": 10.5, "c": "10.00", "d": -1, "e": null, "f": 1.0e3, "g": 99999999999999999999}""").get
    assertEquals(Answers.amount(b, "a"), Right(Some(1000L)))
    assertEquals(Answers.amount(b, "e"), Right(None))
    assertEquals(Answers.amount(b, "absent"), Right(None))
    for field <- Vector("b", "c", "d", "f", "g") do
      val Left(r) = Answers.amount(b, field): @unchecked
      assertEquals((r.status, body(r).text("error"), body(r).text("field")), (400, Some("amount_not_minor_units"), Some(field)), field)
    assertEquals(Answers.amounts(b, "a", "e"), Right(Map("a" -> 1000L)))
    assert(Answers.amounts(b, "a", "b").isLeft)
