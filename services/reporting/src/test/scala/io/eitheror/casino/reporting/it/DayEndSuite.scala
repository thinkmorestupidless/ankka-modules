package io.eitheror.casino.reporting.it

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import io.eitheror.casino.reporting.day.CloseDays
import io.eitheror.casino.reporting.documents.Document

import java.time.Instant
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

import DayEnded.{fields, list}

/**
 * The day-end against the whole service (US6; FR-018, FR-019, SC-008): a day of facts in its own currency closed by
 * `close-days` once its end and the grace have passed (the instant attribution closes `ggr-daily` on), the providers', the
 * games' and the wallet's sections, the differences, the liability and pending holds at the close; the same bytes on every
 * read; a late fact a correction on its day and in the opening of the day-end of the day it arrived; and the export.
 */
class DayEndSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 30.minutes
  private lazy val kit = SharedKit.kit

  private var d: DayEnded    = null
  private var first: JsonNode = null
  private var text: String   = null

  override def beforeAll(): Unit =
    d = DayEnded(kit, kit.days()).usual().publish().settle()
    first = d.close()
    text = DayEnded.read(kit, d.day).text

  private def currency(j: JsonNode) = j.path("currencies").path(d.currency)

  test("the day closes at its end in the zone plus the grace, with the zone it closed in") {
    assertEquals((first.path("day").asText, first.path("zone").asText), (d.day.toString, kit.zone.getId))
    val closedAt = Instant.parse(first.path("closedAt").asText)
    assert(!closedAt.isBefore(d.end.plus(kit.settings.closeGrace)), closedAt)
    assertEquals(first.path("reference").asText.length, 36)
  }

  test("each currency holds the providers' deposits and the gateways' withdrawals with counts and values") {
    val c = currency(first)
    assertEquals(list(c.path("deposits")).map(n => (n.path("provider").asText, n.path("count").asLong, n.path("amount").asText)), d.deposits)
    assertEquals(list(c.path("withdrawals")).map(n => (n.path("gateway").asText, n.path("count").asLong, n.path("amount").asText)),
      d.withdrawals)
  }

  test("GGR per game provider and game from the rounds, beside the wallet's; the wallet's GGR, bonus cost, chargebacks, liability, holds") {
    val c = currency(first)
    assertEquals(list(c.path("games")).map(n => (n.path("provider").asText, n.path("gameId").asText, n.path("gameName").asText,
      n.path("providerGgr").asText, n.path("walletGgr").asText)), d.games)
    assertEquals(d.totals.keySet.map(k => k -> c.path(k).asText).toMap, d.totals)
  }

  test("the day-end's wallet figures equal the revenue report for the day") {
    val r = kit.get(s"/reports/ggr?from=${d.day}&to=${d.day}&currency=${d.currency}", kit.asFinance).json.path("summary")
    val c = currency(first)
    assertEquals((c.path("walletGgr").asText, c.path("depositPoolGgr").asText, c.path("bonusCost").asText, c.path("chargebacks").asText),
      (r.path("ggr").asText, r.path("depositPoolGgr").asText, r.path("bonusCosts").asText, r.path("chargebacks").asText))
  }

  test("every round whose figures differ, or that one side lacks, is listed with both figures") {
    val mine = list(first.path("differences")).map(fields).filter(_("roundId").startsWith(s"de-${d.tag}"))
    assertEquals(mine, d.differences)
  }

  test("a closed day-end reads byte for byte the same on every read (SC-008)") {
    for _ <- 1 to 3 do assertEquals(DayEnded.read(kit, d.day).text, text)
  }

  test("a day not closed is 409 day_open; a day that is not a date 400 invalid_date") {
    val open = DayEnded.read(kit, kit.today.plusDays(1))
    assertEquals((open.status, open.error), (409, "day_open"), open.text)
    val bad = kit.get("/day-end/yesterday", DayEnded.finance(kit))
    assertEquals((bad.status, bad.error, bad.json.path("field").asText), (400, "invalid_date", "day"), bad.text)
  }

  test("finance and the Reports tab's roles read it; another operator is refused") {
    assertEquals(kit.get(s"/day-end/${d.day}", kit.asFinance).status, 200)
    assertEquals(kit.get(s"/day-end/${d.day}", kit.asOperator).status, 403)
    assertEquals(kit.get(s"/day-end/${d.day}", kit.asStaff(Seq("brand_compliance"))).status, 403)
  }

  test("the export holds every section with the reference and the close time, a day-end-export document") {
    val r = kit.get(s"/day-end/${d.day}/export", DayEnded.finance(kit))
    assertEquals(r.status, 200, r.text)
    val ref = r.header("Content-Disposition").get.stripPrefix("attachment; filename=\"day-end-report-").stripSuffix(".csv\"")
    assertEquals(ref.length, 36, r.header("Content-Disposition"))
    val rows = r.text.split("\r\n").toVector.map(_.split(",", -1).toVector)
    assertEquals(rows.head, Vector("section", "currency", "item", "field", "value"))
    def value(section: String, currency: String, item: String, field: String) =
      rows.collectFirst { case Vector(`section`, `currency`, `item`, `field`, v) => v }
    assertEquals(value("day-end", "", "", "reference"), Some(ref))
    assertEquals(value("day-end", "", "", "closedAt"), Some(first.path("closedAt").asText))
    assertEquals(value("day-end", "", "", "dayEnd"), Some(first.path("reference").asText))
    for (p, n, amount) <- d.deposits do
      assertEquals((value("deposits", d.currency, p, "count"), value("deposits", d.currency, p, "amount")), (Some(n.toString), Some(amount)))
    for (k, v) <- d.totals do assertEquals(value("totals", d.currency, "", k), Some(v), k)
    for (prov, game, _, pg, wg) <- d.games do
      assertEquals((value("games", d.currency, s"$prov/$game", "providerGgr"), value("games", d.currency, s"$prov/$game", "walletGgr")),
        (Some(pg), Some(wg)))
    for diff <- d.differences do assertEquals(value("differences", "", diff("roundId"), "status"), Some(diff("status")))
    val record = kit.components.forEventSourcedEntity(EntityId(ref)).call(Document.get).invoke()
    assertEquals((record.kind, record.from, record.to, record.askedBy, record.status), (Document.DayEndExport, Some(d.day.toString),
      Some(d.day.toString), "fin-1", Document.Ready))
    assertEquals(kit.get(s"/documents/$ref", DayEnded.finance(kit)).status, 200)
    val refused = kit.get(s"/documents/$ref", kit.asOperator)
    // Review nit: the refusal names every role that admits, not the first alone.
    assertEquals((refused.status, refused.json.path("message").asText),
      (403, "the brand_admin, resource_admin, platform_admin or brand_finance permission is needed"))
  }

  test("a late fact leaves the day-end as it was, is a correction dated when it arrived, and opens the next day-end (FR-019)") {
    // A today of its own; the late stake arrives during it.
    val (today, arrived) = d.arriveLate(_.bet(d.player, d.currency, d.round(5), 1_25, 1_25, d.set.at(d.day, "20:00")))
    def late(c: JsonNode) = c.path("figures").path("bets").asText == "1.25"
    val after = d.corrected(late)
    // Everything fixed is as it was: only the corrections grew.
    assertEquals(DayEnded.fixed(after), DayEnded.fixed(first))
    val c = list(after.path("corrections")).find(late).get
    assertEquals((c.path("arrivedAt").asText, c.path("day").asText, c.path("figures").path("currency").asText),
      (arrived.toString, d.day.toString, d.currency))
    // The revenue report counts it on its own day.
    kit.eventually("the report with the late stake", 60.seconds)(Some(kit.get(s"/reports/ggr?from=${d.day}&to=${d.day}&currency=${d.currency}",
      kit.asFinance).json.path("summary").path("totalBets").asText).filter(_ == d.money(18_00 + 1_25)))
    // Today is not closed before its end and the grace, and opens with the correction once it is.
    val next = DayEnded.closeAtGrace(kit, today)
    val opening = list(next.path("openingCorrections")).map(n => (n.path("day").asText, n.path("arrivedAt").asText,
      n.path("figures").path("bets").asText))
    assert(opening.contains((d.day.toString, arrived.toString, "1.25")), opening.toString)
  }

  test("a rebuild into a new generation closes the day only once its consumers have read past it: the same day-end, no replayed corrections (R7, SC-004)") {
    // Yesterday of a today of its own: the latest day any fact is dated on, so the new generation's consumers read past it
    // only with these facts, and a run of close-days once the day is open in the replay finds a consumer behind it (players,
    // whose latest is a registration the day before) and leaves it open.
    val today = kit.freshToday()
    val r     = DayEnded(kit, today.minusDays(1)).usual().publish().settle()
    val before = r.close()
    val from   = kit.settings.generation
    assertEquals(kit.rebuild(), from + 1)
    // The day is open in the new generation once a fact of it is folded: from then, a close without the wait fixes it.
    kit.eventually("the day in the new generation", 60.seconds)(
      io.eitheror.casino.reporting.day.DaysView.row(kit.views, kit.settings.generation, r.day))
    val runs = kit.probe.fired("close-days").size
    kit.moveClock(CloseDays.Every + 1.minute)
    kit.eventually("a run of close-days in the new generation")(Option.when(kit.probe.fired("close-days").size > runs)(())): Unit
    Thread.sleep(500)
    DayEnded.assertOpen(kit, r.day)
    r.settle()
    // The close reads the views as they stand (the liability is the open records' balance at the close), so it is made
    // once the replay has reached what the old generation's close read: every currency, each one's liability and pending
    // holds, and the rounds' differences. (The kit's clock is moved past CLOSE_IDLE at once, where a brand's consumers
    // would be idle only once they are. The replay is every suite's facts in a full run: minutes, at its rate.)
    val currencies = before.path("currencies").fieldNames.asScala.toVector.sorted
    def held(c: String, field: String) = before.path("currencies").path(c).path(field).asText
    var seen = ""
    try
      kit.eventually("the new generation's close inputs as the old one's", DayEnded.Replay) {
        val in = io.eitheror.casino.reporting.day.DayEndInputs.gather(kit.views, kit.settings, r.day.toString)
        def shown(m: Map[String, Long], c: String) = io.eitheror.casino.reporting.render.Money(m.getOrElse(c, 0L), c)
        val held2 = io.eitheror.casino.reporting.day.DaysView.currencies(kit.views, kit.settings.generation)
        seen = s"currencies $held2 (want $currencies); " + currencies.map(c =>
          s"$c liability ${shown(in.liability, c)} (want ${held(c, "liability")}), holds ${shown(in.pendingHolds, c)} (want ${held(c, "pendingHolds")})")
          .mkString("; ") + s"; differences ${in.differences.size} (want ${before.path("differences").size})"
        Option.when(
          held2 == currencies &&
            currencies.forall(c => shown(in.liability, c) == held(c, "liability") && shown(in.pendingHolds, c) == held(c, "pendingHolds")) &&
            in.differences.size == before.path("differences").size)(())
      }
    catch case e: AssertionError => fail(s"${e.getMessage}: $seen")
    val after = DayEnded.closeDue(kit, r.day)
    def fixedPart(j: JsonNode) = DayEnded.fixed(j).deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]()
      .without[JsonNode](java.util.List.of("closedAt", "reference"))
    assertEquals(fixedPart(after), fixedPart(before))
    // Nothing replayed was folded into the day after it closed. (The kit's topics hold facts out of date order, since
    // suites publish on days of their own, so an older day of the new generation may close mid-replay by the rule; a
    // brand's topics are in the order its facts happened.)
    assertEquals((DayEnded.list(after.path("corrections")), DayEnded.list(after.path("openingCorrections"))), (Vector.empty, Vector.empty))
  }

