package io.eitheror.casino.rg.it

import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.rg.Json
import io.eitheror.casino.rg.Json.*
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets.UTF_8
import java.sql.{DriverManager, Types}
import java.time.Duration
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * T040 (FR-032, SC-006): an operator's case note, case-close reason and break-lift reason, and after `Erase`
 * none of them in any table but the event journal, in any fact, audit record or log line, or in any answer;
 * the values, dates, types and statuses still there.
 *
 * The journal keeps them until ankka 042's `Personal` encrypts a field under the player's subject (the
 * contract's `Personal` says so): that is the one place allowed, named, so a new place fails here.
 */
class LeakSuite extends munit.FunSuite:
  override val munitTimeout = 3.minutes

  private lazy val kit = SharedKit.kit
  private val appender = ListAppender[ILoggingEvent]()

  override def beforeAll(): Unit =
    appender.setContext(LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext])
    appender.start()
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].addAppender(appender)
    kit.reset()

  override def afterAll(): Unit =
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].detachAppender(appender): Unit

  /** The tables an erased value may stay in until ankka 042: the events, as written. */
  private val Journal = Set("event_journal")

  /** Every value of every row of every table, as text, with the table it came from; `bytea` read as UTF-8. */
  private def database(): Vector[(String, String)] =
    val c = DriverManager.getConnection(kit.testKit.jdbcUrl, "ankka", "ankka")
    try
      val rs     = c.getMetaData.getTables(null, null, "%", Array("TABLE"))
      val tables = Iterator.continually(rs.next()).takeWhile(identity).map(_ => (rs.getString("TABLE_SCHEM"), rs.getString("TABLE_NAME")))
        .toVector.filterNot(t => t._1 == "pg_catalog" || t._1 == "information_schema")
      tables.flatMap { (schema, table) =>
        val rs   = c.createStatement().executeQuery(s"""SELECT * FROM "$schema"."$table"""")
        val meta = rs.getMetaData
        val out  = Vector.newBuilder[(String, String)]
        while rs.next() do
          (1 to meta.getColumnCount).foreach { i =>
            val text = meta.getColumnType(i) match
              case Types.BINARY | Types.VARBINARY | Types.LONGVARBINARY => Option(rs.getBytes(i)).map(String(_, UTF_8))
              case _                                                     => Option(rs.getString(i))
            text.foreach(t => out += (table -> t))
          }
        out.result()
      }
    finally c.close()

  test("after erasure no note or reason is anywhere but the journal; the amounts, dates, types and statuses remain"):
    val p        = s"p-${UUID.randomUUID().toString.take(8)}"
    val marker   = UUID.randomUUID().toString.take(8)
    val note     = s"note-$marker the player's partner called"
    val closing  = s"close-$marker spoke to their bank"
    val lifting  = s"lift-$marker agreed with the player"

    kit.json("PUT", kit.playerPath(s"/$p/limits/DEPOSIT_DAILY"), Json.obj("value" -> "10000", "currency" -> "EUR"), kit.asPlayer(p)): Unit
    val opened = (kit.json("POST", kit.operatorPath(s"/$p/cases"), Json.obj("caseType" -> "account_block", "note" -> note), kit.asOperator()))
    val caseId = opened.str("caseId")
    kit.json("POST", kit.operatorPath(s"/$p/cases/$caseId/notes"), Json.obj("note" -> note), kit.asOperator()): Unit
    kit.json("POST", kit.operatorPath(s"/$p/cases/$caseId/close"), Json.obj("reason" -> closing), kit.asOperator()): Unit
    kit.json("PUT", kit.playerPath(s"/$p/cooling-off"), Json.obj("until" -> kit.now().plus(Duration.ofDays(2)).toString), kit.asPlayer(p)): Unit
    kit.json("POST", kit.operatorPath(s"/$p/cooling-off/lift"), Json.obj("reason" -> lifting), kit.asOperator()): Unit

    kit.eventually("the audit records")(Option.when(kit.facts(PlatformAudit).count { case r: AuditRecord => r.subjectId == p } >= 4)(()))
    kit.eventually("the case facts")(Option.when(kit.facts(CasinoPlayers).count { case f: RgCaseClosed => f.playerId == p; case _ => false } >= 2)(()))

    val before = kit.request("GET", kit.operatorPath(s"/$p"), None, kit.asOperator())
    assert(before.text.contains(note) && before.text.contains(closing), "the operator reads them before the erasure")

    assertEquals(kit.request("POST", kit.servicePath(s"/erasure/players/$p"), None, kit.asService("players")).status, 200)
    val after    = (kit.request("GET", kit.operatorPath(s"/$p"), None, kit.asOperator()))
    val exported = (kit.request("GET", kit.servicePath(s"/exports/players/$p"), None, kit.asService("players")))
    val history  = (kit.request("GET", kit.playerPath(s"/$p/history"), None, kit.asPlayer(p)))

    val secrets   = Vector(note, closing, lifting)
    val published = (kit.broker.publishedTo(CasinoPlayers.name) ++ kit.broker.publishedTo(PlatformAudit.name)).map(d => String(d.message.payload, UTF_8))
    val logged    = appender.list.asScala.toVector.map(e => e.getFormattedMessage + Option(e.getThrowableProxy).fold("")(t => " " + t.getMessage))
    val stored    = database()
    val answered  = Vector(after.text, exported.text, history.text)

    val found = secrets.flatMap { s =>
      stored.collect { case (table, text) if text.contains(s) && !Journal(table) => s"$s in $table" } ++
        published.filter(_.contains(s)).map(_ => s"$s in a fact or audit record") ++
        logged.filter(_.contains(s)).map(l => s"$s in the log: ${l.take(200)}") ++
        answered.filter(_.contains(s)).map(a => s"$s in an answer: ${a.take(200)}")
    }
    assertEquals(found, Vector.empty)
    assert(stored.exists((table, text) => Journal(table) && text.contains(note)),
      s"the scan reads the journal, where the note stays until ankka 042; tables read: ${stored.map(_._1).distinct}")

    val full = after.json
    assertEquals(full.objects("limits").flatMap(_.long("value")), Vector(10_000L))
    assertEquals(full.objects("cases").map(c => (c.text("caseType"), c.text("status"))).toSet,
      Set[(Option[String], Option[String])]((Some("account_block"), Some("closed")), (Some("cool_off"), Some("closed"))))
    assert(full.objects("cases").forall(_.text("openedAt").isDefined))
    assert(full.objects("history").nonEmpty)
