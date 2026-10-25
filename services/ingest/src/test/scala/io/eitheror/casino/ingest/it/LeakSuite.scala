package io.eitheror.casino.ingest.it

import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.eitheror.casino.ingest.requests.Outcome
import io.eitheror.casino.ingest.shape.Digests
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets.UTF_8
import java.sql.{DriverManager, Types}
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * T025, T048 (SC-006, FR-018): after a create, a submit and a read carrying every customer field, a key
 * refusal, a rate refusal and a lost answer, no customer field, no forwarded-for address and no raw key is
 * in the service's database (every table), its log, the audit records or any answer it gave an operator;
 * the key's digest is in no log line (it is the index's id, so it is in the store by design). A service of
 * its own (build.sbt), so its database holds this suite's requests alone.
 */
class LeakSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 5.minutes

  private lazy val kit = Kit()
  private val appender = ListAppender[ILoggingEvent]()
  private val answers  = mutable.ArrayBuffer.empty[String]

  override def beforeAll(): Unit =
    appender.setContext(LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext])
    appender.start()
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].addAppender(appender)
    kit: Unit

  override def afterAll(): Unit = kit.stop()

  private val customer = Vector("customerEmail" -> "leak-marker@example.com", "customerFirstName" -> "Lovelacemarker",
    "customerLastName" -> "Babbagemarker", "customerPhone" -> "+15550100987", "customerAddress" -> "1 Marker Street",
    "customerCity" -> "Markerton", "customerPostCode" -> "MK1 7QZ", "customerDob" -> "1815-12-10")
  private val address = "203.0.113.77"
  private val rawKey  = s"mk_leak_${UUID.randomUUID().toString.replace("-", "")}"
  private val badKey  = s"mk_leak_bad_${UUID.randomUUID().toString.replace("-", "")}"

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

  private def logs: Vector[String] =
    appender.list.asScala.toVector.map(e => e.getFormattedMessage + Option(e.getThrowableProxy).fold("")(t => " " + t.getMessage))

  /** Where each value was found: the database column, a log line, an audit record, or an answer. */
  private def found(stored: Vector[String], logged: Vector[String]): Vector[String] =
    val db = database()
    stored.flatMap { v =>
      db.collect { case (where, text) if text.contains(v) => s"$v in $where" } ++
        kit.auditTexts().filter(_.contains(v)).map(_ => s"$v in an audit record") ++
        answers.synchronized(answers.toVector).filter(_.contains(v)).map(a => s"$v in an answer: ${a.take(300)}")
    } ++ (stored ++ logged).flatMap(v => logs.filter(_.contains(v)).map(l => s"$v in the log: $l"))

  test("a create, a submit, a read, a key refusal, a rate refusal and a lost answer leave no customer field, address or key behind") {
    val key = RawKey(rawKey, "k-leak", "sb-leak")
    kit.issue(key)
    val served = keep(kit.create(Some(key), kit.body(customer*), "X-Forwarded-For" -> s"$address, 10.0.0.1"))
    assertEquals(served.status, 200, served.text)
    assertEquals(kit.merchant.creates.head.get("customerEmail").asText, "leak-marker@example.com") // forwarded, not kept
    val ticket    = served.str("session_token")
    val paymentId = keep(kit.submit(ticket, "sim-eur", customer*)).str("paymentId")
    assertEquals(keep(kit.readPayment(paymentId, key = Some(key))).status, 200)
    assertEquals(keep(kit.create(Some(RawKey(badKey, "k", "x")), kit.body(customer*))).status, 401)

    val other = kit.issueKey()
    keep(kit.request("PUT", s"/v1/ingest/sub-brands/${other.subBrand}/rate", Some("""{"perMinute":1}"""), kit.asOperator())): Unit
    kit.create(Some(other), kit.body(customer*)): Unit
    assertEquals(keep(kit.create(Some(other), kit.body(customer*), "X-Forwarded-For" -> address)).status, 429)

    kit.merchant.hold("t-leak")
    assertEquals(keep(kit.create(Some(key), kit.body((("transactionId" -> "t-leak") +: customer)*))).status, 503)
    kit.merchant.release("t-leak")
    kit.eventually("the lost answer", 30.seconds)(kit.records(key.subBrand).find(_.outcome == Outcome.LostAnswer)): Unit

    keep(kit.request("GET", s"/v1/ingest/sub-brands/${key.subBrand}/requests", None, kit.asOperator())): Unit
    keep(kit.request("GET", s"/v1/ingest/sub-brands/${other.subBrand}/requests", None, kit.asOperator())): Unit
    keep(kit.request("GET", "/v1/ingest/lost-answers", None, kit.asOperator())): Unit
    kit.eventually("both refusals audited", 30.seconds)(Option(kit.audit().size).filter(_ >= 2)): Unit
    Thread.sleep(1500) // the views' last poll and the last log lines

    val leaks = found(customer.map(_._2) :+ address :+ rawKey :+ badKey, Vector(Digests.ofKey(rawKey), Digests.ofKey(badKey)))
    assert(leaks.isEmpty, leaks.mkString("\n"))
  }

  test("the scan finds a value planted in a log line, a table and an answer (positive control)") {
    val planted = s"planted-${UUID.randomUUID()}"
    LoggerFactory.getLogger("io.eitheror.casino.ingest").info(s"a line with $planted in it")
    assert(found(Vector(planted), Vector.empty).exists(_.contains("in the log")))
    keep(Reply(200, s"""{"x":"$planted"}""")): Unit
    assert(found(Vector(planted), Vector.empty).exists(_.contains("in an answer")))
    kit.create(Some(RawKey(rawKey, "k-leak", "sb-leak")), kit.body("transactionId" -> planted)): Unit
    assert(found(Vector(planted), Vector.empty).exists(_.contains("in public.")), found(Vector(planted), Vector.empty).mkString("\n"))
  }
