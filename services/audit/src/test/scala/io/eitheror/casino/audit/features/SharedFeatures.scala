package io.eitheror.casino.audit.features

import com.thinkmorestupidless.ankka.http.Principal
import io.eitheror.casino.audit.ingest.SourceState
import io.eitheror.casino.audit.it.Kit
import io.eitheror.casino.audit.query.{Filters, Query}
import io.eitheror.casino.audit.store.Records
import io.eitheror.casino.audit.stored.{Source, Stored}
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord}

import java.util.UUID

/** features/audit/shared.feature (US2). The brand is goldvibe; the second brand spinvibe. */
class SharedFeatures extends AuditFeatures("shared.feature"):
  private var records: Vector[(Source, AuditRecord)] = Vector.empty
  private var before: Option[Stored]                 = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    records = Vector.empty; before = None

  private def publish(source: Source, r: AuditRecord): AuditRecord =
    val project = source match
      case Source.Brand(slug) => slug
      case _                  => "payments"
    kit.publish(project, r)
    records :+= source -> r
    r

  private val gold = Source.Brand("goldvibe")
  private val spin = Source.Brand("spinvibe")

  private def isPseudonym(s: String) = scala.util.Try(UUID.fromString(s)).isSuccess

  Given("a brand that allows the shared audit to read its audit records") { () => () }

  When("an operator's action is recorded in the brand") { () =>
    publish(gold, Kit.record(id = id("record"), action = "players.suspend", actorId = id("operator"), subjectId = id("player"),
      reasonCode = Some("fraud"), changes = Vector(AuditChange("status", Some("active"), Some("suspended"))))): Unit
  }

  Then("it appears in the shared audit's store within the ingest bound") { () => stored(gold, records.head._2): Unit }

  Then("the id of every person it names is replaced by a pseudonym") { () =>
    val s = stored(gold, records.head._2)
    assert(isPseudonym(s.actor) && isPseudonym(s.subject), s)
    assert(s.actor != records.head._2.actorId && s.subject != records.head._2.subjectId)
  }

  Then("every other field is unchanged") { () =>
    val (r, s) = (records.head._2, stored(gold, records.head._2))
    assertEquals((s.brand, s.service, s.action, s.actorKind, s.subjectKind, s.outcome, s.reasonCode, s.category, s.occurredAt),
      (r.brand, r.service, r.action, r.actorKind, r.subjectKind, r.outcome, r.reasonCode, r.recordClass, r.occurredAt))
    assertEquals(s.changes.map(c => (c.field, c.before, c.after)), r.changes.map(c => (c.field, c.before, c.after)))
  }

  When("an operator's action on a market is recorded in the brand") { () =>
    publish(gold, Kit.record(id = id("record"), service = "markets", action = "markets.suspend", actorId = id("operator"),
      subjectKind = "market", subjectId = id("market"))): Unit
  }

  Then("the shared audit's record names the market by its id as written") { () =>
    assertEquals(stored(gold, records.head._2).subject, id("market"))
  }

  Then("names the operator by a pseudonym") { () => assert(isPseudonym(stored(gold, records.head._2).actor)) }

  When("a bet is placed and an operator adjusts the player's real balance with a reason code") { () =>
    publish(gold, Kit.record(id = id("bet"), service = "wallet", action = "wallet.bet", actorKind = "service", actorId = "wallet",
      subjectId = id("player"), category = "money")): Unit
    publish(gold, Kit.record(id = id("adjustment"), service = "wallet", action = "wallet.adjust", actorId = id("operator"),
      subjectId = id("player"), category = "money", reasonCode = Some("goodwill"),
      changes = Vector(AuditChange("amount", None, Some("2500")), AuditChange("currency", None, Some("EUR"))))): Unit
  }

  Then("the bet's record is not in the shared audit's store") { () =>
    stored(gold, records(1)._2): Unit
    assert(absent(gold, records.head._2))
  }

  Then("the adjustment's record is, with its amount") { () =>
    val s = stored(gold, records(1)._2)
    assertEquals((s.amount, s.currency, s.reasonCode), (Some(2500L), Some("EUR"), Some("goodwill")))
  }

  Given("a player with records at one brand") { () =>
    (1 to 3).foreach(i => publish(gold, Kit.record(id = id(s"record-$i"), actorId = id("operator"), subjectId = id("player"))))
  }

  When("their records are read from the shared audit") { () => records.foreach((s, r) => stored(s, r)) }

  Then("every record names the same pseudonym for the player") { () =>
    assertEquals(records.map((s, r) => stored(s, r).subject).toSet.size, 1)
  }

  Given("the same person registered at two brands") { () =>
    publish(gold, Kit.record(brand = "goldvibe", id = id("gold"), subjectId = id("person"), correlationId = id("corr"))): Unit
    publish(spin, Kit.record(brand = "spinvibe", id = id("spin"), subjectId = id("person"), correlationId = id("corr"))): Unit
  }

  When("their records at each brand are read from the shared audit") { () => records.foreach((s, r) => stored(s, r)) }

  Then("the pseudonyms differ") { () =>
    assertNotEquals(stored(gold, records.head._2).subject, stored(spin, records(1)._2).subject)
  }

  Then("nothing in the store joins them") { () =>
    val (g, s) = (stored(gold, records.head._2), stored(spin, records(1)._2))
    // The operator is the same kit default at both brands, so it is left out: what must not join is the person.
    val linking = Vector(g.subject, g.correlation, g.causation, g.recordDigest).filter(_.nonEmpty).toSet
    val other   = Vector(s.subject, s.correlation, s.causation, s.recordDigest).filter(_.nonEmpty).toSet
    assertEquals(linking intersect other, Set.empty[String])
  }

  Given("a record already in the shared audit's store") { () =>
    before = Some(stored(gold, publish(gold, Kit.record(id = id("record"), actorId = id("operator")))))
  }

  When("the same record arrives again") { () =>
    kit.publish("goldvibe", records.head._2)
    stored(gold, publish(gold, Kit.record(id = id("marker")))): Unit
  }

  Then("the store is unchanged") { () =>
    assertEquals(kit.views.forView(Records).get(before.get.key), before)
  }

  Given("everything the shared audit answers, writes or logs") { () =>
    publish(gold, Kit.record(id = s"wallet/${id("player")}/EUR/k", actorId = id("operator"), subjectId = id("player"),
      approverId = Some(id("approver")), correlationId = s"c/${id("player")}")): Unit
    publish(gold, Kit.record(id = s"programme/${id("affiliate")}", actorKind = "affiliate", actorId = id("affiliate"),
      subjectKind = "market", subjectId = "m-1")): Unit
    records.foreach((s, r) => stored(s, r))
  }

  private var hits: Vector[String] = Vector.empty

  When("it is searched for any real player, operator or affiliate id") { () =>
    val realIds = Vector(id("player"), id("operator"), id("approver"), id("affiliate"))
    val db      = kit.database()
    hits = for
      real         <- realIds
      (table, row) <- db
      if row.contains(real) && !row.contains("audit.identity")
    yield s"$real in $table"
    hits ++= kit.logLines.filter(l => realIds.exists(l.contains))
  }

  Then("none is found") { () => assertEquals(hits, Vector.empty) }

  Then("only the registry holds them") { () =>
    val registry = kit.database().map(_._2).filter(_.contains("audit.identity")).mkString("\n")
    Vector(id("player"), id("operator"), id("approver"), id("affiliate")).foreach(r => assert(registry.contains(r), r))
  }

  private var lastBefore: Option[java.time.Instant] = None

  Given("a brand that revoked what it allowed the shared audit") { () =>
    stored(spin, publish(spin, Kit.record(brand = "spinvibe", id = id("last")))): Unit
    lastBefore = kit.components.forKeyValueEntity(SourceState.entityId(spin.name)).call(SourceState.get).invoke().lastReceived
    kit.revoked = Set("spinvibe")
  }

  When("records are written in the brand") { () =>
    publish(spin, Kit.record(brand = "spinvibe", id = id("unseen"))): Unit
    stored(gold, publish(gold, Kit.record(id = id("marker")))): Unit
  }

  Then("the shared audit's store stops at the last record received") { () =>
    assert(absent(spin, records(1)._2))
    stored(spin, records.head._2): Unit
  }

  Then("the brand's status in the shared audit shows the gap and since when") { () =>
    val r = kit.request("GET", "/v1/audit-status?brand=spinvibe", headers = kit.asBrandCompliance("spinvibe"))
    assertEquals(r.status, 200, r.text)
    val source = r.json.get("brands").get(0).get("sources").get(0)
    assertEquals(source.get("source").asText, spin.name)
    assertEquals(Some(java.time.Instant.parse(source.get("lastReceived").asText)), lastBefore)
    assert(source.get("lagSeconds").asLong >= 0)
  }

  Given("payments allows the shared audit to read its audit records") { () => () }

  When("payments records one action naming brand {string} and one naming no brand") { (brand: String) =>
    publish(Source.Payments, Kit.record(brand = brand, id = id("named"), service = "merchant", subjectKind = "merchant", subjectId = id("m"))): Unit
    publish(Source.Payments, Kit.record(brand = "payments", id = id("unnamed"), service = "merchant", subjectKind = "merchant",
      subjectId = id("m"))): Unit
    records.foreach((s, r) => stored(s, r))
  }

  // What each officer's scope finds in the store; bound to the records route once it is served (T040).
  private def sees(roles: Set[String]): Set[String] =
    val scope = Routes.auditScope(Principal("o", None, None, false, roles, Map.empty, Some(Routes.StaffIssuer)))
    val brands = scope match
      case Routes.BrandSet.Every       => None
      case Routes.BrandSet.Only(slugs) => Some(slugs)
    kit.views.forView(Records).ordered(Query.condition(Filters(brands = brands)), Query.Oldest, 100000)
      .map(_.key).toSet intersect records.map((s, r) => keyOf(s, r)).toSet

  Then("a compliance officer for brand {string} sees the first and not the second") { (brand: String) =>
    assertEquals(sees(Set("brand_compliance", s"brand_$brand")), Set(keyOf(Source.Payments, records.head._2)))
  }

  Then("the installation's compliance officer sees both") { () =>
    assertEquals(sees(Set("audit_compliance")), records.map((s, r) => keyOf(s, r)).toSet)
  }
