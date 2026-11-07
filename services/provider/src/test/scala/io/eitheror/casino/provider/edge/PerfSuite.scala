package io.eitheror.casino.provider.edge

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.util.concurrent.{Callable, Executors, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * SC-003: a launch and its check, asked as a brand and a game page ask them, within 2 s at the
 * 99th percentile. 100 of them, ten at a time, against `FakeBrand` and `TestGame` over Postgres.
 * Run on purpose only (`just provider-perf`, which sets `PROVIDER_PERF=1`); otherwise skipped.
 */
class PerfSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private val perf  = sys.env.get("PROVIDER_PERF").contains("1")
  private val count = sys.env.get("PROVIDER_PERF_COUNT").flatMap(_.toIntOption).getOrElse(100)

  private def kit = ProviderTestKit.kit

  test("100 launches and their checks answer within 2 s at the 99th percentile") {
    assume(perf, "set PROVIDER_PERF=1 (just provider-perf)")
    kit.start()
    assertEquals(kit.game.registerGame("test-slot", "slots").status, 200)
    // One launch and check first, so the figures are the service's, not the JVM's warming.
    val warm = s"L-perf-warm-${System.nanoTime()}"
    assert(kit.launch(warm, "p-perf-warm", "test-slot").json.has("game_url"))
    kit.game.check(warm): Unit

    val pool = Executors.newFixedThreadPool(10)
    val tasks = (1 to count).map { i =>
      (() => {
        val ticket = s"L-perf-$i-${System.nanoTime()}"
        val began  = System.nanoTime()
        val launched = kit.launch(ticket, s"p-perf-$i", "test-slot")
        val checked  = kit.game.check(ticket)
        val took     = (System.nanoTime() - began) / 1000000L
        (launched.json.has("game_url") && checked.status == 200 && checked.text("refused").isEmpty, took)
      }): Callable[(Boolean, Long)]
    }
    val results = pool.invokeAll(tasks.asJava).asScala.map(_.get()).toVector
    pool.shutdown()
    pool.awaitTermination(1, TimeUnit.MINUTES): Unit

    val sorted = results.map(_._2).sorted
    def at(p: Double) = sorted(math.min(sorted.size - 1, math.ceil(p * sorted.size).toInt - 1))
    println(s"provider-perf: $count launches and checks, p50 ${at(0.50)} ms, p95 ${at(0.95)} ms, p99 ${at(0.99)} ms, max ${sorted.last} ms")
    assertEquals(results.count(!_._1), 0, "every launch answered a page address and every check the session")
    assert(at(0.99) < 2000L, s"p99 ${at(0.99)} ms")
  }
