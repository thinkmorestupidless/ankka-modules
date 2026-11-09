package io.eitheror.casino.audit.features

import io.eitheror.casino.audit.it.Kit
import io.eitheror.casino.audit.stored.{Source, Stored, Visibility}
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord, ModuleChange}

import java.time.Instant

/**
 * features/audit/installation.feature (US5): deployments from the brand's `platform.modules`, erasure
 * states from its `platform.audit`. Rollbacks, switches to a restored database and every change of what
 * a brand allows a shared part need ankka to publish a project's events (research R7).
 */
class InstallationFeatures extends AuditFeatures("installation.feature"):
  override protected def ranOutside: Map[String, String] = InstallationFeatures.Outside

  private var brand                      = "goldvibe"
  private var part                       = ""
  private var change: Option[ModuleChange] = None
  private var erasure: Option[(Source, AuditRecord)] = None
  private var officer: Seq[(String, String)] = Nil

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    brand = "goldvibe"; part = ""; change = None; erasure = None; officer = Nil

  private val at = Instant.parse("2026-11-09T07:30:00Z")

  private def moduleRecord(c: ModuleChange): Stored =
    kit.eventually()(kit.records.find(r => r.source == Source.Modules(brand).name && r.service == c.module && r.action == s"module.${c.change}"))

  Given("brand {string}") { (b: String) => brand = b }

  Given("a part of the brand") { () => part = id("module") }

  When("it is deployed") { () =>
    val c = ModuleChange(part, "upgraded", Some("0.3.0"), Some("stable"), "vendor:agent", at)
    kit.publishModule(brand, c)
    change = Some(c)
  }

  Then("an installation record names the actor, the part, the version or restore point, and when") { () =>
    val c = change.get
    val r = moduleRecord(c)
    assertEquals((r.actorKind, r.actor, r.service, r.occurredAt, r.visibility), ("platform", "vendor:agent", part, at, Visibility.Installation))
    assertEquals(r.changes.find(_.field == "version").flatMap(_.after), Some("0.3.0"))
  }

  // The two rows of the deployment outline ankka cannot carry yet: skipped, saying why, while the deployment row runs.
  When("it is rolled back") { () => assume(false, InstallationFeatures.R7) }
  When("it is switched to a restored database") { () => assume(false, InstallationFeatures.R7) }

  Given("an erasure request for a player of the brand") { () => () }

  private def erasureAt(state: String): Unit =
    val r = Kit.record(brand = brand, id = id(s"erasure-$state"), service = "players", action = s"erasure.$state", actorKind = "system",
      actorId = "players", subjectId = id("player"), category = "erasure", correlationId = id("erasure"),
      changes = Vector(AuditChange("erasure", None, Some(state))), occurredAt = at)
    kit.publish(brand, r)
    erasure = Some(Source.Brand(brand) -> r)

  When("it is filed") { () => erasureAt("filed") }
  When("it is held") { () => erasureAt("held") }
  When("it is applied") { () => erasureAt("applied") }
  When("it is completed") { () => erasureAt("completed") }

  Then("a record names the subject's pseudonym, the state {string} and when") { (state: String) =>
    val (s, r) = erasure.get
    val kept = stored(s, r)
    assert(kept.subject.nonEmpty && kept.subject != id("player"))
    assertEquals((kept.action, kept.occurredAt, kept.visibility), (s"erasure.$state", at, Visibility.Installation))
    assertEquals(kept.changes.find(_.field == "erasure").flatMap(_.after), Some(state))
  }

  Then("the record survives the erasure") { () =>
    val (s, r) = erasure.get
    // The erasure completes in the brand; the shared audit's record of it stays, as stored.
    kit.publish(brand, Kit.record(brand = brand, id = id("completed"), service = "players", action = "erasure.completed",
      actorKind = "system", actorId = "players", subjectId = id("player"), category = "erasure", correlationId = id("erasure"),
      occurredAt = at.plusSeconds(60)))
    stored(s, Kit.record(brand = brand, id = id("completed"))): Unit
    assertEquals(stored(s, r).key, keyOf(s, r))
  }

  Given("a compliance officer with the role for the brand") { () => officer = kit.asBrandCompliance(brand, id("officer")) }

  When("they query their brand's records") { () =>
    val c = ModuleChange(id("module"), "enabled", Some("0.3.0"), Some("stable"), "vendor:agent", at.plusSeconds(1))
    kit.publishModule(brand, c)
    val own = Kit.record(brand = brand, id = id("own"), correlationId = id("corr"), occurredAt = at)
    kit.publish(brand, own)
    val later = Kit.record(brand = brand, id = id("later"), correlationId = id("corr"), occurredAt = at.plusSeconds(2))
    kit.publish(brand, later)
    stored(Source.Brand(brand), own): Unit
    stored(Source.Brand(brand), later): Unit
    moduleRecord(c): Unit
    part = c.module
  }

  Then("installation records are listed beside the brand's own records in one order of occurrence") { () =>
    val r = kit.query(officer, "brand" -> brand, "from" -> at.toString, "to" -> at.plusSeconds(3).toString, "limit" -> "1000")
    assertEquals(r.status, 200, r.text)
    val mine = r.records.filter(x => x.get("service").asText == part ||
      Set(keyOf(Source.Brand(brand), Kit.record(brand = brand, id = id("own"))), keyOf(Source.Brand(brand), Kit.record(brand = brand, id = id("later"))))(x.get("key").asText))
    assertEquals(mine.map(_.get("visibility").asText), Vector(Visibility.Brand, Visibility.Installation, Visibility.Brand))
  }

object InstallationFeatures:
  // Here, not in the class: GherkinSuite reads these while it is constructed.
  val R7 = "ankka publishes no project events (research R7)"
  private val Outside: Map[String, String] = Map(
    "what a brand allows a shared part, made, accepted, declined, withdrawn or revoked, is a record naming the principal, the target and the change" -> R7
  )
