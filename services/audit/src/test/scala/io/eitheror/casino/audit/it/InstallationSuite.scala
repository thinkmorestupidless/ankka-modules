package io.eitheror.casino.audit.it

import io.eitheror.casino.audit.api.StatusRoute
import io.eitheror.casino.audit.ids.Ids
import io.eitheror.casino.audit.registry.RegistryKey
import io.eitheror.casino.audit.stored.Visibility
import io.eitheror.casino.contract.facts.{AuditChange, ModuleChange}

import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.*

/**
 * Installation records (FR-019, FR-020, FR-024, SC-007): each module change and each erasure state an
 * installation record of the brand, beside the brand's own in one order and readable by the brand's
 * compliance role within 2 minutes; the status and its installation sources.
 */
class InstallationSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 180.seconds
  private var kit: Kit = null

  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()
  override def beforeEach(context: BeforeEach): Unit = kit.rewire()

  private val base = Instant.parse("2026-11-09T07:00:00Z")

  test("each module change and each erasure state is an installation record beside the brand's own, in one order, within 2 minutes") {
    val module = kit.fresh("module")
    val corr   = kit.fresh("erasure")
    val player = kit.fresh("p")
    val started = System.nanoTime()
    ModuleChange.Changes.zipWithIndex.foreach((c, i) =>
      kit.publishModule("goldvibe", ModuleChange(module, c, Some("0.3.0"), Some("stable"), "vendor:agent", base.plusSeconds(10L * i))))
    Vector("filed", "held", "applied", "completed").zipWithIndex.foreach((state, i) =>
      kit.publish("goldvibe", Kit.record(service = "players", action = s"erasure.$state", actorKind = "system", actorId = "players",
        subjectId = player, category = "erasure", correlationId = corr, changes = Vector(AuditChange("erasure", None, Some(state))),
        occurredAt = base.plusSeconds(5L + 10L * i))))
    kit.publish("goldvibe", Kit.record(correlationId = corr, subjectId = player, occurredAt = base.plusSeconds(1)))
    val officer = kit.asBrandCompliance("goldvibe")
    def read() = kit.query(officer, "brand" -> "goldvibe", "from" -> base.toString, "to" -> base.plusSeconds(3600).toString, "limit" -> "1000")
      .records.filter(r => r.get("service").asText == module || r.get("subject").asText != "goldvibe")
    val digest = Ids.digest(RegistryKey.required(kit.secrets), "goldvibe", corr)
    val rows = kit.eventually(2.minutes)(Option(read()).filter(rs =>
      rs.count(_.get("service").asText == module) == ModuleChange.Changes.size && rs.count(_.get("correlation").asText == digest) == 5))
    assert(JDuration.ofNanos(System.nanoTime() - started).compareTo(JDuration.ofMinutes(2)) < 0)
    val mine = rows.filter(r => r.get("service").asText == module || r.get("correlation").asText == digest)
    val times = mine.map(r => Instant.parse(r.get("occurredAt").asText))
    assertEquals(times, times.sorted)
    val installation = mine.filter(_.get("visibility").asText == Visibility.Installation)
    assertEquals(installation.size, ModuleChange.Changes.size + 4)
    assertEquals(mine.count(_.get("visibility").asText == Visibility.Brand), 1)
    assertEquals(mine.filter(_.get("service").asText == module).map(_.get("action").asText), ModuleChange.Changes.map(c => s"module.$c"))
    // The erasure's records name the player by pseudonym only, and stay once it is complete.
    val erasures = mine.filter(_.get("action").asText.startsWith("erasure."))
    assertEquals(erasures.map(_.get("action").asText), Vector("erasure.filed", "erasure.held", "erasure.applied", "erasure.completed"))
    assert(erasures.forall(r => r.get("subject").asText != player && r.get("subject").asText.nonEmpty))
  }

  test("the status names each source of a brand, the installation sources read and not stated, and the retention") {
    val r = kit.request("GET", "/v1/audit-status", headers = kit.asInstallationCompliance())
    assertEquals(r.status, 200, r.text)
    val gold = r.json.get("brands").get(0)
    assertEquals(gold.get("brand").asText, "goldvibe")
    assertEquals(gold.get("retentionYears").asInt, 7)
    assertEquals((0 until gold.get("sources").size).map(i => gold.get("sources").get(i).get("source").asText).toVector,
      Vector("brand:goldvibe", "modules:goldvibe"))
    val sources = gold.get("installationSources")
    assertEquals(StatusRoute.InstallationSources.map((k, _) => k -> sources.get(k).asText), StatusRoute.InstallationSources)
    assertEquals(r.json.get("shared").get(0).get("source").asText, "payments")
    assertEquals(kit.request("GET", "/v1/audit-status", headers = kit.asOwner()).status, 200)
    val brand = kit.request("GET", "/v1/audit-status?brand=goldvibe", headers = kit.asBrandCompliance("goldvibe"))
    assertEquals((brand.status, brand.json.has("shared")), (200, false))
    assertEquals(kit.request("GET", "/v1/audit-status", headers = kit.asBrandCompliance("goldvibe")).status, 403)
    assertEquals(kit.request("GET", "/v1/audit-status?brand=spinvibe", headers = kit.asBrandCompliance("goldvibe")).status, 403)
    assertEquals(kit.request("GET", "/v1/audit-status", headers = kit.as("op-1", "brand_admin", "brand_goldvibe")).status, 403)
  }
