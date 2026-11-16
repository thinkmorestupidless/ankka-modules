package io.eitheror.casino.players.roles

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.players.api.RolesRoutes

import java.util.concurrent.atomic.AtomicInteger

/** The default roles are seeded off the starting thread and tried again until they hold (spec 030 T136). */
class SeedRetrySuite extends munit.FunSuite with LogCapturing:

  test("a seed that times out is tried again until it holds, and the starting thread is not held up") {
    val calls = AtomicInteger(0)
    val thread = RolesRoutes.untilItHolds(
      () => if calls.incrementAndGet() < 3 then throw RuntimeException("no reply within 10000 milliseconds"),
      _ => 1L)
    thread.join(5000)
    assert(!thread.isAlive, "the seed should have finished")
    assertEquals(calls.get, 3)
  }

  test("a seed that holds at once is made once") {
    val calls = AtomicInteger(0)
    val thread = RolesRoutes.untilItHolds(() => calls.incrementAndGet(): Unit, _ => 1L)
    thread.join(5000)
    assertEquals(calls.get, 1)
  }

  test("the pause doubles from 2 seconds to a minute and no further") {
    assertEquals((0 until 8).map(RolesRoutes.SeedPause).toList,
      List(2000L, 4000L, 8000L, 16000L, 32000L, 60000L, 60000L, 60000L))
  }
