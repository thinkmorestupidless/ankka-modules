package io.eitheror.casino.audit.perf

import io.eitheror.casino.audit.exports.Export
import io.eitheror.casino.audit.ingest.SourceState
import io.eitheror.casino.audit.it.Kit
import io.eitheror.casino.audit.query.Filters
import io.eitheror.casino.audit.store.Records
import io.eitheror.casino.audit.stored.Source
import io.eitheror.casino.contract.facts.AuditChange
import com.thinkmorestupidless.ankka.runtime.InMemoryBroker

import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.*

/**
 * The audit at scale (quickstart §4; SC-003, SC-004, SC-006), over Postgres. Run on purpose only:
 * `just audit-perf` (`AUDIT_PERF=1`). Each figure is printed as `perf <name> <value>` for PERF.md; a
 * criterion missed fails its test, it is not tuned away.
 *
 * The sizes are settings: `AUDIT_PERF_SECONDS` (300) of 200 actions and 500 bets (1,000 money records)
 * a second; `AUDIT_PERF_YEAR` (100,000) records spread over a year for the query and the export;
 * `AUDIT_PERF_DAY` (50,000) records after a restore point for the re-read.
 */
class IngestPerf extends munit.FunSuite:
  override val munitTimeout: Duration = 90.minutes
  private val on = sys.env.get("AUDIT_PERF").contains("1")
  private def setting(name: String, default: Int) = sys.env.get(name).flatMap(_.toIntOption).getOrElse(default)

  private val seconds   = setting("AUDIT_PERF_SECONDS", 300)
  private val actions   = 200
  private val money     = 1000
  private val yearRows  = setting("AUDIT_PERF_YEAR", 100000)
  private val dayRows   = setting("AUDIT_PERF_DAY", 50000)

  private var kit: Kit = null
  override def beforeAll(): Unit = if on then kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()

  private def figure(name: String, value: Any): Unit = println(s"perf $name $value")

  private def counts(k: Kit, brand: String) =
    k.components.forKeyValueEntity(SourceState.entityId(Source.Brand(brand).name)).call(SourceState.get).invoke()

  private def action(brand: String, at: Instant) =
    Kit.record(brand = brand, actorId = s"op-${at.getNano % 50}", subjectId = s"p-${at.toEpochMilli % 5000}",
      correlationId = s"c-${at.toEpochMilli}", occurredAt = at)

  private def bet(brand: String, i: Long, at: Instant) =
    Kit.record(brand = brand, service = "wallet", action = if i % 2 == 0 then "wallet.bet" else "wallet.win", actorKind = "service",
      actorId = "game-provider", subjectId = s"p-${i % 5000}", category = "money", correlationId = s"r-$i", occurredAt = at,
      changes = Vector(AuditChange("amount", None, Some("100"))))

  test("SC-003: 200 actions a second kept with a lag under 30 seconds, beside 1,000 money records a second passed over") {
    assume(on, "AUDIT_PERF=1 runs the perf suite")
    // The in-memory broker's publication completes when every group has handled it, so a publisher
    // that cannot keep to its schedule is the consumer falling behind: the lag is how far the last
    // message of each second was handled after that second ended.
    val start = System.nanoTime()
    var worst = 0L
    var n     = 0L
    for s <- 0 until seconds do
      val at = kit.now.minusSeconds(seconds - s.toLong)
      for i <- 0 until actions + money do
        // Interleaved as a brand would write them: one action in six.
        if i % 6 == 0 && i / 6 < actions then kit.publish("goldvibe", action("goldvibe", at.plusMillis(i.toLong)))
        else kit.publish("goldvibe", bet("goldvibe", n, at.plusMillis(i.toLong)))
        n += 1
      val due = start + (s + 1) * 1000000000L
      val now = System.nanoTime()
      worst = math.max(worst, now - due)
      if due > now then Thread.sleep((due - now) / 1000000)
    val took = (System.nanoTime() - start) / 1e9
    val c    = counts(kit, "goldvibe")
    figure("published", n)
    figure("scheduledSeconds", seconds)
    figure("handledInSeconds", f"$took%.1f")
    figure("handledPerSecond", f"${n / took}%.0f")
    figure("stored", c.stored)
    figure("passedOver", c.passedOver)
    figure("shareKept", f"${c.stored.toDouble / (c.stored + c.passedOver) * 100}%.1f%%")
    figure("worstLagSeconds", f"${math.max(0L, worst) / 1e9}%.1f")
    assertEquals(c.stored + c.passedOver, n)
    assert(worst < 30.seconds.toNanos, s"lag ${worst / 1e9} s: ${n / took} messages a second handled of ${actions + money} published")
  }

  test("SC-004: the first page of a brand's year under 2 seconds; a month's export under 10 minutes") {
    assume(on, "AUDIT_PERF=1 runs the perf suite")
    val step = JDuration.ofDays(365).dividedBy(yearRows.toLong)
    val from = kit.now.minus(JDuration.ofDays(365))
    for i <- 0 until yearRows do kit.publish("spinvibe", action("spinvibe", from.plus(step.multipliedBy(i.toLong))))
    // The view, not the source's counter: the query and the export read the view.
    kit.eventually(60.minutes)(Option.when(Records.count(kit.views.forView(Records), Filters(brands = Some(Set("spinvibe")))) >= yearRows)(()))
    figure("yearRows", yearRows)

    val as = kit.asBrandCompliance("spinvibe")
    kit.query(as, "brand" -> "spinvibe", "from" -> from.toString, "to" -> kit.now.toString): Unit
    val t0    = System.nanoTime()
    val first = kit.query(as, "brand" -> "spinvibe", "from" -> from.toString, "to" -> kit.now.toString)
    val page  = (System.nanoTime() - t0) / 1e9
    assertEquals(first.status, 200, first.text)
    figure("firstPageSeconds", f"$page%.3f")

    val monthFrom = kit.now.minus(JDuration.ofDays(30))
    val t1 = System.nanoTime()
    val started = kit.request("POST", "/v1/audit-exports",
      Some(s"""{"key":"perf-month","brand":"spinvibe","from":"$monthFrom","to":"${kit.now}"}"""), as)
    assertEquals(started.status, 202, started.text)
    val done = kit.eventually(10.minutes) {
      val r = kit.request("GET", "/v1/audit-exports/perf-month", headers = as)
      Option.when(r.status == 200 && r.json.get("status").asText != Export.Running)(r)
    }
    val exported = (System.nanoTime() - t1) / 1e9
    assertEquals(done.json.get("status").asText, "done", done.text)
    figure("monthRows", done.json.get("count").asLong)
    figure("monthExportSeconds", f"$exported%.1f")
    assert(page < 2.0 && exported < 600, s"first page $page s, export $exported s")
  }

  test("SC-006: a raised generation re-reads a day of records after the restore point within 10 minutes") {
    assume(on, "AUDIT_PERF=1 runs the perf suite")
    val topics = InMemoryBroker()
    // A day before the restore point, which the re-read passes by, and a day after, which it reads.
    val before = dayRows / 10
    for i <- 0 until before do kit.publishOn(topics, "goldvibe", action("goldvibe", kit.now.minusSeconds(172800 - i.toLong)))
    Thread.sleep(1100)
    val restorePoint = Instant.now()
    Thread.sleep(1100)
    val step = 86400L * 1000 / dayRows
    for i <- 0 until dayRows do kit.publishOn(topics, "goldvibe", action("goldvibe", kit.now.minusMillis(86400L * 1000 - i * step)))
    val t0       = System.nanoTime()
    val restored = Kit(configure = _.copy(readGeneration = 2, readFrom = Some(restorePoint)), restoring = Some(kit), broker = topics)
    try
      // A message whose handler failed stays at the head of its group until the next publication or a
      // redelivery; a broker redelivers on its own, so the wait does too.
      val topic = s"goldvibe/${io.eitheror.casino.contract.facts.PlatformAudit.name}"
      try restored.eventually(10.minutes) {
          topics.redeliver(topic): Unit
          Option.when(counts(restored, "goldvibe").stored >= dayRows)(())
        }
      catch case e: AssertionError =>
        figure("rereadFailedCounts", counts(restored, "goldvibe"))
        figure("rereadFailedRecords", restored.records.size)
        import scala.jdk.CollectionConverters.*
        restored.log.list.asScala.filter(_.getLevel.isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)).takeRight(8)
          .foreach(e => figure("log", s"${e.getLoggerName} ${e.getFormattedMessage} ${Option(e.getThrowableProxy).map(t => t.getClassName + ": " + t.getMessage).getOrElse("")}".take(400)))
        throw e
      val reread = (System.nanoTime() - t0) / 1e9
      val c      = counts(restored, "goldvibe")
      figure("dayRows", dayRows)
      figure("rereadSeconds", f"$reread%.1f")
      figure("rereadStored", c.stored)
      assertEquals(c.stored, dayRows.toLong)
      assert(reread < 600, s"re-read $reread s")
    finally
      restored.stop()
      kit.rewire()
  }
