package io.eitheror.casino.ingest.keys

import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.thinkmorestupidless.ankka.testkit.{LogCapturing, ScriptedServices}
import io.eitheror.casino.ingest.Recorded
import io.eitheror.casino.ingest.keys.KeyCodecs.given
import io.eitheror.casino.ingest.merchant.MerchantClient
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.jdk.CollectionConverters.*

/** FR-004's "only at start", research R3: merchant's keys into the index; a failure logged without a digest. */
class KeyRefreshSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-10-22T10:00:00Z")

  private def logged(f: => Unit): Vector[String] =
    val appender = ListAppender[ILoggingEvent]()
    appender.setContext(LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext])
    appender.start()
    val logger = LoggerFactory.getLogger(KeyRefresh.getClass).asInstanceOf[Logger]
    logger.addAppender(appender)
    try f finally logger.detachAppender(appender): Unit
    appender.list.asScala.toVector.map(_.getFormattedMessage)

  test("three entries are three issues, and the count is logged, never a digest") {
    val body  = """{"keys":[{"digest":"d1","keyId":"k-1","subBrandId":"sb-1"},{"digest":"d2","keyId":"k-2","subBrandId":"sb-1"},{"digest":"d3","keyId":"k-3","subBrandId":"sb-2"}]}"""
    val m     = MerchantClient.http(ScriptedServices().answer("merchant")(_ => ScriptedServices.json(body)), "merchant")
    val r     = Recorded()
    var count = 0
    val lines = logged { count = KeyRefresh.run(m, r.client, at) }
    assertEquals(count, 3)
    assertEquals(r.calls.map(c => (c.entity, c.as[Issue])), Vector(("k-1", Issue("d1", "sb-1", at)), ("k-2", Issue("d2", "sb-1", at)),
      ("k-3", Issue("d3", "sb-2", at))))
    assert(lines.exists(_.contains("3 keys")), lines.toString)
    assert(!lines.exists(l => l.contains("d1") || l.contains("d2")), lines.toString)
  }

  test("a merchant that fails or answers 503 issues nothing, throws nothing, and is logged at warn without a digest") {
    Seq(ScriptedServices().unanswered("merchant"), ScriptedServices().answer("merchant")(_ => ScriptedServices.json("""{"error":"x"}""", 503)))
      .foreach { services =>
        val r     = Recorded()
        val lines = logged { assertEquals(KeyRefresh.run(MerchantClient.http(services, "merchant"), r.client, at), 0) }
        assertEquals(r.calls, Vector.empty)
        assert(lines.exists(_.contains("not refreshed")), lines.toString)
      }
  }
