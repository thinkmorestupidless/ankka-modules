package io.eitheror.casino.publicapi.keys

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, MovableClock}

import java.time.Instant
import scala.concurrent.duration.*

/** T061, a port of the original's `quota.test.ts`: a key's window, `Retry-After` and last use. */
class KeyWindowSuite extends munit.FunSuite:
  private val start = Instant.parse("2026-11-12T08:00:00Z")

  test("the first request opens a window; up to the limit are allowed, the next is refused until the window turns") {
    val clock = MovableClock.at(start)
    val k     = KeyValueEntityTestKit.of(KeyWindow, "k1")
    def take() = k.call(KeyWindow.take)(Take(clock.instant(), 3)).replyValue
    assertEquals((1 to 3).map(_ => take().allowed), Vector(true, true, true))
    clock.moveBy(20.seconds)
    assertEquals(take(), Taken(false, 40))
    clock.moveBy(40.seconds)
    assertEquals(take(), Taken(true))
    assertEquals(k.currentState.count, 1)
  }

  test("Retry-After rounds up to the window's end and is never under one second") {
    val (s, _) = Window.take(WindowState(), "k", Take(start, 1))
    assertEquals(Window.take(s, "k", Take(start.plusMillis(1500), 1))._2, Taken(false, 59))
    assertEquals(Window.take(s, "k", Take(start.plusMillis(59_999), 1))._2, Taken(false, 1))
  }

  test("a refused request is not a use: lastUsedAt is the last allowed request") {
    val (s1, _) = Window.take(WindowState(), "k", Take(start, 1))
    val (s2, t) = Window.take(s1, "k", Take(start.plusSeconds(5), 1))
    assertEquals(t.allowed, false)
    assertEquals(s2.lastUsedAt, Some(start))
    assertEquals(s2.count, 1)
  }

  test("a lowered limit applies within the current window") {
    val s = (1 to 5).foldLeft(WindowState())((w, _) => Window.take(w, "k", Take(start, 600))._1)
    assertEquals(Window.take(s, "k", Take(start.plusSeconds(1), 5))._2.allowed, false)
    assertEquals(Window.take(s, "k", Take(start.plusSeconds(1), 6))._2.allowed, true)
  }
