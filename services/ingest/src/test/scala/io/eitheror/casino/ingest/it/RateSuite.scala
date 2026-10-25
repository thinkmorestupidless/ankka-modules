package io.eitheror.casino.ingest.it

import io.eitheror.casino.ingest.Json
import io.eitheror.casino.ingest.requests.Outcome

import java.time.Duration
import java.util.concurrent.Executors
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** T042 (US4; FR-015, SC-005): each sub-brand's minute is its own; the excess is 429 and never reaches merchant. */
class RateSuite extends munit.FunSuite:
  private lazy val kit = Kit()
  private val pool     = Executors.newFixedThreadPool(64)
  private given ExecutionContext = ExecutionContext.fromExecutor(pool)

  override def afterAll(): Unit = { pool.shutdown(); kit.stop() }
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def forwards = kit.merchant.received(FakeMerchant.Create).size
  private def flood(key: RawKey, n: Int): Vector[Reply] = (1 to n).toVector.map(_ => kit.create(key))

  private def rate(subBrand: String, headers: Seq[(String, String)] = kit.asOperator()) =
    kit.request("GET", s"/v1/ingest/sub-brands/$subBrand/rate", None, headers)
  private def setRate(subBrand: String, perMinute: Long, headers: Seq[(String, String)] = kit.asOperator()) =
    kit.request("PUT", s"/v1/ingest/sub-brands/$subBrand/rate", Some(s"""{"perMinute":$perMinute}"""), headers)

  test("700 in one minute: 600 served, 100 refused with the seconds to the next minute, merchant asked 600 times, 100 audited") {
    kit.holdClockInAMinute(15)
    val key     = kit.issueKey()
    val replies = flood(key, 700)
    assertEquals(replies.count(_.status == 200), 600)
    val refused = replies.filter(_.status == 429)
    assertEquals(refused.size, 100)
    assertEquals(refused.map(r => (r.str("error"), r.header("Retry-After"), r.json.get("retryAfterSeconds").asLong)).distinct,
      Vector(("TOO_MANY_REQUESTS", Some("45"), 45L)))
    assertEquals(forwards, 600)
    kit.eventually("100 rate records")(Some(kit.records(key.subBrand).count(_.outcome == Outcome.Rate)).filter(_ == 100)): Unit
    kit.eventually("100 audit records")(Some(kit.audit().count(a => a.actorId == key.subBrand && a.actorKind == "sub-brand")).filter(_ == 100)): Unit

    assertEquals(kit.create(kit.issueKey()).status, 200, "another sub-brand in the same minute")
    kit.advance(Duration.ofMinutes(1))
    assertEquals(kit.create(key).status, 200, "the next minute")
  }

  test("a raise holds: 6000 a minute serves 700; the default is read where nothing was raised; 0, a negative and a staff token are refused") {
    kit.holdClockInAMinute(15)
    val key = kit.issueKey()
    assertEquals(setRate(key.subBrand, 6000).status, 204)
    assertEquals(Json.parse(rate(key.subBrand).text).map(Json.render), Json.parse("""{"perMinute":6000,"default":600}""").map(Json.render))
    assert(flood(key, 700).forall(_.status == 200))
    assertEquals(Json.parse(rate("never-raised").text).map(Json.render), Json.parse("""{"perMinute":600,"default":600}""").map(Json.render))
    assertEquals(setRate(key.subBrand, 0).status, 400)
    assertEquals(setRate(key.subBrand, -5).status, 400)
    assertEquals(setRate(key.subBrand, 10, kit.asStaff).status, 403)
    assertEquals(rate(key.subBrand, kit.asStaff).status, 403)
  }

  test("a submit, a read and an abandon count toward their owner's minute") {
    kit.holdClockInAMinute(15)
    val key       = kit.issueKey()
    val ticket    = kit.create(key).str("session_token")
    val paymentId = kit.submit(ticket).str("paymentId")
    assertEquals(kit.readPayment(paymentId, key = Some(key)).status, 200)
    assertEquals(kit.abandon(ticket).status, 200)
    assert(flood(key, 596).forall(_.status == 200))
    assertEquals(kit.create(key).status, 429)
  }

  test("while one sub-brand floods, another is answered within the answer bound (SC-005)") {
    kit.holdClockInAMinute(15)
    val flooding = kit.issueKey()
    val other    = kit.issueKey()
    val sent     = Future.sequence((1 to 700).map(_ => Future(kit.create(flooding))))
    Thread.sleep(200)
    val started = System.nanoTime()
    val r       = kit.create(other)
    val millis  = (System.nanoTime() - started) / 1_000_000
    assertEquals(r.status, 200)
    assert(millis < kit.settings.answerBound.toMillis, s"answered in $millis ms")
    Await.result(sent, 120.seconds): Unit
  }
