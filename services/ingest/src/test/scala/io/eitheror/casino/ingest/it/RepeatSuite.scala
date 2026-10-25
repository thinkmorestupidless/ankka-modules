package io.eitheror.casino.ingest.it

import io.eitheror.casino.ingest.Json
import io.eitheror.casino.ingest.requests.{Outcome, RequestState}

import java.time.Duration
import java.util.UUID
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** T033 (US2; FR-010, SC-002): a request id is answered once per sub-brand; a repeat is the first answer. */
class RepeatSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def id(prefix: String) = s"$prefix-${UUID.randomUUID().toString.take(8)}"
  private def forwards = kit.merchant.received(FakeMerchant.Create).size

  test("the same body under the same id is the first answer byte for byte, forwarded once, the repeat recorded with the first's ticket") {
    val key   = kit.issueKey()
    val t     = id("t-1")
    val body  = kit.body("transactionId" -> t)
    val first = kit.create(Some(key), body)
    val again = kit.create(Some(key), body)
    assertEquals((again.status, again.text), (first.status, first.text))
    assertEquals(forwards, 1)
    val repeated = kit.recordWhere(key.subBrand)(r => r.requestId.contains(t) && r.outcome == Outcome.Repeated)
    assertEquals(repeated.ticket, Some(first.str("session_token")))
  }

  test("ten at once under one id: ten identical answers, one forward, one attempt, one served and nine repeated") {
    val key  = kit.issueKey()
    val t    = id("t-2")
    val body = kit.body("transactionId" -> t)
    kit.merchant.delay = 200
    val answers = Await.result(Future.sequence((1 to 10).map(_ => Future(kit.create(Some(key), body)))), 60.seconds)
    assertEquals(answers.map(a => (a.status, a.text)).distinct.size, 1, answers.map(_.text).distinct.toString)
    assertEquals(answers.head.status, 200)
    assertEquals(forwards, 1)
    assertEquals(kit.requestState(key.subBrand, t).attempt, 1)
    val records = kit.eventually("ten records")(Some(kit.records(key.subBrand).filter(_.requestId.contains(t))).filter(_.size == 10))
    assertEquals(records.groupBy(_.outcome).view.mapValues(_.size).toMap, Map(Outcome.Served -> 1, Outcome.Repeated -> 9))
  }

  test("another amount under the id is 409 naming it and not forwarded; another key order is the first answer") {
    val key   = kit.issueKey()
    val t     = id("t-1")
    val first = kit.create(Some(key), kit.body("transactionId" -> t))
    val other = kit.create(key, "transactionId" -> t, "amountCents" -> 6000L)
    assertEquals((other.status, other.str("error"), other.str("transactionId")), (409, "REQUEST_ID_REUSED", t))
    val reordered = Json.render(Json.obj("transactionId" -> t, "redirectUrl" -> "https://example.test/return", "country" -> "GB",
      "customerId" -> "goldvibe:p1", "currency" -> "EUR", "amountCents" -> 5000L))
    assertEquals(kit.create(Some(key), reordered).text, first.text)
    assertEquals(forwards, 1)
    kit.recordWhere(key.subBrand)(r => r.requestId.contains(t) && r.outcome == Outcome.RequestIdReused): Unit
  }

  test("the id belongs to the sub-brand: two sub-brands under one id are two sessions") {
    val t = id("t-1")
    val a = kit.create(kit.issueKey(), "transactionId" -> t)
    val b = kit.create(kit.issueKey(), "transactionId" -> t)
    assertNotEquals(a.str("session_token"), b.str("session_token"))
    assertEquals(forwards, 2)
  }

  test("no id: one is minted and forwarded as the command's, and the same body again is a second session") {
    val key   = kit.issueKey()
    val first = kit.create(key, "transactionId" -> None)
    val minted = first.str("transactionId")
    assert(minted.nonEmpty, first.text)
    assertEquals(Json.text(kit.merchant.creates.head)("transactionId"), Some(minted))
    val second = kit.create(key, "transactionId" -> None)
    assertNotEquals(second.str("transactionId"), minted)
    assertNotEquals(second.str("session_token"), first.str("session_token"))
  }

  test("a refusal leaves the id free: corrected, it is forwarded again and served (R5)") {
    val key = kit.issueKey()
    val t   = id("t-3")
    kit.merchant.refuseCreate = Some(400 -> "NO_ROUTING_TREE")
    assertEquals(kit.create(key, "transactionId" -> t).status, 400)
    kit.merchant.refuseCreate = None
    assertEquals(kit.create(key, "transactionId" -> t).status, 200)
    assertEquals(forwards, 2)
    assertEquals(kit.requestState(key.subBrand, t).state, RequestState.Answered)
  }

  test("a repeat after the id's retention on the clock is forwarded again") {
    val key   = kit.issueKey()
    val t     = id("t-1")
    val first = kit.create(key, "transactionId" -> t)
    kit.advance(kit.settings.requestIdRetention.plus(Duration.ofMinutes(1)))
    val later = kit.create(key, "transactionId" -> t)
    assertEquals(later.status, 200)
    assertNotEquals(later.str("session_token"), first.str("session_token"))
    assertEquals(forwards, 2)
  }
