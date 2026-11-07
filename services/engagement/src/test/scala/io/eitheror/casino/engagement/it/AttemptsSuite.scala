package io.eitheror.casino.engagement.it

import io.eitheror.casino.engagement.send.Outcome

import scala.concurrent.duration.*

/**
 * Research R5's attempts through the consumers: the waits doubling from the kit's 50 ms (±25 %), a 429, a 5xx and a
 * hang tried again, a 4xx not, the fourth failure parking the send with the last answer.
 */
class AttemptsSuite extends munit.FunSuite:
  override val munitTimeout = 120.seconds
  private lazy val kit   = SharedKit.kit
  private lazy val facts = Facts(kit)

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(Map("DepositConfirmed" -> "7"))

  override def beforeEach(context: BeforeEach): Unit =
    kit.crm.reset()

  test("three failures then an answer: accepted at the fourth attempt, after at least the three waits") {
    val p = facts.player("att")
    kit.crm.fail(503, 3)
    val d     = facts.deposit(p)
    val start = java.time.Instant.now()
    facts.publish(d)
    val s = facts.settled(facts.key("deposit_confirmed", d))
    assertEquals(s.outcome, Outcome.Accepted)
    assertEquals(s.calls.find(_.kind == "identify").map(_.attempts), Some(4))
    // 50, 100 and 200 ms less a quarter each: the identify's last attempt is at least 262 ms after the first.
    val took = java.time.Duration.between(start, s.calls.find(_.kind == "identify").get.at).toMillis
    assert(took >= 262, s"$took ms")
  }

  test("a 429, a 5xx and a hang are retried; a 4xx is refused at once") {
    val p = facts.player("att")
    kit.crm.fail(429)
    kit.crm.fail(500)
    kit.crm.hangMillis = 2000
    kit.crm.hang()
    val d = facts.deposit(p)
    facts.publish(d)
    assertEquals(facts.settled(facts.key("deposit_confirmed", d)).calls.find(_.kind == "identify").map(_.attempts), Some(4))

    val q = facts.player("att")
    kit.crm.refuse(422, """{"meta":{"error":"bad attribute"}}""")
    val e = facts.deposit(q)
    facts.publish(e)
    val s = facts.settled(facts.key("deposit_confirmed", e))
    assertEquals((s.outcome, s.calls.map(c => (c.kind, c.attempts, c.status))), (Outcome.Refused, Vector(("identify", 1, Some(422)))))
  }

  test("the fourth failure parks the send with the last answer") {
    val p = facts.player("att")
    kit.crm.fail(429, 3)
    kit.crm.fail(502)
    val d = facts.deposit(p)
    facts.publish(d)
    val s = facts.settled(facts.key("deposit_confirmed", d))
    assertEquals((s.outcome, s.calls.head.status, s.calls.head.attempts), (Outcome.Parked, Some(502), 4))
    assert(s.calls.head.lastAnswer.exists(_.contains("502")), s.calls.head.lastAnswer.toString)
  }
