package io.eitheror.casino.programme.port

import com.fasterxml.jackson.databind.ObjectMapper
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.port.Recording
import io.eitheror.casino.programme.port.Translate.*

import java.nio.file.Paths
import java.time.Instant

/** The translation alone, pure: it runs with the unit suites. */
class TranslateSuite extends munit.FunSuite:
  private val mapper = ObjectMapper()
  private val now    = Instant.parse("2026-11-01T10:00:00Z")
  private def json(s: String) = mapper.readTree(s)
  private def step(name: String, method: String, path: String, body: String = "null", response: String = "null", status: Int = 200,
      headers: Map[String, String] = Map.empty) =
    Recording.Step(name, Recording.Request(method, path, headers, Option(json(body)).filterNot(_.isNull), None),
      Recording.Response(status, Option(json(response)).filterNot(_.isNull)), Map.empty)
  private def translate(steps: Recording.Step*) =
    Translate(Recording(Paths.get("r.json"), "r", None, 2, steps.toVector, Map.empty), "goldvibe", identity, p => s"ps($p)", now)

  test("major units become minor units by the currency's exponent, exactly") {
    assertEquals(minorUnits("50.00", "GBP"), Right(5000L))
    assertEquals(minorUnits("1000", "JPY"), Right(1000L))
    assert(minorUnits("0.001", "EUR").isLeft)
  }

  test("the reshaped routes, and the brand a filter on the portal's reads") {
    assertEquals(route("/v1/affiliates/spin/accounts/a1/network/s1/approve", "goldvibe"), "/v1/affiliates/spin/network/s1/approve")
    assertEquals(route("/v1/affiliates/spin/accounts/a1/view/commission/2026-10", "goldvibe"), "/v1/affiliates/spin/accounts/a1/view/commission?period=2026-10")
    assertEquals(route("/v1/my/a1/revenue?period=2026-10", "goldvibe"), "/v1/my/a1/revenue?period=2026-10&brand=goldvibe")
    assertEquals(route("/v1/affiliates/spin/payouts", "goldvibe"), "/v1/affiliates/spin/payouts")
  }

  test("a bearer names the manager or the affiliate it was") {
    assertEquals(authOf(Map("Authorization" -> "Bearer {staffToken}")), Auth.Manager)
    assertEquals(authOf(Map("Authorization" -> "Bearer {carrierToken}")), Auth.Affiliate("carrier"))
    assertEquals(authOf(Map.empty), Auth.Nobody)
  }

  test("an answer's money is in minor units and a player id is its pseudonym") {
    val out = translated(json("""{"cpaCommission":"50.00","depositCount":"1","players":[{"playerId":"p1","ggr":"-1.50"}]}"""), "GBP", p => s"ps-$p")
    assertEquals(out.toString, """{"cpaCommission":5000,"depositCount":"1","players":[{"pseudonym":"ps-p1","ggr":-150}]}""")
  }

  test("the seeded twins become setup; a first deposit is a first-deposit and a deposit, a later one a deposit") {
    val actions = translate(
      step("plan", "POST", "/twins/affiliate-plans-spin/{plan}", """{"planName":"Gold","planType":"CPA","cpaAmount":"50.00"}"""),
      step("aff", "POST", "/twins/affiliates-spin/{affiliate}", """{"affiliateId":"{affiliate}","planId":"{plan}"}"""),
      step("link", "POST", "/twins/affiliate-links/{btag}", """{"affiliateId":"{affiliate}","btag":"{btag}"}"""),
      step("link again", "POST", "/twins/affiliate-links-spin/{btag}", """{"affiliateId":"{affiliate}","btag":"{btag}"}"""),
      step("reg", "POST", "/internal/v1/affiliates/spin/notify/registration", """{"btag":"{btag}","playerId":"{player}","registeredAt":"2026-10-28T07:31:39Z"}"""),
      step("dep", "POST", "/internal/v1/affiliates/spin/notify/deposit", """{"amount":"50.00","currency":"GBP","playerId":"{player}","transactionId":"{txn}"}"""),
      step("dep2", "POST", "/internal/v1/affiliates/spin/notify/deposit", """{"amount":"20.00","currency":"GBP","playerId":"{player}","transactionId":"{txn2}"}""")
    )
    assertEquals(actions.take(3), Vector(
      SetupPlan("goldvibe", "{plan}", "Gold", "CPA", 5000, "GBP", 0, 0),
      SetupAffiliate("goldvibe", "{affiliate}", "{plan}"),
      SetupLink("goldvibe", "{btag}", "{affiliate}")))
    val facts = actions.collect { case Publish(f) => f }
    assertEquals(facts.map(Translate.kindOf), Vector("registration", "first-deposit", "deposit", "deposit"))
    assert(facts.forall(f => f.affiliate == "{affiliate}" && f.link == "{btag}" && f.brand == "goldvibe"))
    assertEquals(facts.collect { case d: Deposit => d.amount.value }, Vector(5000L, 2000L))
    assertEquals(facts.collect { case p: PlayerAttributionFact => p.pseudonym }.distinct, Vector("ps({player})"))
  }

  test("a day's first GGR is its ggr-daily and a later one that day an adjustment") {
    val actions = translate(
      step("link", "POST", "/twins/affiliate-links/{btag}", """{"affiliateId":"a","btag":"{btag}"}"""),
      step("reg", "POST", "/internal/v1/affiliates/spin/notify/registration", """{"btag":"{btag}","playerId":"p"}"""),
      step("g1", "POST", "/internal/v1/affiliates/spin/notify/ggr", """{"playerId":"p","ggr":"-100.00","eventId":"r1","occurredAt":"2026-09-15T12:00:00Z"}"""),
      step("g2", "POST", "/internal/v1/affiliates/spin/notify/ggr", """{"playerId":"p","ggr":"300.00","eventId":"r2","occurredAt":"2026-09-15T13:00:00Z"}""")
    )
    val ggr = actions.collect { case Publish(f: GgrDaily) => (f.reportingDay, f.ggr.value); case Publish(f: GgrAdjustment) => (f.reportingDay, f.ggr.value) }
    assertEquals(ggr, Vector(("2026-09-15", -10000L), ("2026-09-15", 30000L)))
  }

  test("a revenue twin read is the portal's revenue, as the affiliate, money in minor units") {
    val actions = translate(
      step("rev", "GET", "/twins/affiliate-revenue-{affiliate}-spin/{revenue}",
        response = """{"cpaCommission":"50.00","period":"2026-10","totalDepositAmount":"100.00"}""")
    )
    actions match
      case Vector(Replay(r)) =>
        assertEquals((r.method, r.path), ("GET", "/v1/my/{affiliate}/revenue?period=2026-10&brand=goldvibe"))
        assertEquals(r.expected.map(_.toString), Some("""{"cpaCommission":5000,"period":"2026-10","totalDepositAmount":10000}"""))
      case other => fail(s"not one replay: $other")
  }

  test("the realm's and other services' steps are skipped, naming why") {
    assert(translate(step("t", "POST", "/realms/eitheror/protocol/openid-connect/token")).forall(_.isInstanceOf[Skip]))
    assert(translate(step("w", "POST", "/v1/wallet/spin/p/EUR/bet")).forall(_.isInstanceOf[Skip]))
  }
