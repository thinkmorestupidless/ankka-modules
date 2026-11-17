package io.eitheror.casino.players.roles

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.players.api.RolesRoutes

/** The default roles are seeded on the starting thread and tried again until they hold (spec 030 T136). */
class SeedRetrySuite extends munit.FunSuite with LogCapturing:

  test("a seed that times out is tried again, after the pauses, until it holds; the caller waits for it") {
    var calls = 0
    val slept = scala.collection.mutable.ArrayBuffer.empty[Long]
    val tries = RolesRoutes.untilItHolds(
      () => { calls += 1; if calls < 3 then throw RuntimeException("no reply within 10000 milliseconds") },
      sleep = ms => slept += ms)
    assertEquals(tries, 3)
    assertEquals(slept.toList, List(2000L, 4000L))
  }

  test("a seed that holds at once is made once, with no pause") {
    var calls = 0
    val tries = RolesRoutes.untilItHolds(() => calls += 1, sleep = _ => fail("no pause expected"))
    assertEquals((tries, calls), (1, 1))
  }

  test("the pause doubles from 2 seconds to a minute and no further") {
    assertEquals((0 until 8).map(RolesRoutes.SeedPause).toList,
      List(2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L, 60000L))
  }
