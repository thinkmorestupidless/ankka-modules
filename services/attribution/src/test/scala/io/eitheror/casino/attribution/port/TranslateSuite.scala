package io.eitheror.casino.attribution.port

import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.port.Translate.*
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.port.Recording

import java.nio.file.Paths
import java.time.{Instant, ZoneId}

/** Each kind of recorded step from a hand-written fragment, and the exact money it carries. */
class TranslateSuite extends munit.FunSuite:

  private val zone = ZoneId.of("Africa/Johannesburg")
  private val now  = Instant.parse("2026-10-25T08:00:00Z")
  private val ids  = Map("player" -> "p-1", "btag" -> "b-1", "txn" -> "t-1", "withdrawal" -> "w-1")
  private def fill(text: String) = ids.foldLeft(text)((t, kv) => t.replace(s"{${kv._1}}", kv._2))

  private def step(method: String, path: String, body: String = "", status: Int = 200, answer: String = "") =
    Recording.Step(s"$method $path", Recording.Request(method, path, Map.empty, Json.parse(body)), Recording.Response(status,
      Json.parse(answer)), Map.empty)

  private def notify(kind: String, body: String) = step("POST", s"/internal/v1/affiliates/goldvibe/notify/$kind", body)

  private def translate(steps: Recording.Step*) =
    Translate(Recording(Paths.get("fragment.json"), "fragment", None, 2, steps.toVector, Map.empty), zone, fill, now)

  test("'12.34' EUR is 1234 minor units; JPY has none; a fraction finer than the currency is refused") {
    assertEquals(minorUnits("12.34", "EUR"), Right(1234L))
    assertEquals(minorUnits("50.00", "GBP"), Right(5000L))
    assertEquals(minorUnits("-3.10", "EUR"), Right(-310L))
    assertEquals(minorUnits("500", "JPY"), Right(500L))
    assert(minorUnits("1.005", "EUR").isLeft)
    assert(minorUnits("ten", "EUR").isLeft)
  }

  test("a click is replayed as recorded") {
    val click = step("POST", "/v1/track/click", """{"btag":"{btag}"}""", 200, """{"ok":true}""")
    assertEquals(translate(click), Vector(Replay(click)))
  }

  test("a registration notification becomes player_registered with the tag, the click and the time") {
    val got = translate(notify("registration",
      """{"playerId":"{player}","btag":"{btag}","clickId":"c-1","registeredAt":"2026-10-24T10:00:00Z","country":"GB"}"""))
    val at = Instant.parse("2026-10-24T10:00:00Z")
    assertEquals(got, Vector(Publish(CasinoPlayers, PlayerRegistered("p-1", at, Some("b-1"), Some("c-1"), None, Some("GB"), None, None, at))))
  }

  test("a deposit notification becomes payment.captured in minor units, keyed by the transaction") {
    translate(notify("deposit", """{"playerId":"{player}","amount":"50.00","currency":"GBP","transactionId":"{txn}"}""")) match
      case Vector(Publish(PaymentsPayments, p: PaymentCaptured)) =>
        assertEquals((p.paymentId, p.playerId, p.amountCents.value, p.currency.code), ("t-1", "p-1", 5000L, "GBP"))
      case other => fail(s"not one payment.captured: $other")
  }

  test("a withdrawal becomes requested then completed; its reversal carries the withdrawal's amount") {
    val got = translate(
      notify("withdrawal", """{"playerId":"{player}","amount":"20.00","currency":"GBP","requestId":"{withdrawal}"}"""),
      notify("withdrawal-reversal", """{"playerId":"{player}","requestId":"{withdrawal}"}""")
    )
    assertEquals(got.collect { case Publish(_, f) => f.getClass.getSimpleName },
      Vector("WithdrawalRequested", "WithdrawalCompleted", "WithdrawalReversed"))
    got.last match
      case Publish(_, r: WithdrawalReversed) => assertEquals(r.amountCents.value, 2000L)
      case other                             => fail(other.toString)
  }

  test("a GGR notification becomes the wallet's bet and win, moving the real balance by each") {
    translate(notify("ggr", """{"playerId":"{player}","ggr":"7.50","currency":"EUR","eventId":"r-1","real_stake_value":"10.00","real_win_value":"2.50"}""")) match
      case Vector(Publish(MoneyTransactions, b: BetPlaced), Publish(MoneyTransactions, w: WinCredited)) =>
        assertEquals((b.amountCents.value, b.realBalanceAfter.value), (1000L, -1000L))
        assertEquals((w.amountCents.value, w.realBalanceAfter.value), (250L, -750L))
        assertEquals(b.roundId, "r-1")
      case other => fail(s"not a bet and a win: $other")
  }

  test("a bonus cost becomes restricted_funds_awarded; a chargeback, a dispute opened") {
    val got = translate(
      notify("bonus-cost", """{"playerId":"{player}","amount":"10.00","currency":"GBP","claimId":"cl-1"}"""),
      notify("chargeback", """{"playerId":"{player}","transactionId":"{txn}","amount":"50.00","currency":"GBP"}""")
    )
    got match
      case Vector(Publish(_, a: RestrictedFundsAwarded), Publish(_, d: PaymentDisputeOpened)) =>
        assertEquals((a.awardId, a.amountCents.value), ("cl-1", 1000L))
        assertEquals((d.paymentId, d.amountCents.value), ("t-1", 5000L))
      case other => fail(other.toString)
  }

  test("the facts twin read expects one ggr-daily per day: three rounds on one day summed, exactly") {
    def round(id: String, ggr: String, at: String) =
      notify("ggr", s"""{"playerId":"{player}","ggr":"$ggr","currency":"EUR","eventId":"$id","occurredAt":"$at"}""")
    val got = translate(
      round("r-1", "1.10", "2026-10-24T08:00:00Z"),
      round("r-2", "-0.35", "2026-10-24T12:00:00Z"),
      round("r-3", "2.00", "2026-10-24T21:30:00Z"), // 23:30 in Johannesburg: the same day
      round("r-4", "4.00", "2026-10-24T22:30:00Z"), // 00:30 the next day there
      step("GET", "/twins/affiliate-facts-goldvibe/{player}", answer = """{"playerId":"{player}"}""")
    )
    assertEquals(got.collect { case e: Expect => e }, Vector(
      Expect("ggr-daily", "p-1", Some(275L), Some("2026-10-24"))(),
      Expect("ggr-daily", "p-1", Some(400L), Some("2026-10-25"))()
    ))
  }

  test("the registration and revenue twin reads expect the registration and the first deposit") {
    val got = translate(
      notify("deposit", """{"playerId":"{player}","amount":"50.00","currency":"GBP","transactionId":"{txn}"}"""),
      notify("deposit", """{"playerId":"{player}","amount":"30.00","currency":"GBP","transactionId":"t-2"}"""),
      step("GET", "/twins/affiliate-registration-goldvibe/{player}", answer = """{"playerId":"{player}"}"""),
      step("GET", "/twins/affiliate-revenue-a1-goldvibe/rev-1", answer = """{"ftdCount":"1"}""")
    )
    assertEquals(got.collect { case e: Expect => e }, Vector(
      Expect("registration", "p-1")(),
      Expect("first-deposit", "p-1", Some(5000L), Some("t-1"))()
    ))
  }

  test("a settled round read is the wallet's real legs; the original's GGR facts are the day's expectation, or none") {
    // The round names the run-1 id the registration's answer captured as c0: this replay's c0.
    val register = Recording.Step("register", Recording.Request("POST", "/v1/casino/players/goldvibe/register", Map.empty, None),
      Recording.Response(201, Json.parse("""{"playerId":"run-1-player"}""")), Map("c0" -> "$.playerId"))
    def fillAll(text: String) = fill(text).replace("{c0}", "p-1")
    def round(real: String) = step("GET", "/twins/casino-rounds-goldvibe/leaf?include=concepts", answer =
      s"""{"properties":{"player_ref":"run-1-player","currency":"EUR","real_stake":"$real","real_win":"0.00",
         |"settled_at":"2026-10-28T07:34:15Z","st8_round_id":"r-1","game_id":"g-1"}}""".stripMargin)
    def facts(answer: String) = step("GET", "/twins/affiliate-facts-goldvibe-{c3}?filter=x", answer = answer)
    def of(steps: Recording.Step*) = Translate(Recording(Paths.get("f.json"), "f", None, 2, steps.toVector, Map.empty), zone, fillAll, now)

    val split = of(register, round("10.00"), facts(
      """{"total":1,"twins":[{"properties":{"playerId":"run-1-player","amountCents":"500","excludedGgrCents":"500",
        |"occurredAt":"2026-10-28T07:34:15Z"}}]}""".stripMargin))
    split.collect { case Publish(_, b: BetPlaced) => b } match
      case Vector(b) => assertEquals((b.playerId, b.amountCents.value, b.walletType, b.roundId), ("p-1", 1000L, "real", "r-1"))
      case other     => fail(s"one real bet: $other")
    assertEquals(split.collect { case Publish(_, w: WinCredited) => w }, Vector.empty, "a win of nothing is not published")
    assertEquals(split.collect { case e: Expect => e }, Vector(Expect("ggr-daily", "p-1", Some(500L), Some("2026-10-28"))()))

    val restricted = of(register, round("0.00"), facts("""{"total":0,"twins":[]}"""))
    assertEquals(restricted.collect { case p: Publish[?] => p }, Vector.empty)
    assertEquals(restricted.collect { case e: ExpectNone => e }, Vector(ExpectNone("ggr-daily", "p-1", "2026-10-28")()))
  }

  test("a link's seed is a legacy tag; a plan's seed and anything else are skipped, saying why") {
    val got = translate(
      step("PUT", "/twins/affiliate-links-goldvibe/{btag}", """{"btag":"{btag}","affiliateId":"a-1"}"""),
      step("PUT", "/twins/affiliate-plans-goldvibe/plan-1", """{"planType":"CPA"}"""),
      step("GET", "/health")
    )
    assertEquals(got.head, Seed("b-1", "a-1", "b-1"))
    assert(got.tail.forall(_.isInstanceOf[Skip]), got.toString)
  }
