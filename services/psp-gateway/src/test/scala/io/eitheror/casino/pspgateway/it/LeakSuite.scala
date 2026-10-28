package io.eitheror.casino.pspgateway.it

import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.pspgateway.sessions.*
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets.UTF_8
import java.sql.{DriverManager, Types}
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * US5, SC-004, FR-020 to FR-022: after a deposit and a payout through every provider, with every customer
 * field filled, no credential value, no customer field and no private key is in the service's database
 * (every table: the journal, the views, the key value store), in what it logged, or in what it answered.
 * A service of its own (build.sbt), so its database holds this suite's sessions alone. Spans are not
 * scanned: the integration kit exposes none; the log lines carry their trace and span ids only.
 */
class LeakSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 10.minutes

  private lazy val kit = Kit.withStub()

  private val appender = ListAppender[ILoggingEvent]()
  private val answers  = mutable.ArrayBuffer.empty[String]

  override def beforeAll(): Unit =
    appender.setContext(LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext])
    appender.start()
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].addAppender(appender)
    kit: Unit

  override def afterAll(): Unit = kit.stop()

  /**
   * What must appear nowhere: every credential value long enough to be told from ordinary words, and the
   * customer's fields. Unity Finance's `checkoutId.<METHOD>` is left out: data-model.md keeps it on the
   * session as `providerData.checkoutId`, the invoice's `ik_co_id` the webhook must name (`webhook.go:357`),
   * an identifier the provider echoes and the player's browser is sent, not a secret.
   */
  private val Secrets: Vector[String] =
    val credentials = PspStub.credentials.values.flatMap(_.filterNot(_._1.startsWith("checkoutId.")).values).toVector.flatMap { v =>
      if v.contains("PRIVATE KEY") then v.linesIterator.filterNot(_.startsWith("-----")).filter(_.length >= 16).toVector
      else Vector(v)
    }
    val customer = Vector("player@example.com", "Lovelace", "203.0.113.7", "+15550100", "1 King St", "M5H 1A1", "Toronto",
      "DE89370400440532013000", "TXyz0000000000000000000000000000")
    (credentials ++ customer).filter(_.length >= 6).filterNot(Set("casino")).distinct

  private def keep(r: Reply): Reply = { answers.synchronized(answers += r.text); r }

  /** Every value of every row of every table in the service's own database, as text; `bytea` read as UTF-8. */
  private def database(): Vector[(String, String)] =
    val c = DriverManager.getConnection(kit.testKit.jdbcUrl, "ankka", "ankka")
    try
      val tables = Iterator.unfold(c.getMetaData.getTables(null, null, "%", Array("TABLE"))) { rs =>
        Option.when(rs.next())((rs.getString("TABLE_SCHEM") + "." + rs.getString("TABLE_NAME"), rs))
      }.toVector.filterNot(t => t.startsWith("pg_catalog.") || t.startsWith("information_schema."))
      tables.flatMap { table =>
        val rs   = c.createStatement().executeQuery(s"""SELECT * FROM ${table.split('.').map(p => s""""$p"""").mkString(".")}""")
        val meta = rs.getMetaData
        val out  = Vector.newBuilder[(String, String)]
        while rs.next() do
          (1 to meta.getColumnCount).foreach { i =>
            val text = meta.getColumnType(i) match
              case Types.BINARY | Types.VARBINARY | Types.LONGVARBINARY => Option(rs.getBytes(i)).map(String(_, UTF_8))
              case _                                                     => Option(rs.getString(i))
            text.foreach(t => out += (s"$table.${meta.getColumnName(i)}" -> t))
          }
        out.result()
      }
    finally c.close()

  private def logs: Vector[String] = appender.list.asScala.toVector.map(e => e.getFormattedMessage + Option(e.getThrowableProxy).fold("")(t => " " + t.getMessage))

  /** Where each value was found: the database column, a log line, or an answer. */
  private def found(values: Vector[String]): Vector[String] =
    val db = database()
    values.flatMap { v =>
      db.collect { case (where, text) if text.contains(v) => s"$v in $where" } ++
        logs.filter(_.contains(v)).map(l => s"$v in the log: $l") ++
        answers.synchronized(answers.toVector).filter(_.contains(v)).map(a => s"$v in an answer: ${a.take(300)}")
    }

  test("a deposit through every provider and a payout through every payout provider leave no secret and no customer field behind") {
    Provider.values.foreach(kit.credential("sb-1", _))
    kit.payoutTerminals("goldvibe")
    Provider.values.foreach { provider =>
      kit.psp.control(provider, "pending")
      val paymentId = UUID.randomUUID().toString
      assertEquals(keep(kit.initiate(paymentId, provider)).status, 200, provider.wire)
      kit.relay(paymentId): Unit
      kit.psp.webhook(provider, kit.stubReference(paymentId, provider), "outcome" -> "success"): Unit
      kit.eventually(s"${provider.wire} delivered", 60.seconds)(Option(kit.merchant.deliveredTo(paymentId)).filter(_.nonEmpty))
      keep(kit.session(paymentId)): Unit
    }
    Kit.PayoutProviders.foreach { provider =>
      kit.psp.control(provider, "pending")
      val requestId = s"w-${UUID.randomUUID()}"
      assertEquals(keep(kit.withdraw("goldvibe", requestId, provider)).status, 200, provider.wire)
      val reference =
        if provider == Provider.Morphosis then kit.payout(requestId).claim.flatMap(_.generated.get("token")).get else requestId
      kit.psp.webhook(provider, reference, "outcome" -> "success"): Unit
      kit.eventually(s"${provider.wire} payout settled", 60.seconds)(kit.payout(requestId).outcome)
      keep(kit.withdrawal("goldvibe", requestId)): Unit
    }
    keep(kit.request("GET", "/status", None, kit.asService("local", "ingest"))): Unit
    keep(kit.request("GET", "/v1/psp/outcomes/undelivered", None, kit.asOperator())): Unit
    Thread.sleep(1000) // the views and the deliverers' last log lines

    val leaks = found(Secrets)
    assert(leaks.isEmpty, leaks.mkString("\n"))
  }

  test("the scan finds a value planted in a log line, a table and an answer (positive control)") {
    val planted = s"planted-${UUID.randomUUID()}"
    LoggerFactory.getLogger("io.eitheror.casino.pspgateway").info(s"a line with $planted in it")
    assert(found(Vector(planted)).exists(_.contains("in the log")))
    keep(Reply(200, s"""{"x":"$planted"}""")): Unit
    assert(found(Vector(planted)).exists(_.contains("in an answer")))
    kit.request("POST", "/v1/psp/hpp/initiate", Some(kit.initiateBody(planted, Provider.T365, "customerId" -> planted)), kit.asMerchant): Unit
    assert(found(Vector(planted)).exists(_.contains("in public.") ), found(Vector(planted)).mkString("\n"))
  }
