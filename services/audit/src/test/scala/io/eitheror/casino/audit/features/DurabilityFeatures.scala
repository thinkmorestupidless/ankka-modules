package io.eitheror.casino.audit.features

import io.eitheror.casino.audit.api.StatusRoute
import io.eitheror.casino.audit.it.{Kit, Reply}
import io.eitheror.casino.audit.registry.{Identity, IdentityState}
import io.eitheror.casino.audit.store.Records
import io.eitheror.casino.audit.stored.{Source, Stored}
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord}

import java.time.Instant
import scala.concurrent.duration.*

/** features/audit/durability.feature (US6), through the routes and the store. */
class DurabilityFeatures extends AuditFeatures("durability.feature"):
  private val gold = Source.Brand("goldvibe")

  private var record: Option[(AuditRecord, Stored)] = None
  private var answers: Vector[Reply]                = Vector.empty
  private var identity: Option[IdentityState]       = None
  private var brand                                 = "goldvibe"
  private var answer: Reply                         = null
  private var restored: Option[Kit]                 = None
  private var afterRestore: Option[(AuditRecord, Stored)] = None

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    record = None; answers = Vector.empty; identity = None; brand = "goldvibe"; answer = null; afterRestore = None

  override def afterEach(context: AfterEach): Unit =
    restored.foreach(_.stop())
    restored = None
    kit.mirrors = Vector.empty
    kit.rewire()
    super.afterEach(context)

  private def keep(r: AuditRecord): Stored =
    kit.publish("goldvibe", r)
    stored(gold, r)

  // ── immutability ─────────────────────────────────────────────────────────────────────────────

  Given("a record in the shared audit's store") { () =>
    val r = Kit.record(id = id("record"))
    record = Some(r -> keep(r))
  }

  When("any role attempts to change or delete it") { () =>
    val key = record.get._2.key
    val roles = Vector(kit.asOwner(), kit.asInstallationCompliance(), kit.asBrandCompliance("goldvibe"), kit.asReidentifier("goldvibe"))
    answers = for as <- roles; method <- Vector("PUT", "PATCH", "DELETE") yield
      kit.request(method, s"/v1/audit-records/$key", Some("""{"outcome":"refused"}"""), as)
  }

  Then("there is no such action: no route, command or role can") { () =>
    assert(answers.forall(_.status >= 400), answers.map(_.status).toString)
    assertEquals(kit.views.forView(Records).get(record.get._2.key), Some(record.get._2))
  }

  // ── erasure ──────────────────────────────────────────────────────────────────────────────────

  Given("a player erased at a brand") { () =>
    val r = Kit.record(id = id("record"), subjectId = id("player"))
    val s = keep(r)
    record = Some(r -> s)
    identity = Some(kit.components.forKeyValueEntity(Identity.entityId(s.subject)).call(Identity.get).invoke())
    keep(Kit.record(id = id("erased"), service = "players", action = "erasure.completed", actorKind = "system", actorId = "players",
      subjectId = id("player"), category = "erasure", changes = Vector(AuditChange("erasure", None, Some("completed"))))): Unit
  }

  When("their records are read from the shared audit") { () =>
    answer = kit.query(kit.asInstallationCompliance(), "brand" -> "goldvibe", "subject" -> record.get._2.subject)
  }

  Then("every record is present and unchanged, naming the pseudonym") { () =>
    assertEquals(answer.status, 200, answer.text)
    val keys = answer.records.map(_.get("key").asText)
    assert(keys.contains(record.get._2.key), keys.toString)
    assertEquals(kit.views.forView(Records).get(record.get._2.key), Some(record.get._2))
    assert(answer.records.forall(_.get("subject").asText == record.get._2.subject))
  }

  Then("the registry's row for the player id is unchanged, since an id is not personal data") { () =>
    val now = kit.components.forKeyValueEntity(Identity.entityId(record.get._2.subject)).call(Identity.get).invoke()
    assertEquals(Some(now), identity)
    assertEquals(now.realId, id("player"))
  }

  // ── retention ────────────────────────────────────────────────────────────────────────────────

  private def years(b: String): Int =
    kit.request("GET", s"/v1/audit-retention/$b", headers = kit.asInstallationCompliance()).json.get("years").asInt

  // spinvibe is raised; goldvibe keeps the installation's, which the shared kit's other features read.
  Given("a brand whose licence requires ten years") { () => brand = "spinvibe" }

  Given("a brand whose retention is the installation's {string} years") { (y: String) =>
    brand = "goldvibe"
    assertEquals(years(brand), y.toInt)
  }

  When("its retention is set to {string} years") { (y: String) =>
    answer = kit.request("PUT", s"/v1/audit-retention/$brand", Some(s"""{"years":$y}"""), kit.asOwner())
  }

  Then("records of that brand are kept {string} years") { (y: String) =>
    assertEquals(answer.status, 200, answer.text)
    assertEquals(years(brand), y.toInt)
  }

  Then("another brand's records are kept the installation's {string} years") { (y: String) => assertEquals(years("goldvibe"), y.toInt) }

  Then("it is refused") { () => assertEquals((answer.status, answer.code), (409, "below_floor")) }

  // ── restore ──────────────────────────────────────────────────────────────────────────────────

  private var restorePoint: Instant = Instant.EPOCH

  private val topics = com.thinkmorestupidless.ankka.runtime.InMemoryBroker()

  Given("the shared audit's database restored to a point in time") { () =>
    kit.mirrors = Vector(topics)
    keep(Kit.record(id = id("before"))): Unit
    Thread.sleep(1100)
    restorePoint = Instant.now()
    Thread.sleep(1100)
    val r = Kit.record(id = id("after"))
    afterRestore = Some(r -> keep(r))
    kit.mirrors = Vector.empty
  }

  When("the shared audit resumes") { () =>
    restored = Some(Kit(configure = _.copy(readGeneration = 2, readFrom = Some(restorePoint)), restoring = Some(kit), broker = topics))
    kit.rewire()
  }

  Then("the records after the restore point are re-read from each brand's audit records where the brand still holds them") { () =>
    val k = restored.get
    k.rewire()
    try
      val (r, before) = afterRestore.get
      val again = k.eventually(30.seconds)(k.views.forView(Records).get(keyOf(gold, r)))
      assertEquals((again.key, again.action, again.occurredAt), (before.key, before.action, before.occurredAt))
    finally kit.rewire()
  }

  Then("the shared audit's status names any brand whose audit records no longer reach back to the restore point") { () =>
    val r = kit.request("GET", "/v1/audit-status", headers = kit.asInstallationCompliance())
    val brands = r.json.get("brands")
    val keeps = (0 until brands.size).flatMap(i => (0 until brands.get(i).get("sources").size).map(j =>
      brands.get(i).get("sources").get(j).get("topicKeeps").asText))
    // Every brand's audit records reach back: none is named.
    assert(keeps.nonEmpty && keeps.forall(_ == StatusRoute.Everything), keeps.toString)
  }
