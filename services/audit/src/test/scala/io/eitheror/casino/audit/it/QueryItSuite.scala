package io.eitheror.casino.audit.it

import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord}

import java.time.Instant
import scala.concurrent.duration.*

/** `GET /v1/audit-records` (FR-013, FR-015, SC-005): each role's scope, the filters, the cursor, `retainedFrom` and its records. */
class QueryItSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 180.seconds
  private var kit: Kit = null

  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()
  override def beforeEach(context: BeforeEach): Unit = kit.rewire()

  /** Publishes records under one correlation and waits until the store holds them all (asking the route would record each ask). */
  private def loaded(records: (String, AuditRecord)*): String =
    val correlation = records.head._2.correlationId
    records.foreach((project, r) => kit.publish(project, r))
    val digests = Set("goldvibe", "spinvibe", "payments").map(b =>
      io.eitheror.casino.audit.ids.Ids.digest(io.eitheror.casino.audit.registry.RegistryKey.required(kit.secrets), b, correlation))
    kit.eventually(30.seconds)(Option.when(kit.records.count(r => digests(r.correlation)) >= records.size)(()))
    correlation

  private def at(minute: Int) = Instant.parse("2026-11-09T07:00:00Z").plusSeconds(60L * minute)

  test("a brand's officer sees that brand's records, oldest first, a page at a time, and none of another brand") {
    val corr = kit.fresh("c")
    val p    = kit.fresh("p")
    loaded(
      ("goldvibe", Kit.record(subjectId = p, correlationId = corr, occurredAt = at(3))),
      ("goldvibe", Kit.record(subjectId = p, correlationId = corr, occurredAt = at(1))),
      ("goldvibe", Kit.record(subjectId = p, correlationId = corr, occurredAt = at(2))),
      ("spinvibe", Kit.record(brand = "spinvibe", subjectId = p, correlationId = corr, occurredAt = at(1)))
    )
    val first = kit.query(kit.asBrandCompliance("goldvibe"), "brand" -> "goldvibe", "correlation" -> corr, "limit" -> "2")
    assertEquals(first.status, 200, first.text)
    assertEquals(first.records.map(_.get("occurredAt").asText), Vector(at(1), at(2)).map(_.toString))
    val next = first.json.get("next").asText
    val second = kit.query(kit.asBrandCompliance("goldvibe"), "brand" -> "goldvibe", "correlation" -> corr, "after" -> next)
    assertEquals(second.records.map(_.get("occurredAt").asText), Vector(at(3).toString))
    assert(second.json.get("next").isNull)
    assert((first.records ++ second.records).forall(_.get("brand").asText == "goldvibe"))
    val subjects = (first.records ++ second.records).map(_.get("subject").asText).toSet
    assertEquals(subjects.size, 1)
    val bySubject = kit.query(kit.asBrandCompliance("goldvibe"), "brand" -> "goldvibe", "subject" -> subjects.head)
    assertEquals(bySubject.records.size, 3)
  }

  test("a brand role naming another brand, or none, is refused") {
    val other = kit.query(kit.asBrandCompliance("goldvibe"), "brand" -> "spinvibe")
    assertEquals((other.status, other.code), (403, "brand_not_in_roles"))
    val none = kit.query(kit.asBrandCompliance("goldvibe"))
    assertEquals((none.status, none.code), (403, "brand_not_in_roles"))
    assertEquals(kit.query(kit.as("someone", "brand_goldvibe")).status, 403)
  }

  test("the installation's officer queries across brands by action and amount; each record names its brand") {
    val corr = kit.fresh("c")
    def adjust(brand: String, amount: Long) = Kit.record(brand = brand, action = "adjust", category = "money", correlationId = corr,
      changes = Vector(AuditChange("amount", None, Some(amount.toString)), AuditChange("currency", None, Some("EUR"))))
    loaded(("goldvibe", adjust("goldvibe", 150_000)), ("spinvibe", adjust("spinvibe", 250_000)), ("goldvibe", adjust("goldvibe", 50_000)))
    val r = kit.query(kit.asInstallationCompliance(), "action" -> "adjust", "amountMin" -> "100000", "correlation" -> corr)
    assertEquals(r.status, 200, r.text)
    assertEquals(r.records.map(x => (x.get("brand").asText, x.get("amount").asLong)).toSet, Set(("goldvibe", 150_000L), ("spinvibe", 250_000L)))
  }

  test("every filter narrows the answer") {
    val corr = kit.fresh("c")
    val base = Kit.record(correlationId = corr, occurredAt = at(10))
    loaded(
      ("goldvibe", base),
      ("goldvibe", base.copy(recordId = kit.fresh("r"), service = "players", action = "suspend", outcome = "refused",
        reasonCode = Some("fraud"), recordClass = "operator", occurredAt = at(20))),
      ("goldvibe", base.copy(recordId = kit.fresh("r"), changes = Vector(AuditChange("amount", None, Some("700")),
        AuditChange("currency", None, Some("GBP"))), recordClass = "money", occurredAt = at(30)))
    )
    def count(params: (String, String)*) =
      val r = kit.query(kit.asInstallationCompliance(), (("correlation" -> corr) +: params)*)
      assertEquals(r.status, 200, r.text)
      r.records.size
    assertEquals(count(), 3)
    assertEquals(count("service" -> "players"), 1)
    assertEquals(count("action" -> "suspend"), 1)
    assertEquals(count("outcome" -> "refused"), 1)
    assertEquals(count("reasonCode" -> "fraud"), 1)
    assertEquals(count("category" -> "money"), 1)
    assertEquals(count("currency" -> "GBP"), 1)
    assertEquals(count("amountMax" -> "699"), 0)
    assertEquals(count("visibility" -> "brand"), 3)
    assertEquals(count("brand" -> "spinvibe"), 0)
    assertEquals(count("from" -> at(15).toString, "to" -> at(25).toString), 1)
    assertEquals(count("from" -> at(20).toString), 2)
    val actor = kit.query(kit.asInstallationCompliance(), "correlation" -> corr).records.head.get("actor").asText
    assertEquals(count("actor" -> actor), 3)
  }

  test("a bad filter or a cursor not given here is refused") {
    assertEquals(kit.query(kit.asInstallationCompliance(), "amountMin" -> "ten").code, "bad_filter")
    assertEquals(kit.query(kit.asInstallationCompliance(), "colour" -> "red").code, "bad_filter")
    assertEquals(kit.query(kit.asInstallationCompliance(), "after" -> "not-a-cursor").code, "bad_cursor")
    assertEquals(kit.query(kit.asInstallationCompliance(), "limit" -> "5000").status, 400)
  }

  test("a period older than the retention answers what is retained and says from when") {
    val r = kit.query(kit.asBrandCompliance("goldvibe"), "brand" -> "goldvibe", "from" -> "2001-01-01T00:00:00Z")
    assertEquals(r.status, 200, r.text)
    assertEquals(Instant.parse(r.json.get("retainedFrom").asText), Instant.parse("2019-11-09T08:00:00Z"))
    assert(kit.query(kit.asBrandCompliance("goldvibe"), "brand" -> "goldvibe").json.get("retainedFrom") == null)
  }

  test("each query is a record naming the actor by pseudonym, the filters, range and count; one asking after the audit alone is not") {
    val corr   = kit.fresh("c")
    val before = kit.own("audit.query").size
    loaded(("goldvibe", Kit.record(correlationId = corr)))
    val beforeAsked = kit.own("audit.query").map(_.key).toSet
    val asked = kit.query(kit.asBrandCompliance("goldvibe", "officer-7"), "brand" -> "goldvibe", "correlation" -> corr, "from" -> "2026-11-01T00:00:00Z")
    assertEquals(asked.status, 200, asked.text)
    // The kit's clock stands still, so the new record is told by its key, not its place.
    // An earlier test's query record may reach the view late, so this one is told by its range too.
    val made = kit.eventually()(kit.own("audit.query").find(r => !beforeAsked(r.key) && r.brand == "goldvibe" &&
      r.changes.exists(c => c.field == "range" && c.after.contains("2026-11-01T00:00:00Z.."))))
    assertNotEquals(made.actor, "officer-7")
    assertEquals(made.visibility, "installation")
    val fields = made.changes.map(c => c.field -> c.after.getOrElse("")).toMap
    assert(fields("filters").contains("brand=goldvibe") && fields("filters").contains("correlation"), fields.toString)
    assertEquals(fields("range"), "2026-11-01T00:00:00Z..")
    assertEquals(fields("count"), "1")
    // Installation records about the brand are the brand's officer's to read too (FR-020), and only the brand's.
    val own = kit.query(kit.asBrandCompliance("goldvibe"), "brand" -> "goldvibe", "service" -> "audit").records
    assert(own.exists(_.get("key").asText == made.key) && own.forall(_.get("brand").asText == "goldvibe"), own.toString)
    val recorded = kit.own("audit.query").size
    kit.query(kit.asInstallationCompliance(), "service" -> "audit")
    // Nothing to wait on for a record that must not appear: a later query's record shows the view has
    // caught up past it.
    kit.query(kit.asInstallationCompliance(), "brand" -> "spinvibe")
    kit.eventually()(Option.when(kit.own("audit.query").size > recorded)(()))
    assertEquals(kit.own("audit.query").size, recorded + 1)
    assert(before <= beforeAsked.size)
  }
