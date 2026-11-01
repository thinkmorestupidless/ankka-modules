package io.eitheror.casino.exchange.perf

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.exchange.it.{Kit, Reply}

import java.time.Duration as JDuration
import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * SC-002: an order checked, funded and resting within 50 ms at the median and 200 ms at p99 under 200
 * orders a second for the brand, the gates and the wallet included, each neighbour answering in a fixed
 * 5 ms. Buys that do not cross, across 20 books, each a player's own, timed at the player's route.
 *
 * 20 orders a second for three seconds by default, the figures printed; with `EXCHANGE_PERF=1` (`just
 * exchange-perf`) 200 a second for ten seconds and the bounds asserted, since a shared CI runner's
 * timings are not the service's: at 200 a second one queues until the neighbours' calls time out.
 */
class PlacementPerfSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 600.seconds

  private val perf    = sys.env.get("EXCHANGE_PERF").contains("1")
  private val seconds = if perf then 10 else 3
  private val rate    = sys.env.get("EXCHANGE_PERF_RATE").flatMap(_.toIntOption).getOrElse(if perf then 200 else 20)
  private val latency = sys.env.get("EXCHANGE_PERF_LATENCY_MS").flatMap(_.toLongOption).getOrElse(5L)

  private lazy val kit = Kit()
  override def afterAll(): Unit = kit.stop()

  private def percentile(sorted: Vector[Long], p: Double) = sorted(math.min(sorted.size - 1, math.ceil(p * sorted.size).toInt - 1))

  test(s"$rate orders a second for $seconds s rest within 50 ms at the median and 200 ms at p99 (SC-002)") {
    kit.reset()
    val run     = UUID.randomUUID().toString.take(6)
    val markets = (1 to 20).map(i => s"perf-$run-$i").toVector
    markets.foreach(m => assertEquals(kit.open(m, Vector("yes", "no"), "EUR", maxPosition = 1000000).status, 200))
    val total   = rate * seconds
    (0 until total).foreach(i => kit.wallet.real.put(s"p-$run-$i", 100000L))
    // Warm the path: the first calls load the classes and the entities.
    (0 until 40).foreach(i => kit.place(s"p-$run-$i", markets(i % 20), s"warm-$run-$i", "buy", "yes", 1, 10): Unit)
    kit.gates.latency = JDuration.ofMillis(latency)
    kit.wallet.latency = JDuration.ofMillis(latency)

    val times   = ConcurrentLinkedQueue[Long]()
    val refused = ConcurrentLinkedQueue[Reply]()
    val workers = Executors.newVirtualThreadPerTaskExecutor()
    val ticks   = Executors.newSingleThreadScheduledExecutor()
    val next    = java.util.concurrent.atomic.AtomicInteger(0)
    val started = System.nanoTime()
    ticks.scheduleAtFixedRate(() => {
      val i = next.getAndIncrement()
      if i < total then
        workers.execute(() => {
          val t = System.nanoTime()
          val r = kit.place(s"p-$run-$i", markets(i % 20), s"o-$run-$i", "buy", "yes", 1, 10 + i % 20)
          times.add((System.nanoTime() - t) / 1000)
          if r.status != 200 then refused.add(r): Unit
        })
    }, 0, 1000000L / rate, TimeUnit.MICROSECONDS)
    while next.get() < total do Thread.sleep(50)
    ticks.shutdown()
    workers.shutdown()
    assert(workers.awaitTermination(120, TimeUnit.SECONDS), "placements still running two minutes after the last was sent")
    val elapsed = (System.nanoTime() - started) / 1e9

    val sorted = times.asScala.toVector.sorted
    val (median, p99) = (percentile(sorted, 0.5) / 1000.0, percentile(sorted, 0.99) / 1000.0)
    println(f"SC-002: ${sorted.size}%,d orders in $elapsed%.1f s (${sorted.size / elapsed}%.0f a second), neighbours at $latency ms: " +
      f"median $median%.1f ms, p99 $p99%.1f ms, max ${sorted.last / 1000.0}%.1f ms")
    println(refused.asScala.toVector.map(r => s"${r.status} ${r.text}").groupMapReduce(identity)(_ => 1)(_ + _).take(5))
    assertEquals(refused.asScala.toVector.map(r => s"${r.status} ${r.text}").take(3), Vector.empty)
    assertEquals(sorted.size, total)
    if perf then
      assert(median <= 50, f"median $median%.1f ms is over SC-002's 50 ms")
      assert(p99 <= 200, f"p99 $p99%.1f ms is over SC-002's 200 ms")
  }
