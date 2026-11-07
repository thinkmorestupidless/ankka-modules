package io.eitheror.casino.programme.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.programme.brands.Plan
import io.eitheror.casino.programme.ledger.{CommissionAccount, RecordCpa, Unsettled}

import java.time.Instant
import java.util.concurrent.Executors
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Closing a period and paying it (US4), through the routes. Each test dates its facts in a period of
 * its own in 2025, so a close here sweeps only what this suite wrote; the other suites date theirs in
 * October 2026.
 */
class CloseSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 180.seconds
  private def kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = { kit.rewire(); kit.reset() }

  private def affiliate(): String = kit.affiliate(kit.fresh("payee"), "goldvibe", kit.plan("goldvibe", kit.fresh("p"), Plan.Cpa, cpa = 5_000))

  /** A CPA of `amount` dated `at`, then waited for in the `Unsettled` view, as a close reads it. */
  private def earn(aff: String, amount: Long, at: String, currency: String = "EUR"): Unit =
    kit.components.forEventSourcedEntity(CommissionAccount.entityId(aff, "goldvibe", currency)).call(CommissionAccount.recordCpa)
      .invoke(RecordCpa(kit.fresh("cpa"), amount, Instant.parse(at), "UTC", kit.now)): Unit
    kit.owed(aff, "goldvibe", currency)

  private def close(period: String): Reply = kit.request("POST", s"/v1/affiliates/goldvibe/payouts/close?period=$period", headers = kit.asManager())

  private def payoutsOf(aff: String, period: String): Vector[JsonNode] =
    kit.request("GET", s"/v1/affiliates/goldvibe/payouts?period=$period", headers = kit.asManager()).json.elements().asScala.toVector
      .filter(p => Option(p.get("affiliateId")).exists(_.asText == aff))

  /** The payout once the view shows it as `f` wants it. */
  private def payout(aff: String, period: String, currency: String = "EUR")(f: JsonNode => Boolean = _ => true): JsonNode =
    kit.eventually(s"$aff's $currency payout for $period")(payoutsOf(aff, period).find(p => p.get("currency").asText == currency && f(p)))

  private def move(payoutId: String, body: String): Reply =
    kit.request("PATCH", s"/v1/affiliates/goldvibe/payouts/$payoutId/status", Some(body), kit.asManager())

  test("a close claims every unclaimed fact up to the period's end for a pending payout, and answers what it wrote") {
    val aff = affiliate()
    earn(aff, 5_000, "2025-01-15T10:00:00Z")
    earn(aff, 2_000, "2025-02-03T10:00:00Z")
    val r = close("2025-01")
    assertEquals(r.status, 200, r.text)
    val mine = r.json.get("payouts").elements().asScala.find(_.get("affiliateId").asText == aff)
    assertEquals(mine.map(_.get("totalPayable").asLong), Some(5_000L))
    assertEquals(mine.map(_.get("payoutId").asText), Some(s"$aff:EUR:2025-01"))
    val p = payout(aff, "2025-01")()
    assertEquals((p.get("status").asText, p.get("totalPayable").asLong, p.get("cpa").asLong), ("pending", 5_000L, 5_000L))
  }

  test("below the affiliate's threshold nothing is claimed, and the facts carry to the next close") {
    val aff = affiliate()
    val r = kit.request("PATCH", s"/v1/affiliates/goldvibe/accounts/$aff", Some("""{"minimumPayout":{"EUR":10000}}"""), kit.asManager())
    assertEquals(r.status, 200, r.text)
    earn(aff, 5_000, "2025-02-10T10:00:00Z")
    assertEquals(close("2025-02").status, 200)
    assertEquals(payoutsOf(aff, "2025-02"), Vector.empty)
    earn(aff, 6_000, "2025-03-10T10:00:00Z")
    assertEquals(close("2025-03").status, 200)
    assertEquals(payout(aff, "2025-03")().get("totalPayable").asLong, 11_000L)
  }

  test("a late fact swept into an approved payout reverts it to pending, the approval superseded") {
    val aff = affiliate()
    earn(aff, 5_000, "2025-04-10T10:00:00Z")
    close("2025-04"): Unit
    val id = payout(aff, "2025-04")().get("payoutId").asText
    assertEquals(move(id, """{"status":"APPROVED"}""").status, 200)
    earn(aff, 1_000, "2025-04-20T10:00:00Z")
    close("2025-04"): Unit
    // The row read before the approval reached the view is pending too; wait for the second close's.
    val p = payout(aff, "2025-04")(p => p.get("status").asText == "pending" && p.get("supersededApprovals").size > 0)
    assertEquals(p.get("totalPayable").asLong, 6_000L)
    assertEquals(p.get("supersededApprovals").elements().asScala.map(_.get("total").asLong).toVector, Vector(5_000L))
  }

  test("a paid payout is left as it is; its late fact is claimed by the next close") {
    val aff = affiliate()
    earn(aff, 5_000, "2025-05-10T10:00:00Z")
    close("2025-05"): Unit
    val id = payout(aff, "2025-05")().get("payoutId").asText
    assertEquals(move(id, """{"status":"APPROVED"}""").status, 200)
    assertEquals(move(id, """{"status":"PAID"}""").status, 200)
    earn(aff, 1_000, "2025-05-20T10:00:00Z")
    assertEquals(close("2025-05").status, 200)
    val paid = payout(aff, "2025-05")(_.get("status").asText == "paid")
    assertEquals(paid.get("totalPayable").asLong, 5_000L)
    close("2025-06"): Unit
    assertEquals(payout(aff, "2025-06")().get("totalPayable").asLong, 1_000L)
  }

  test("facts in two currencies close as two payouts") {
    val aff = affiliate()
    earn(aff, 5_000, "2025-07-10T10:00:00Z")
    earn(aff, 7_000, "2025-07-11T10:00:00Z", "USD")
    close("2025-07"): Unit
    assertEquals(payout(aff, "2025-07", "EUR")().get("totalPayable").asLong, 5_000L)
    assertEquals(payout(aff, "2025-07", "USD")().get("totalPayable").asLong, 7_000L)
  }

  test("two closes at once give one result") {
    val aff = affiliate()
    earn(aff, 5_000, "2025-08-10T10:00:00Z")
    val pool = Executors.newFixedThreadPool(2)
    given ExecutionContext = ExecutionContext.fromExecutor(pool)
    try
      val both = Await.result(Future.sequence(Seq(Future(close("2025-08")), Future(close("2025-08")))), 60.seconds)
      assertEquals(both.map(_.status), Seq(200, 200), both.map(_.text).mkString("\n"))
      val totals = both.map(_.json.get("payouts").elements().asScala.filter(_.get("affiliateId").asText == aff).map(_.get("totalPayable").asLong).toVector)
      assertEquals(totals, Seq(Vector(5_000L), Vector(5_000L)))
      assertEquals(payout(aff, "2025-08")().get("totalPayable").asLong, 5_000L)
      assertEquals(payoutsOf(aff, "2025-08").size, 1)
    finally pool.shutdown()
  }

  test("a transition the status machine does not allow is refused, naming the status") {
    val aff = affiliate()
    earn(aff, 5_000, "2025-09-10T10:00:00Z")
    close("2025-09"): Unit
    val id = payout(aff, "2025-09")().get("payoutId").asText
    val r  = move(id, """{"status":"PAID"}""")
    assertEquals((r.status, r.json.get("error").asText), (409, "status_transition"))
    assert(r.json.get("message").asText.contains("pending"), r.text)
    assertEquals(move(s"$aff:EUR:1999-01", """{"status":"APPROVED"}""").status, 404)
    assertEquals(close("2025-13").status, 400)
  }
