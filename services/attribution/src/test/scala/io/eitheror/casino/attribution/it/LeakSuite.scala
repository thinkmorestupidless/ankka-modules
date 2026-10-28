package io.eitheror.casino.attribution.it

import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.clicks.Click
import io.eitheror.casino.attribution.networks.Network
import io.eitheror.casino.attribution.tag.Pseudonyms
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.money.{Currency, MinorUnits}
import io.eitheror.casino.contract.personal.Personal
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets.UTF_8
import java.sql.{DriverManager, Types}
import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * SC-006, constitution V (T067): no personal datum leaves the brand or rests anywhere it should not. Markers
 * go in through every input: a click's address, user agent and a `From` header; a `player_profile_updated`
 * beside the registration on `casino.players` with a name, a date of birth and a street; a closure whose
 * reason names the player. A deposit, a withdrawal, a day's play, a closure and an erasure follow. Then every
 * table of the database, every fact on the topic, every feed page, every webhook body, every operator answer
 * and the captured log are searched for each marker, and the topic, the feed and the webhooks for the player
 * id. A service of its own (build.sbt), so its database and log hold this suite's work alone.
 */
class LeakSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 10.minutes

  private val appender = ListAppender[ILoggingEvent]()
  private val answers  = mutable.ArrayBuffer.empty[String]

  private lazy val kit: Kit =
    val k = Kit()
    k.addNetwork(): Unit
    k.makePseudonymKey()
    k.components.forKeyValueEntity(EntityId("programme")).call(Network.setMachine).invoke("acme/reader"): Unit
    assertEquals(k.request("POST", s"/v1/casino/backoffice/${k.brand}/attribution/signing-keys", headers = k.asOperator()).status, 201)
    k

  override def beforeAll(): Unit =
    appender.setContext(LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext])
    appender.start()
    LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[Logger].addAppender(appender)
    kit: Unit

  override def afterAll(): Unit = kit.stop()

  /** What the suite sends: a name, an email, a raw address, a fingerprint, a date of birth and a street. */
  val markers: Vector[(String, String)] = Vector(
    "name"          -> "Zebedee Quillfeather",
    "email"         -> "zebedee.quillfeather@leak.example",
    "address"       -> "198.51.100.77",
    "fingerprint"   -> "fp-7d1e0c2b9a4f",
    "date of birth" -> "1971-02-03",
    "street"        -> "17 Quillfeather Lane"
  )
  private def marker(kind: String) = markers.toMap.apply(kind)

  private def keep(r: Reply): Reply = { answers.synchronized(answers += r.text); r }
  private def op(method: String, path: String, body: Option[String] = None): Reply =
    keep(kit.request(method, s"/v1/casino/backoffice/${kit.brand}/$path", body, kit.asOperator()))

  /** Every row of every table in the service's database, as the table and the row's values joined; `bytea` read as UTF-8. */
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
          val values = (1 to meta.getColumnCount).flatMap { i =>
            meta.getColumnType(i) match
              case Types.BINARY | Types.VARBINARY | Types.LONGVARBINARY => Option(rs.getBytes(i)).map(String(_, UTF_8))
              case _                                                     => Option(rs.getString(i))
          }
          out += table -> values.mkString(" | ")
        out.result()
      }
    finally c.close()

  private def logs: Vector[String] =
    appender.list.asScala.toVector.map(e => e.getFormattedMessage + Option(e.getThrowableProxy).fold("")(t => " " + t.getMessage))

  private def topic: Vector[String] =
    kit.attributionMessages.map(m => String(m.message.payload, UTF_8) + " " + m.message.key.getOrElse("") + " " + m.message.metadata.toString)

  private def feedPages(): Vector[String] =
    val base  = s"/v1/casino/brands/${kit.brand}/attribution/networks/programme/feed?limit=5"
    val pages = Vector.newBuilder[String]
    var path  = Option(base)
    while path.isDefined do
      val r = kit.request("GET", path.get, headers = kit.asMachine("acme", "reader"))
      assertEquals(r.status, 200, r.text)
      pages += r.text + " " + r.headers.toString
      path = Option.when(r.json.get("facts").size > 0)(s"$base&cursor=${r.json.get("cursor").asText}")
    pages.result()

  private def webhookBodies: Vector[String] = kit.receiver.posts.map(p => p.body + " " + p.headers.toString)

  /** Where each value was found: a database table, an operator answer, a log line, or one of the carriers given. */
  private def found(values: Vector[String], carriers: Vector[(String, Vector[String])] = Vector.empty): Vector[String] =
    val db = database()
    values.flatMap { v =>
      db.collect { case (table, row) if row.contains(v) => s"$v in $table: ${row.take(300)}" } ++
        answers.synchronized(answers.toVector).filter(_.contains(v)).map(a => s"$v in an answer: ${a.take(300)}") ++
        logs.filter(_.contains(v)).map(l => s"$v in the log: ${l.take(300)}") ++
        carriers.flatMap((place, texts) => texts.filter(_.contains(v)).map(t => s"$v in $place: ${t.take(300)}"))
    }

  test("a click, a registration beside a personal sibling, a deposit, a withdrawal, a day's play, a closure and an erasure leave no marker behind") {
    val hook = op("POST", "attribution-webhooks", Some(Json.render(Json.obj("network" -> "programme", "address" -> kit.receiver.hookUrl,
      "secret" -> kit.receiver.secret))))
    assertEquals(hook.status, 201, hook.text)

    val clickId = kit.fresh("c")
    val tag     = kit.network.mint("aff-1", kit.fresh("lnk"), Some("autumn"), Vector("s1"))
    val click = kit.request("POST", "/v1/track/click", Some(Json.render(Json.obj("btag" -> tag, "clickId" -> clickId))),
      kit.fromInternet ++ Seq("X-Forwarded-For" -> s"${marker("address")}, 10.0.0.1", "User-Agent" -> marker("fingerprint"),
        "From" -> marker("email")))
    assertEquals(click.status, 200, click.text)
    val digest = kit.components.forKeyValueEntity(EntityId(clickId)).call(Click.get).invoke().click.flatMap(_.addressDigest)
      .getOrElse(fail("no address digest on the click"))

    val player = kit.fresh("p")
    kit.bindPlayer(Some(tag), player, clickId = Some(clickId))
    kit.publish(CasinoPlayers, PlayerProfileUpdated(player, Vector("givenName", "familyName", "dateOfBirth", "addressLine"),
      Some(Personal("Zebedee")), Some(Personal("Quillfeather")), Some(Personal(marker("date of birth"))), None,
      Some(Personal(marker("street"))), None, None, Some("ZA"), kit.now))
    kit.eventually("bound")(kit.record(player).binding): Unit

    val eur = Currency.unsafe("EUR")
    kit.publish(PaymentsPayments, PaymentCaptured(kit.fresh("pay"), player, "psp", MinorUnits.unsafe(5000), eur, kit.now))
    val payout = kit.fresh("w")
    kit.publish(CasinoWithdrawals, WithdrawalRequested(player, payout, MinorUnits.unsafe(1000), eur, "web", kit.now))
    kit.publish(CasinoWithdrawals, WithdrawalCompleted(player, payout, MinorUnits.unsafe(1000), eur, kit.now))

    val days   = DayClock(kit)
    val day    = days.evening()
    val wallet = WalletScript(kit, player)
    wallet.deposit(5000)
    wallet.bet("r-1", 600)
    wallet.win("r-1", 200)
    days.close(day, wallet)

    kit.publish(CasinoPlayers, PlayerClosed(player, "active", "closed", "operator", Some("op-1"),
      Some(s"${marker("name")} asked, ${marker("email")}"), kit.now))
    assertEquals(kit.request("POST", s"/v1/casino/brands/${kit.brand}/attribution/players/$player/erasure", headers = kit.asService())
      .status, 200)

    val p = Pseudonyms.derive(Pseudonyms.keyBytes(kit.testKit.secrets.get(Pseudonyms.secretName("programme")).get), player)
    val kinds = Vector("click/", "registration/", "first-deposit/", "deposit/", "withdrawal-requested/", "withdrawal-completed/", "ggr-daily/", "player-closed/")
    kit.eventually("every kind of fact on the topic", 60.seconds)(Option.when(kinds.forall(k =>
      kit.attributionMessages.exists(_.message.metadata.get("ce-id").exists(_.startsWith(k)))))(())): Unit
    val emitted = kit.attributionMessages.size
    kit.eventually("every fact posted to the webhook", 60.seconds)(Option.when(kit.receiver.posts.size >= emitted)(())): Unit

    op("GET", "attribution/status"): Unit
    op("GET", s"attribution/records/$player"): Unit
    op("GET", "attribution/networks"): Unit
    op("GET", "attribution/networks/programme"): Unit
    op("GET", "attribution/settings"): Unit
    op("GET", "attribution/signing-keys"): Unit
    op("GET", "attribution-webhooks?network=programme"): Unit
    op("GET", "attribution-webhooks/undelivered?network=programme"): Unit
    Thread.sleep(1500) // the views' last poll and the last log lines

    val carriers = Vector("a fact on the topic" -> topic, "a feed page" -> feedPages(), "a webhook body" -> webhookBodies)
    assert(carriers.forall(_._2.nonEmpty), carriers.map((c, t) => s"$c: ${t.size}").mkString(", "))
    assert(topic.exists(_.contains(p)), "the pseudonym is on the topic")

    val leaks = found(markers.map(_._2) :+ "Zebedee" :+ "Quillfeather", carriers) ++
      carriers.flatMap((place, texts) => texts.filter(_.contains(player)).map(t => s"the player id in $place: ${t.substring(math.max(0, t.indexOf(player) - 150)).take(300)}"))
    assert(leaks.isEmpty, leaks.mkString("\n"))

    // The address's digest is on the click's row alone: no fact, page, post, answer, log line or other row.
    val digestRows = database().filter(_._2.contains(digest))
    assert(digestRows.nonEmpty && digestRows.forall(_._2.contains(clickId)), digestRows.mkString("\n"))
    val elsewhere = (carriers.flatMap(_._2) ++ answers ++ logs).filter(_.contains(digest))
    assert(elsewhere.isEmpty, elsewhere.mkString("\n"))
  }

  test("the search finds a value planted in a log line, a table and an answer (positive control)") {
    val planted = s"planted-${UUID.randomUUID()}"
    LoggerFactory.getLogger("io.eitheror.casino.attribution").info(s"a line with $planted in it")
    assert(found(Vector(planted)).exists(_.contains("in the log")))
    keep(Reply(200, s"""{"x":"$planted"}""")): Unit
    assert(found(Vector(planted)).exists(_.contains("in an answer")))
    kit.bindPlayer(None, planted)
    kit.eventually("registered")(kit.record(planted).registeredAt): Unit
    assert(found(Vector(planted)).exists(_.contains("in public.")), found(Vector(planted)).mkString("\n"))
  }
