package io.eitheror.casino.provider.edge.steps

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.contract.facts.PlatformAudit
import io.eitheror.casino.provider.core.{Callback, Sweep, Wiring}
import io.eitheror.casino.provider.edge.ProviderTestKit
import io.eitheror.casino.provider.edge.ProviderTestKit.{staff, staffAs}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalDate, ZoneOffset}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * features/games/operators.feature (US6): the operator routes read through the kit as a staff
 * token would, the audit records read from the kit's in-memory broker, and the sweep run as its
 * timer runs it. The reporting zone is the default, UTC.
 */
class OperatorsFeatures extends GherkinSuite(s"${sys.props("casino.root")}/features/games/operators.feature") with LogCapturing:

  override val munitTimeout = 3.minutes

  private val mapper = ObjectMapper()
  private def kit    = ProviderTestKit.kit
  private def game   = kit.game
  private def json(text: String): JsonNode = mapper.readTree(text)
  private val reader = staff("brand_goldvibe")
  private val operator = "op-ana"
  private val writer = staffAs(operator, "games")

  override def afterEach(context: AfterEach): Unit =
    kit.offset = 0L
    kit.brand.failing("credit", 0)
    kit.restoreGoldvibe()

  private def fresh(tag: String): (String, String) = (s"L-ops-$tag-${System.nanoTime()}", s"p-ops-$tag-${System.nanoTime()}")

  /** A round of a stake of 10 and a win of `win`, its credit acknowledged unless `acknowledged` is false. */
  private def round(ticket: String, player: String, roundId: String, win: Long = 25L, acknowledged: Boolean = true): String =
    kit.open(ticket, player)
    assertEquals(game.stake(ticket, roundId, 10L).text("status"), Some("ok"))
    val settled = game.settle(ticket, roundId, win)
    assertEquals(settled.status, 202, settled.body.toString)
    val id = settled.text("callbackId").get
    if acknowledged then kit.eventually()(Option(kit.callback(id)).filter(_.outcome == Callback.Acknowledged)): Unit
    id

  private def get(path: String, token: String = reader): JsonNode =
    val a = kit.asStaff("GET", path, token)
    assertEquals(a.status, 200, a.body)
    a.json

  Given("a house provider serving a brand") { () =>
    kit.start()
    game.up()
    kit.brand.clear()
  }

  // ---- reading a round ----------------------------------------------------------------------------

  private var ticket = ""
  private var roundId = ""
  private var opened: JsonNode = null

  Given("a round played at the brand") { () =>
    val (t, p) = fresh("read")
    ticket = t
    roundId = "r-read"
    round(ticket, p, roundId)
  }

  When("an operator reads the brand's rounds and opens that round") { () =>
    kit.eventually() {
      Option(get("/v1/provider/brands/goldvibe/rounds?day=" + LocalDate.now(ZoneOffset.UTC)))
        .filter(_.get("rounds").elements().asScala.exists(r => r.get("launchToken").asText() == ticket))
    }: Unit
    opened = kit.eventually() {
      Option(get(s"/v1/provider/brands/goldvibe/rounds/$ticket")).filter(_.get("callbacks").size >= 3) // check, debit, credit
    }
  }

  Then("each provider call of the round is listed with its content as sent, the signing key's id, the time sent, each attempt, the answer's word and balance and the time taken") { () =>
    val calls = opened.get("callbacks").elements().asScala.toVector.filter(c => Set("debit", "credit").contains(c.get("kind").asText()))
    assertEquals(calls.map(_.get("kind").asText()), Vector("debit", "credit"))
    calls.foreach { c =>
      assert(json(c.get("body").asText()).get("round").asText() == roundId, c.toString)
      val sent = c.get("sent").elements().asScala.toVector
      assert(sent.nonEmpty, c.toString)
      sent.foreach { a =>
        assertEquals(a.get("keyId").asText(), kit.keys.currentKeyId)
        assert(Try(Instant.parse(a.get("sentAt").asText())).isSuccess, a.toString)
        assert(a.get("attempt").asInt() >= 1 && a.get("tookMillis").asLong() >= 0, a.toString)
      }
      assertEquals(sent.last.get("status").asText(), "ok")
      assert(!sent.last.get("balance").isNull, sent.last.toString)
    }
    // FR-025: the round's calls and its launch are counted where Prometheus reads them.
    val metrics = kit.asStaff("GET", "/v1/provider/metrics", writer)
    assertEquals(metrics.status, 200, metrics.body)
    Vector("""eitheror_games_callbacks_sent_total{brand="goldvibe",kind="credit"}""", """eitheror_games_callbacks_sent_total{brand="goldvibe",kind="debit"}""",
      """eitheror_games_launches_total{brand="goldvibe",game="test-slot"}""", """eitheror_games_callbacks_unanswered{brand="goldvibe"}""")
      .foreach(line => assert(metrics.body.linesIterator.exists(_.startsWith(line + " ")), s"$line in\n${metrics.body}"))
  }

  // ---- unanswered calls -------------------------------------------------------------------------

  private var credit = ""

  private def unansweredCredit(tag: String): Unit =
    val (t, p) = fresh(tag)
    ticket = t
    kit.brand.failing("credit", 100000)
    credit = round(ticket, p, s"r-$tag", acknowledged = false)
    kit.eventually()(Option(kit.callback(credit)).filter(_.attempts.size >= 2)): Unit

  Given("a credit the brand answered {string} on every attempt so far") { (word: String) =>
    assertEquals(word, "error") // the fake brand's failing answer
    unansweredCredit("unanswered")
  }

  private var listed: JsonNode = null

  When("an operator lists the brand's unanswered calls") { () =>
    listed = kit.eventually() {
      get("/v1/provider/brands/goldvibe/unanswered").get("unanswered").elements().asScala
        .find(r => r.get("callbackId").asText() == credit && r.get("attempts").asInt() >= 2) // the view catches up with the entity
    }
  }

  Then("the credit is listed with its attempts and the time of its next retry") { () =>
    assertEquals(listed.get("kind").asText(), "credit")
    assertEquals(listed.get("outcome").asText(), Callback.Pending)
    assert(listed.get("attempts").asInt() >= 2, listed.toString)
    assert(listed.get("sent").elements().asScala.forall(_.get("status").asText() == "error"), listed.toString)
    assert(Try(Instant.parse(listed.get("nextRetryAt").asText())).isSuccess, listed.toString)
  }

  // ---- abandoning --------------------------------------------------------------------------------

  Given("an unanswered call") { () => unansweredCredit("abandon") }

  private var answer: ProviderTestKit.Answer = null

  When("an operator abandons it with a reason") { () =>
    answer = kit.asStaff("POST", s"/v1/provider/brands/goldvibe/abandon/$credit", writer, Some("""{"reason":"settled by hand with the brand"}"""))
    assertEquals(answer.status, 200, answer.body)
  }

  Then("no further attempt is made") { () =>
    val attempts = kit.callback(credit).attempts.size
    Thread.sleep(1500) // five retry intervals of the kit's schedule
    assertEquals(kit.callback(credit).attempts.size, attempts)
    assertEquals(kit.callback(credit).outcome, Callback.Abandoned)
  }

  Then("the call is listed as abandoned with the reason and the operator") { () =>
    val row = kit.eventually() {
      get("/v1/provider/brands/goldvibe/unanswered").get("unanswered").elements().asScala
        .find(r => r.get("callbackId").asText() == credit && r.get("outcome").asText() == Callback.Abandoned)
    }
    assertEquals(row.get("abandoned").get("reason").asText(), "settled by hand with the brand")
    assertEquals(row.get("abandoned").get("by").asText(), operator)
  }

  // ---- the day export ------------------------------------------------------------------------------

  private var dayTickets = Map.empty[String, String]

  /** Moves the core's clock to noon UTC of `day`. */
  private def at(day: String): Unit =
    kit.offset = LocalDate.parse(day).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli - System.currentTimeMillis()

  Given("rounds settled at the brand on {string} and on {string}") { (first: String, second: String) =>
    dayTickets = Vector(first, second).map { day =>
      at(day)
      val (t, p) = fresh(s"day-$day")
      round(t, p, s"r-$day", win = if day == first then 0L else 40L)
      day -> t
    }.toMap
    kit.offset = 0L
  }

  private var exported: Vector[JsonNode] = Vector.empty

  When("finance exports the brand's settled rounds for {string}") { (day: String) =>
    exported = kit.eventually() {
      val a = kit.asStaff("GET", s"/v1/provider/brands/goldvibe/export/$day", reader)
      assertEquals(a.status, 200, a.body)
      Option(a.body.linesIterator.filter(_.nonEmpty).map(json).toVector).filter(_.exists(_.get("launchToken").asText() == dayTickets(day)))
    }
  }

  Then("the export holds every round settled that day with its stake, win, outcome and transaction ids, and none of the next day's") { () =>
    assert(exported.forall(_.get("day").asText() == "2026-11-07"), exported.mkString("\n"))
    val ours = exported.filter(_.get("launchToken").asText() == dayTickets("2026-11-07"))
    assertEquals(ours.size, 1)
    val r = ours.head
    assertEquals((r.get("stake").asLong(), r.get("win").asLong(), r.get("outcome").asText()), (10L, 0L, "lost"))
    assertEquals(r.get("debits").size, 1)
    assertEquals(r.get("credits").size, 1)
    // Whether the brand acknowledged the credit that settled it (review, should fix 2).
    assert(Set("acknowledged", "pending").contains(r.get("settlement").asText()), r.toString)
    assert(!exported.exists(_.get("launchToken").asText() == dayTickets("2026-11-08")))
  }

  // ---- the audit -------------------------------------------------------------------------------------

  private var before = 0

  private def auditRecords: Vector[JsonNode] =
    kit.broker.publishedTo(PlatformAudit.name).toVector.map(d => json(String(d.message.payload, UTF_8)))

  Given("an operator signed in to the installation") { () => before = auditRecords.size }

  When("the operator writes a brand entry, starts a key rotation or abandons an unanswered call") { () =>
    assertEquals(kit.asStaff("PUT", "/v1/provider/brands/goldvibe", writer, Some(kit.entryBody("goldvibe", kit.brand.address))).status, 200)
    if !get("/v1/provider/keys", writer).get("next").isNull then
      assertEquals(kit.asStaff("POST", "/v1/provider/keys/switch", writer).status, 200)
    assertEquals(kit.asStaff("POST", "/v1/provider/keys/start", writer).status, 200)
    unansweredCredit("audit")
    assertEquals(kit.asStaff("POST", s"/v1/provider/brands/goldvibe/abandon/$credit", writer, Some("""{"reason":"audit"}""")).status, 200)
  }

  Then("an audit record on the platform audit topic names the operator, the act and the time") { () =>
    val records = kit.eventually() {
      Option(auditRecords.drop(before)).filter(rs => Set("brand_entry_written", "key_rotation_started", "callback_abandoned").subsetOf(rs.map(r => text(r, "action")).toSet))
    }
    val ours = records.filter(r => Set("brand_entry_written", "key_rotation_started", "callback_abandoned").contains(text(r, "action")))
    ours.foreach { r =>
      assertEquals(text(r, "actor_id"), operator, r.toString)
      assertEquals(text(r, "service"), "provider")
      assert(Try(Instant.parse(text(r, "occurred_at"))).isSuccess, r.toString)
    }
    assertEquals(ours.find(r => text(r, "action") == "callback_abandoned").map(r => text(r, "subject_id")), Some(credit))
  }

  /** A field of a record, wherever the fact's framing put it. */
  private def text(r: JsonNode, field: String): String =
    Option(r.get(field)).orElse(Option(r.findValue(field))).map(_.asText()).getOrElse("")

  // ---- retention --------------------------------------------------------------------------------------

  private var old = ""
  private var young = ""

  /** A swept session is deleted: it answers as one that never was. */
  private def gone(ticket: String): Boolean = Try(kit.session(ticket)).fold(_ => true, s => !s.launched)

  Given("a round whose last provider call is older than the installation's retention for the house provider") { () =>
    val (o, po) = fresh("old")
    old = o
    round(old, po, "r-old")
    // The sweep finds a run through the run view; a round 90 days old is long in it, so wait until
    // this one's three calls (check, debit, credit) are, before its age is made.
    kit.eventually() {
      Option(Sweep.views.get.forView(io.eitheror.casino.provider.core.RunCallbacks)
        .ask(io.eitheror.casino.provider.core.RunCallbacks.byRun, 100, "run" -> old, "after_at" -> "-1", "after_id" -> ""))
        .filter(rows => rows.size == 3 && rows.forall(r => Callback.Final.contains(r.outcome)))
    }: Unit
    kit.offset += (Sweep.retention + 1.day).toMillis
    val (y, py) = fresh("young")
    young = y
    round(young, py, "r-young")
  }

  private var unpaid   = ""
  private var unpaidAt = ""
  private var demo     = ""

  Given("a round whose credit was exhausted and a session in fun mode, both older than the installation's retention for the house provider") { () =>
    val (u, pu) = fresh("unpaid")
    unpaid = u
    unpaidAt = round(unpaid, pu, "r-unpaid")
    // As the schedule's end would leave it: the brand never acknowledged it (the record moves straight there).
    kit.testKit.componentClient.forEventSourcedEntity(com.thinkmorestupidless.ankka.core.EntityId(unpaidAt))
      .call(io.eitheror.casino.provider.core.CallbackEntity.end).invoke(Callback.Exhausted): Unit
    val (d, pd) = fresh("demo")
    demo = d
    assert(kit.launch(demo, pd, "test-slot", true).json.has("game_url"))
    kit.eventually() {
      Option(Sweep.views.get.forView(io.eitheror.casino.provider.core.RunCallbacks)
        .ask(io.eitheror.casino.provider.core.RunCallbacks.byRun, 100, "run" -> unpaid, "after_at" -> "-1", "after_id" -> ""))
        .filter(_.exists(r => r.callbackId == unpaidAt && r.outcome == Callback.Exhausted))
    }: Unit
    kit.eventually() {
      Option(Sweep.views.get.forView(io.eitheror.casino.provider.edge.views.SessionsByBrand)
        .ask(io.eitheror.casino.provider.edge.views.SessionsByBrand.byBrand, 1000, "brand" -> "goldvibe", "after_at" -> "-1", "after_id" -> ""))
        .filter(_.exists(_.launchToken == demo))
    }: Unit
    kit.offset += (Sweep.retention + 1.day).toMillis
  }

  When("the sweep runs") { () =>
    // As the timer runs it (both passes), until what it may discard is gone (the views catch up between runs).
    kit.eventually(60.seconds) {
      val cutoff = Wiring.now() - Sweep.retention.toMillis
      Sweep.run(kit.testKit.componentClient, Sweep.views.get, cutoff): Unit
      Sweep.alsoSweep(kit.testKit.componentClient, Sweep.views.get, cutoff): Unit
      Option.when((old.isEmpty || gone(old)) && (demo.isEmpty || gone(demo)))(())
    }: Unit
  }

  Then("the exhausted credit and its session are kept until an operator abandons it") { () =>
    assert(!gone(unpaid))
    assertEquals(kit.callback(unpaidAt).outcome, Callback.Exhausted)
    // ... and an operator can abandon it, which records who and why (review).
    val closed = kit.asStaff("POST", s"/v1/provider/brands/goldvibe/abandon/$unpaidAt", writer, Some("""{"reason":"paid by hand"}"""))
    assertEquals(closed.status, 200, closed.body)
    assertEquals(kit.callback(unpaidAt).abandoned.map(_.by), Some(operator))
  }

  Then("the session in fun mode is gone") { () =>
    assert(gone(demo))
    old = ""; demo = ""
  }

  Then("the round, its session and its provider calls are gone") { () =>
    assert(gone(old))
    val callbacks = kit.eventually() {
      Option(Sweep.views.get.forView(io.eitheror.casino.provider.core.RunCallbacks)
        .ask(io.eitheror.casino.provider.core.RunCallbacks.byRun, 100, "run" -> old, "after_at" -> "-1", "after_id" -> "")).filter(_.isEmpty)
    }
    assertEquals(callbacks, Vector.empty)
  }

  Then("a younger round is untouched") { () =>
    assert(kit.session(young).round("r-young").isDefined)
  }
