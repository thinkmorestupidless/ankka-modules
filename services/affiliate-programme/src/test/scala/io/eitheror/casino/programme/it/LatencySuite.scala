package io.eitheror.casino.programme.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * SC-005: a fact is in the affiliate's revenue within 10 s of its delivery, under load: while other
 * affiliates' players send a stream of facts, each probe's GGR is read back from the portal's revenue,
 * the time from its publication to the answer that holds it measured. A service of its own (build.sbt).
 */
class LatencySuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = Duration(300, "s")
  private def kit = LatencySuite.kit
  override def afterAll(): Unit = { LatencySuite.kit.stop(); super.afterAll() }

  private val Within  = 10.seconds
  private val Period  = "2026-10"
  private val Day     = "2026-10-24"

  private def ggrIn(affiliate: String): Long =
    val r = kit.request("GET", s"/v1/my/$affiliate/revenue?period=$Period", headers = kit.asAffiliate(affiliate))
    if r.status != 200 then 0L else r.json.get("figures").elements().asScala.map(_.path("ggr").asLong).sum

  test("a fact is in the affiliate's revenue within 10 s of its delivery while others' facts stream in") {
    val busy = Vector.fill(10)(Scene(kit)).map(s => s -> s.player())
    val probing = java.util.concurrent.atomic.AtomicBoolean(true)
    val failed  = java.util.concurrent.atomic.AtomicReference[Throwable]()
    val sent    = java.util.concurrent.atomic.AtomicInteger()
    // The load streams for as long as the probes run, so every probe is measured under it.
    val load = new Thread(() =>
      try
        var round = 0
        while probing.get() do
          round += 1
          busy.foreach((s, p) => { kit.publish(s.facts.ggrDaily(p, Day, stake = 1_000L + round, win = 500, at = kit.now)); sent.incrementAndGet(): Unit })
          Thread.sleep(200) // ten facts every 200 ms, the stream SC-005 means, not a flood
      catch case e: Throwable => failed.set(e)
    )
    load.start()
    val probes = Vector.fill(5)(Scene(kit)).map(s => s -> s.player())
    val took = probes.map { (s, p) =>
      val start = System.nanoTime()
      kit.publish(s.facts.ggrDaily(p, Day, stake = 3_000, win = 1_000, at = kit.now))
      kit.eventually(s"${s.affiliate}'s GGR in revenue", within = Within)(Option(ggrIn(s.affiliate)).filter(_ == 2_000L)): Unit
      (System.nanoTime() - start).nanos.toMillis
    }
    probing.set(false)
    load.join()
    Option(failed.get()).foreach(e => fail("the load failed", e))
    assert(sent.get() >= busy.size * 5, s"only ${sent.get()} facts of load were sent")
    println(s"SC-005: a fact in revenue after ${took.mkString(", ")} ms")
    assert(took.forall(_ < Within.toMillis), took.toString)
  }

object LatencySuite:
  lazy val kit: Kit = Kit()
