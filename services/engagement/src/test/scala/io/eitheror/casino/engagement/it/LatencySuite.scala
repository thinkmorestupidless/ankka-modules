package io.eitheror.casino.engagement.it

import io.eitheror.casino.engagement.send.Outcome

import java.time.{Duration, Instant}
import scala.concurrent.duration.*

/** SC-005: a fact's send is accepted within 60 s of the fact, healthy and after one 503. */
class LatencySuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit   = SharedKit.kit
  private lazy val facts = Facts(kit)

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(Map("DepositConfirmed" -> "7"))

  private def within60(retryable: Boolean): Unit =
    kit.crm.reset()
    if retryable then kit.crm.fail(503)
    val p     = facts.player("lat")
    val d     = facts.deposit(p)
    val start = Instant.now()
    facts.publish(d)
    val s = facts.settled(facts.key("deposit_confirmed", d), 60.seconds)
    assertEquals(s.outcome, Outcome.Accepted)
    val took = Duration.between(start, Instant.now())
    assert(took.compareTo(Duration.ofSeconds(60)) < 0, s"$took")

  test("healthy: accepted within 60 s")(within60(retryable = false))
  test("after one 503: accepted within 60 s")(within60(retryable = true))
