package io.eitheror.casino.engagement.it

import io.eitheror.casino.engagement.send.Outcome

import java.time.{Duration, Instant}
import scala.concurrent.duration.*

/**
 * SC-002: a year of a player's facts delivered twice into an empty journal. The first delivery skips every fact older
 * than the staleness bound and sends the rest once; the second finds every send and makes no call.
 */
class ReplaySuite extends munit.FunSuite:
  override val munitTimeout = 300.seconds
  private lazy val kit   = SharedKit.kit
  private lazy val facts = Facts(kit)

  override def beforeAll(): Unit =
    kit.reset()
    kit.configure(Map("DepositConfirmed" -> "7"))

  test("a year delivered twice: the stale skipped, the fresh sent once, the repeat silent") {
    val p    = facts.player("replay")
    val now  = Instant.now()
    // A deposit every week for a year, oldest first, and three in the last day.
    val old   = (52 to 1 by -1).map(w => facts.deposit(p, now.minus(Duration.ofDays(7L * w)))).toVector
    val fresh = Vector(6, 3, 1).map(h => facts.deposit(p, now.minus(Duration.ofHours(h.toLong))))
    val year  = old ++ fresh
    year.foreach(facts.publish)
    val first = year.map(d => facts.settled(facts.key("deposit_confirmed", d), 60.seconds))
    assertEquals(first.take(old.size).map(_.outcome).distinct, Vector(Outcome.SkippedStale))
    assertEquals(first.drop(old.size).map(_.outcome).distinct, Vector(Outcome.Accepted))
    val callsAfterFirst = facts.calls(p).size
    assertEquals(callsAfterFirst, fresh.size * 3)

    year.foreach(facts.publish)
    Thread.sleep(3000)
    assertEquals(facts.calls(p).size, callsAfterFirst)
    assertEquals(year.map(d => facts.state(facts.key("deposit_confirmed", d))), first)
  }
