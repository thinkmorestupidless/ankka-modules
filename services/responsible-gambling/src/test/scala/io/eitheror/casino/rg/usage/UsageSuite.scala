package io.eitheror.casino.rg.usage

import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit
import io.eitheror.casino.rg.profile.Sums

import java.time.{Duration, Instant}

/** The usage buckets (T010) and the session day (T011). */
class UsageSuite extends munit.FunSuite:
  private val now = Instant.parse("2026-10-27T12:00:00Z")
  private def ago(d: Duration) = now.minus(d)

  test("the three windows: deposits exactly, losses net of wins and rollbacks"):
    val kit = KeyValueEntityTestKit.of(UsageEntity, "p-1/EUR")
    Seq(
      Record("d1", Record.Deposit, 1_000, ago(Duration.ofHours(2))),
      Record("d2", Record.Deposit, 2_000, ago(Duration.ofDays(3))),
      Record("d3", Record.Deposit, 4_000, ago(Duration.ofDays(20))),
      Record("d4", Record.Deposit, 8_000, ago(Duration.ofDays(31))),
      Record("b1", Record.Loss, 500, ago(Duration.ofMinutes(30))),
      Record("w1", Record.Loss, -200, ago(Duration.ofMinutes(20))),
      Record("b2", Record.Loss, 1_000, ago(Duration.ofDays(5)))
    ).foreach(r => kit.call(UsageEntity.record)(r): Unit)
    val sums = kit.call(UsageEntity.sums)(UsageAt(now)).replyValue
    assertEquals(sums.deposits, Sums(1_000, 3_000, 7_000))
    assertEquals(sums.losses, Sums(300, 1_300, 1_300))

  test("more than 100 deposits in a window summed exactly (SC-007)"):
    val kit = KeyValueEntityTestKit.of(UsageEntity, "p-2/EUR")
    (1 to 250).foreach(i => kit.call(UsageEntity.record)(Record(s"d$i", Record.Deposit, i.toLong, ago(Duration.ofMinutes(i.toLong * 100)))): Unit)
    val sums = kit.call(UsageEntity.sums)(UsageAt(now)).replyValue
    assertEquals(sums.deposits.last30d, (1 to 250).map(_.toLong).sum)
    assertEquals(sums.deposits.last24h, (1 to 14).map(_.toLong).sum)

  test("a repeat transaction is no change"):
    val kit = KeyValueEntityTestKit.of(UsageEntity, "p-3/EUR")
    assert(kit.call(UsageEntity.record)(Record("d1", Record.Deposit, 1_000, now)).replyValue)
    assert(!kit.call(UsageEntity.record)(Record("d1", Record.Deposit, 1_000, now)).replyValue)
    assertEquals(kit.call(UsageEntity.sums)(UsageAt(now.plusSeconds(1))).replyValue.deposits.last24h, 1_000L)

  test("a net win is shown as no loss"):
    val kit = KeyValueEntityTestKit.of(UsageEntity, "p-4/EUR")
    kit.call(UsageEntity.record)(Record("w1", Record.Loss, -5_000, ago(Duration.ofMinutes(5)))): Unit
    assertEquals(kit.call(UsageEntity.sums)(UsageAt(now)).replyValue.losses, Sums(0, 0, 0))

  test("state is bounded: thousands of losses keep at most a day of minutes and a month of hours"):
    val u = (0 until 50_000).foldLeft(Usage()) { (u, i) =>
      val at = ago(Duration.ofMinutes(i.toLong))
      Windows.fold(u, Record(s"b$i", Record.Loss, 1, at), now)
    }
    assert(u.lossMinutes.size <= 1_440, u.lossMinutes.size.toString)
    assert(u.lossHours.size <= 721, u.lossHours.size.toString)
    assertEquals(u.seen.size, Windows.SeenKept)

  test("the session day: minutes in whole minutes, an open session counted to now"):
    val kit   = KeyValueEntityTestKit.of(SessionDayEntity, "p-1/2026-10-27")
    val start = Instant.parse("2026-10-27T09:00:00Z")
    kit.call(SessionDayEntity.started)(SessionStarted("gs-1", start)): Unit
    kit.call(SessionDayEntity.ended)(SessionEnded("gs-1", start.plusSeconds(25 * 60 + 59))): Unit
    kit.call(SessionDayEntity.started)(SessionStarted("gs-2", start.plusSeconds(3600))): Unit
    val day = kit.call(SessionDayEntity.get)(DayAt(start.plusSeconds(3600 + 10 * 60 + 30))).replyValue
    assertEquals(day, DayAnswer(35, Set("gs-1", "gs-2")))

  test("a session started yesterday is not counted today"):
    val kit = KeyValueEntityTestKit.of(SessionDayEntity, "p-1/2026-10-28")
    assert(!kit.call(SessionDayEntity.ended)(SessionEnded("gs-y", Instant.parse("2026-10-28T00:30:00Z"))).replyValue)
    assertEquals(kit.call(SessionDayEntity.get)(DayAt(Instant.parse("2026-10-28T01:00:00Z"))).replyValue.minutesUsed, 0L)
    assertEquals(SessionDayEntity.id("p-1", Instant.parse("2026-10-27T23:59:59Z")), "p-1/2026-10-27")
