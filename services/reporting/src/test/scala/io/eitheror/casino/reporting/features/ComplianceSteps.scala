package io.eitheror.casino.reporting.features

import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.reporting.documents.{Document, DocumentState}
import io.eitheror.casino.reporting.it.{DayEnded, Disputed, FactSet, Reply}

import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * features/reporting/compliance-exports.feature (US7; FR-020, FR-021, FR-024): the responsible gambling cases, limits and
 * self-exclusions, and the KYC decisions, of players of the scenario's own over a period of its own days, published as
 * `casino.players` facts; each row held to the facts it came from; the exports as `compliance-export` documents, kept
 * for the brand's lifetime and ended by an erasure.
 */
class ComplianceFeatures extends ReportingFeatures("compliance-exports.feature"):
  private var d: LocalDate      = null
  private var tag: String       = null
  private var want: Vector[Vector[String]] = Vector.empty
  private var reference: String = null
  private var exported: Reply   = null
  private var hours: Long       = 0
  private var named: String     = null
  private var route: String     = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    d = null; tag = null; want = Vector.empty; reference = null; exported = null; hours = 0; named = null; route = null

  Given("brand {string}") { (slug: String) =>
    assertEquals(slug, kit.brand)
  }

  private def compliance = kit.asStaff(Seq("brand_compliance"), "co-1")
  private def at(day: LocalDate, time: String): Instant = FactSet(kit.zone).at(day, time)
  private def player(n: Int) = s"co-$tag-p$n"
  private def mine(rows: Vector[Vector[String]]) = rows.filter(_.head.startsWith(s"co-$tag"))

  /** The period, its second and third days, of a block of the scenario's own. */
  private def fresh(): Unit =
    d = kit.days()
    tag = UUID.randomUUID().toString.take(8)

  private def from = d.plusDays(1)
  private def to   = d.plusDays(2)

  private def publish(facts: f.CasinoPlayersFact*): Unit = facts.foreach(kit.publish(f.CasinoPlayers, _))

  private def csv(route: String, as: Seq[(String, String)] = compliance): Reply =
    kit.get(s"$route?from=$from&to=$to&format=csv", as)

  private def rowsOf(r: Reply): Vector[Vector[String]] = r.text.split("\r\n").toVector.map(_.split(",", -1).toVector)

  private val Ref = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})".r
  private def referenceOf(r: Reply): String =
    r.header("Content-Disposition").flatMap(Ref.findFirstIn).getOrElse(throw AssertionError(s"${r.status} ${r.text.take(300)}"))

  /** The JSON answer's rows of the scenario's players, once the view holds `n` of them. */
  private def answered(route: String, n: Int): Vector[Vector[String]] =
    kit.eventually(s"$route's $n rows", 60.seconds) {
      val j = kit.get(s"$route?from=$from&to=$to", compliance).json
      val rows = j.path("rows").elements.asScala.toVector.map(_.properties.asScala.toVector.map(e =>
        if e.getValue.isNull then "" else e.getValue.asText))
      Some(mine(rows)).filter(_.size == n)
    }

  // ── RG cases ────────────────────────────────────────────────────────────────────────────────────

  private def cases(): Unit =
    fresh()
    route = "/compliance/rg-cases"
    val (p1, p2, p3) = (player(1), player(2), player(3))
    // Opened before the period and closed in it; opened in it and still open; a limit; a self-exclusion; one after it.
    val c1o = at(d, "10:00"); val c1c = at(from, "10:00"); val c2o = at(to, "09:00")
    val lim = at(from, "12:00"); val se = at(to, "15:00"); val until = at(to.plusDays(180), "15:00")
    publish(
      f.RgCaseOpened(p1, s"$tag-c1", "cooling_off", "player_request", Some(c1c), c1o),
      f.RgCaseClosed(p1, s"$tag-c1", "cooling_off", "expired", c1c),
      f.RgCaseOpened(p2, s"$tag-c2", "affordability", "deposit_threshold", None, c2o),
      f.RgLimitApplied(p1, "deposit", Some("daily"), Some(50_00), "player", lim, Some("EUR")),
      f.PlayerSelfExcluded(p3, "active", "self_excluded", "player", None, Some("player_request"), se, Some(until), se),
      f.RgCaseOpened(p3, s"$tag-c3", "cooling_off", "player_request", None, at(to.plusDays(1), "09:00")))
    want = Vector(
      Vector(p1, "cooling_off", c1o.toString, c1c.toString, "expired", "player_request"),
      Vector(p1, "deposit", lim.toString, "", "applied", "player"),
      Vector(p2, "affordability", c2o.toString, "", "", "deposit_threshold"),
      Vector(p3, "self_exclusion", se.toString, until.toString, "self_excluded", "player_request"))

  Given("cases opened and closed in a period") { () => cases() }

  When("compliance exports them") { () =>
    answered(route, want.size): Unit
    exported = csv(route)
  }

  Then("one row per case holds the player, type, opened, closed, outcome and reason, from the {string}, {string}, {string} and {string} facts") {
    (_: String, _: String, _: String, _: String) =>
      assertEquals(exported.status, 200, exported.text)
      val rows = rowsOf(exported)
      assertEquals(rows.head, Vector("playerId", "type", "openedAt", "closedAt", "outcome", "reason"))
      assertEquals(mine(rows.tail), want)
      assertEquals(answered("/compliance/rg-cases", want.size), want)
  }

  // ── KYC decisions ───────────────────────────────────────────────────────────────────────────────

  private def decisions(): Unit =
    fresh()
    route = "/compliance/kyc-decisions"
    val (p1, p2) = (player(1), player(2))
    val s1 = at(d, "09:00"); val r1 = at(from, "10:00"); val v1 = at(from, "10:05")
    val s2 = at(from, "11:00"); val r2 = at(to, "12:00"); val v2 = at(to, "12:05")
    publish(
      f.PlayerKycSubmitted(p1, s"$tag-d1", "identity", s1),
      f.PlayerKycDocumentReviewed(p1, s"$tag-d1", "identity", "approved", None, "op-1", r1),
      f.PlayerKycVerified(p1, "unverified", "verified", "operator", Some("op-1"), Some("documents_approved"), v1),
      f.PlayerKycSubmitted(p2, s"$tag-d2", "proof_of_address", s2),
      f.PlayerKycDocumentReviewed(p2, s"$tag-d2", "proof_of_address", "rejected", Some("illegible"), "op-1", r2),
      f.PlayerKycRejected(p2, "unverified", "rejected", "operator", Some("op-1"), Some("documents_rejected"), v2))
    want = Vector(
      Vector(p1, "identity", s1.toString, r1.toString, "approved", ""),
      Vector(p1, "status", s1.toString, v1.toString, "verified", "documents_approved"),
      Vector(p2, "proof_of_address", s2.toString, r2.toString, "rejected", "illegible"),
      Vector(p2, "status", s2.toString, v2.toString, "rejected", "documents_rejected"))

  Given("KYC decisions in a period") { () => decisions() }

  Then("one row per decision holds the player, submitted, decided, outcome and reason, from the {string}, {string} and {string} facts") {
    (_: String, _: String, _: String) =>
      assertEquals(exported.status, 200, exported.text)
      val rows = rowsOf(exported)
      assertEquals(rows.head, Vector("playerId", "category", "submittedAt", "decidedAt", "outcome", "reason"))
      assertEquals(mine(rows.tail), want)
      assertEquals(answered("/compliance/kyc-decisions", want.size), want)
  }

  // ── the document ────────────────────────────────────────────────────────────────────────────────

  private def record(ref: String): DocumentState = Disputed.document(kit, ref)

  /** A compliance export of cases, served; answers its reference once the documents view holds it. */
  private def generate(): String =
    exported = csv("/compliance/rg-cases")
    assertEquals(exported.status, 200, exported.text)
    val ref = referenceOf(exported)
    Disputed.inView(kit, ref)
    ref

  Given("a compliance export") { () => cases() }

  When("it is generated") { () =>
    answered("/compliance/rg-cases", want.size): Unit
    reference = generate()
  }

  Then("it carries a reference number, the period and the generation time") { () =>
    val r = record(reference)
    assertEquals((r.kind, r.from, r.to, r.askedBy), (Document.ComplianceExport, Some(from.toString), Some(to.toString), "co-1"))
    assert(r.generatedAt.isDefined, r.toString)
    assertEquals(r.parameters.get("report"), Some("rg-cases"))
  }

  Then("it is served as an attachment") { () =>
    assertEquals(exported.header("Content-Disposition"), Some(s"attachment; filename=\"rg-cases-report-$reference.csv\""))
    assertEquals(kit.get(s"/documents/$reference", compliance).status, 200)
    assertEquals(kit.get(s"/documents/$reference", kit.asStaff(Seq("brand_finance"))).status, 403)
  }

  /** Not served `hours` after it was ready, and then expired by its lifetime with its record kept and no file left. */
  private def endsAfter(ref: String, hours: Long, as: Seq[(String, String)]): Unit =
    val r = record(ref)
    assertEquals(java.time.Duration.between(r.readyAt.get, r.expiresAt.get).toHours, hours)
    kit.moveClock((hours.hours - 1.minute) - java.time.Duration.between(r.readyAt.get, kit.now).toMillis.millis)
    assertEquals(kit.get(s"/documents/$ref", as).status, 200, "still served before its lifetime ends")
    kit.moveClock(2.minutes)
    assertEquals(kit.get(s"/documents/$ref", as).error, "document_expired")
    // A sweep still catching up when the clock moved read it before the move and set its next run an hour on: the
    // clock is moved on to that run if it has to be (as `DayEnded.closeAtGrace` does for the close).
    scala.util.Try(kit.eventually(s"$ref expired by the sweep", 20.seconds)(Option.when(record(ref).status == Document.Expired)(())))
      .getOrElse(kit.moveClock(io.eitheror.casino.reporting.documents.Sweep.Every))
    val gone = kit.eventually(s"$ref expired", 60.seconds)(Some(record(ref)).filter(_.status == Document.Expired))
    assertEquals(gone.endedBy, Some(Document.Lifetime))
    assertEquals(kit.testKit.storage.default.getBytes(gone.objectName.get), None)

  Then("it is deleted after {string} hours with its record kept") { (h: String) =>
    endsAfter(reference, h.toLong, compliance)
  }

  // ── lifetimes ───────────────────────────────────────────────────────────────────────────────────

  private var kind: String = null
  private var before: io.eitheror.casino.reporting.settings.ReportingSettingsState = null

  private def kindOf(words: String) = words match
    case "compliance export" => Document.ComplianceExport
    case "day-end export"    => Document.DayEndExport

  Given("the brand's lifetime for compliance export is not set") { () =>
    kind = Document.ComplianceExport
    before = kit.reporting
    kit.setReporting(s => s.copy(lifetimeHours = s.lifetimeHours - kind))
    hours = 24
  }

  Given("the brand's lifetime for {word} {word} is {string} hours") { (a: String, b: String, h: String) =>
    kind = kindOf(s"$a $b")
    before = kit.reporting
    kit.setReporting(s => s.copy(lifetimeHours = s.lifetimeHours.updated(kind, h.toInt)))
    hours = h.toLong
  }

  When("a compliance export is generated") { () =>
    cases()
    answered("/compliance/rg-cases", want.size): Unit
    reference = generate()
  }

  When("a day-end export is generated") { () =>
    val day = DayEnded(kit, kit.days())
    day.set.deposit(day.player, day.currency, 1_00, day.set.at(day.day, "08:00"))
    day.publish()
    kit.eventually("the deposit folded", 60.seconds)(io.eitheror.casino.reporting.day.DaysView.row(kit.views, kit.settings.generation, day.day)
      .filter(_.currencies.get(day.currency).exists(_.total.money.deposits >= 1_00)))
    day.close(): Unit
    exported = kit.get(s"/day-end/${day.day}/export", DayEnded.finance(kit))
    assertEquals(exported.status, 200, exported.text)
    reference = referenceOf(exported)
    Disputed.inView(kit, reference)
  }

  Then("it is served until {int} hours after it is ready and deleted then, with its record kept") { (h: Int) =>
    try
      assertEquals(h.toLong, hours)
      val as = if kind == Document.DayEndExport then DayEnded.finance(kit) else compliance
      endsAfter(reference, h.toLong, as)
      assertEquals(record(reference).kind, kind)
    finally kit.setReporting(_ => before)
  }

  // ── a lifetime out of range ─────────────────────────────────────────────────────────────────────

  When("an operator sets the brand's lifetime for compliance exports to {string} hours") { (h: String) =>
    before = kit.reporting
    last = kit.request("PUT", kit.path("/settings"), Some(s"""{"lifetimeHours": {"${Document.ComplianceExport}": $h}}"""), kit.asFinance)
  }

  Then("the setting is refused naming its range, {string} to {string} hours") { (lo: String, hi: String) =>
    assertEquals((last.status, last.error, last.json.path("field").asText), (400, "invalid_setting",
      s"lifetimeHours.${Document.ComplianceExport}"), last.text)
    assert(last.json.path("message").asText.contains(s"$lo to $hi"), last.text)
    assertEquals(kit.reporting, before)
  }

  // ── erasure ─────────────────────────────────────────────────────────────────────────────────────

  Given("a compliance export kept for {string} hours that names a player") { (h: String) =>
    before = kit.reporting
    kit.setReporting(s => s.copy(lifetimeHours = s.lifetimeHours.updated(Document.ComplianceExport, h.toInt)))
    try
      cases()
      answered("/compliance/rg-cases", want.size): Unit
      reference = generate()
      named = player(3)
      assert(exported.text.contains(named), exported.text)
    finally kit.setReporting(_ => before)
  }

  When("the player is erased") { () =>
    val r = kit.request("POST", kit.path(s"/erasure/players/$named"), headers = kit.asService("players"))
    assertEquals(r.status, 200, r.text)
    assert(r.json.path("documents").asInt >= 1, r.text)
  }

  Then("the document is deleted") { () =>
    val r = record(reference)
    assertEquals((r.status, r.endedBy), (Document.Expired, Some(Document.Erasure)))
    assertEquals(kit.testKit.storage.default.getBytes(r.objectName.get), None)
    assertEquals(kit.get(s"/documents/$reference", compliance).error, "document_expired")
  }

  Then("its record, holding no personal field, remains") { () =>
    val r = record(reference)
    assertEquals((r.kind, r.askedBy, r.from, r.to), (Document.ComplianceExport, "co-1", Some(from.toString), Some(to.toString)))
    // The player by id at most: the record's parameters are what was asked, none of them a player.
    assert(r.parameters.values.forall(!_.contains(named)), r.toString)
  }
