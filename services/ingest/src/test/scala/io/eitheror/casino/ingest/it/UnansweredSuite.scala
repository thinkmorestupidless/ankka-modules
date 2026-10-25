package io.eitheror.casino.ingest.it

import io.eitheror.casino.ingest.requests.{LostAnswer, Outcome, Ownership, RequestsView}
import io.eitheror.casino.ingest.Json

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/** T034 (US2; FR-013, FR-014): merchant unanswered at the bound is "try again"; what it made late is a lost answer. */
class UnansweredSuite extends munit.FunSuite:
  private lazy val kit = Kit()

  override def afterAll(): Unit = kit.stop()
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def timed(f: => Reply): (Reply, Long) =
    val started = System.nanoTime()
    val r       = f
    (r, (System.nanoTime() - started) / 1_000_000)

  test("unanswered at the bound is 503 TRY_AGAIN; the repeat is attempt 2; the first's late session is a lost answer and never the id's") {
    val key = kit.issueKey()
    kit.merchant.hold("t-4")
    val (first, millis) = timed(kit.create(key, "transactionId" -> "t-4"))
    assertEquals((first.status, first.str("error"), first.header("Retry-After")), (503, "TRY_AGAIN", Some("5")))
    assert(millis < 1000, s"answered after $millis ms")
    val unanswered = kit.recordWhere(key.subBrand)(r => r.requestId.contains("t-4") && r.outcome == Outcome.Unanswered)
    assertEquals(unanswered.attempt, Some(1))

    val second = kit.create(key, "transactionId" -> "t-4")
    assertEquals(second.status, 200, second.text)
    assertEquals(kit.merchant.received(FakeMerchant.Create).size, 2)
    assertEquals(kit.requestState(key.subBrand, "t-4").attempt, 2)

    kit.merchant.release("t-4")
    val lost = kit.eventually("the first record lost")(kit.record(unanswered.recordId).filter(_.outcome == Outcome.LostAnswer))
    val late = lost.lostAnswer.get
    assert(late.ticket.exists(_ != second.str("session_token")), late.toString)
    assertEquals(kit.requestState(key.subBrand, "t-4").answer.flatMap(_.text("session_token")), Some(second.str("session_token")))
    assertEquals(kit.ownership(Ownership.ofTicket(late.ticket.get)), None)
    assert(kit.ownership(Ownership.ofTicket(second.str("session_token"))).isDefined)
    val listed = kit.eventually("the lost answer listed")(RequestsView.lostAnswers(kit.views, Some(key.subBrand)).find(_.recordId == unanswered.recordId))
    assertEquals(listed.seen, false)
  }

  test("merchant unreachable is 503 at once, not at the bound") {
    val key = kit.issueKey()
    kit.merchant.stop()
    try
      val (r, millis) = timed(kit.create(key, "transactionId" -> "t-down"))
      assertEquals((r.status, r.str("error")), (503, "TRY_AGAIN"))
      assert(millis < 450, s"answered after $millis ms")
      kit.recordWhere(key.subBrand)(r => r.requestId.contains("t-down") && r.outcome == Outcome.Unanswered): Unit
    finally kit.merchant.start()
  }

  test("an answer under the bound is served normally") {
    val key = kit.issueKey()
    kit.merchant.delay = 300
    assertEquals(kit.create(key, "transactionId" -> "t-slow").status, 200)
  }

  test("a late refusal made nothing: the record stays unanswered and there is no lost answer") {
    val key = kit.issueKey()
    kit.merchant.hold("t-5", io.eitheror.casino.ingest.it.Answer(400, """{"error":"NO_ROUTING_MATCH"}"""))
    assertEquals(kit.create(key, "transactionId" -> "t-5").status, 503)
    val r = kit.recordWhere(key.subBrand)(r => r.requestId.contains("t-5"))
    kit.merchant.release("t-5")
    Thread.sleep(1500)
    assertEquals(kit.record(r.recordId).map(x => (x.outcome, x.lostAnswer)), Some((Outcome.Unanswered, None)))
  }
