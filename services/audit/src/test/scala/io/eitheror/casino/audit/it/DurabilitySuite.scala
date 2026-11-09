package io.eitheror.casino.audit.it

import io.eitheror.casino.audit.ids.Ids
import io.eitheror.casino.audit.registry.{Identity, RegistryKey}
import io.eitheror.casino.audit.store.{Record, Records}
import io.eitheror.casino.audit.stored.{Source, Stored}
import io.eitheror.casino.audit.sweep.{Sweep, SweepTimer}
import io.eitheror.casino.contract.facts.{AuditChange, AuditRecord}

import java.time.{Instant, ZoneOffset}
import scala.concurrent.duration.*

/**
 * Durability (FR-010, FR-011, FR-021 to FR-023, SC-008): no route changes or deletes a record; an erased
 * player's records and registry rows stay; retention per brand, raised only; the sweep removes only
 * what is past and records its counts; a raised generation re-reads from the restore point.
 */
class DurabilitySuite extends munit.FunSuite:
  override val munitTimeout: Duration = 240.seconds
  private var kit: Kit = null

  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()
  override def beforeEach(context: BeforeEach): Unit = kit.rewire()

  private def keyOf(source: Source, r: AuditRecord) =
    s"${source.name}/${Ids.digest(RegistryKey.required(kit.secrets), source.project, r.recordId)}"
  private def stored(k: Kit, source: Source, r: AuditRecord): Stored =
    k.eventually(30.seconds)(k.views.forView(Records).get(keyOf(source, r)))
  private def keep(brand: String, r: AuditRecord): Stored =
    kit.publish(brand, r)
    stored(kit, Source.Brand(brand), r)

  private def yearsAgo(years: Int, days: Int = 0): Instant = kit.now.atOffset(ZoneOffset.UTC).minusYears(years.toLong).minusDays(days.toLong).toInstant

  test("no route and no role changes or deletes a record") {
    val r    = Kit.record(id = kit.fresh("r"))
    val kept = keep("goldvibe", r)
    val roles = Vector(kit.asOwner(), kit.asInstallationCompliance(), kit.asBrandCompliance("goldvibe"), kit.asReidentifier("goldvibe"))
    val paths = Vector("/v1/audit-records", s"/v1/audit-records/${kept.key}", s"/v1/audit-records?key=${kept.key}")
    for as <- roles; path <- paths; method <- Vector("PUT", "PATCH", "DELETE", "POST") do
      val answer = kit.request(method, path, Some("""{"outcome":"refused"}"""), as)
      assert(answer.status >= 400, s"$method $path answered ${answer.status}")
    assertEquals(kit.views.forView(Records).get(kept.key), Some(kept))
    // The entity itself takes no change: the same key with another record is a collision, kept as it was.
    val other = kept.copy(outcome = "refused")
    assertEquals(kit.components.forKeyValueEntity(Record.entityId(kept.key)).call(Record.keep).invoke(other).outcome, Record.Collision)
    assertEquals(kit.components.forKeyValueEntity(Record.entityId(kept.key)).call(Record.get).invoke().stored, Some(kept))
  }

  test("an erased player's records stay under the pseudonym, and the registry's row for the id is unchanged") {
    val player = kit.fresh("p")
    val kept   = keep("goldvibe", Kit.record(id = kit.fresh("r"), subjectId = player))
    val before = kit.components.forKeyValueEntity(Identity.entityId(kept.subject)).call(Identity.get).invoke()
    keep("goldvibe", Kit.record(id = kit.fresh("erased"), service = "players", action = "erasure.completed", actorKind = "system",
      actorId = "players", subjectId = player, category = "erasure", changes = Vector(AuditChange("erasure", None, Some("completed")))))
    assertEquals(kit.views.forView(Records).get(kept.key), Some(kept))
    assertEquals(kit.components.forKeyValueEntity(Identity.entityId(kept.subject)).call(Identity.get).invoke(), before)
    assertEquals(before.realId, player)
  }

  test("ten years kept beside seven; lower refused; each raise a record") {
    val raised = kit.request("PUT", "/v1/audit-retention/goldvibe", Some("""{"years":10}"""), kit.asOwner())
    assertEquals((raised.status, raised.json.get("years").asInt), (200, 10), raised.text)
    assertEquals(kit.request("GET", "/v1/audit-retention/goldvibe", headers = kit.asBrandCompliance("goldvibe")).json.get("years").asInt, 10)
    assertEquals(kit.request("GET", "/v1/audit-retention/spinvibe", headers = kit.asInstallationCompliance()).json.get("years").asInt, 7)
    assertEquals(kit.request("PUT", "/v1/audit-retention/goldvibe", Some("""{"years":8}"""), kit.asOwner()).code, "below_floor")
    assertEquals(kit.request("PUT", "/v1/audit-retention/spinvibe", Some("""{"years":5}"""), kit.asOwner()).code, "below_floor")
    assertEquals(kit.request("PUT", "/v1/audit-retention/spinvibe", Some("""{"years":9}"""), kit.asInstallationCompliance()).status, 403)
    assertEquals(kit.request("GET", "/v1/audit-retention/spinvibe", headers = kit.asBrandCompliance("goldvibe")).status, 403)
    kit.eventually()(kit.own("audit.retention").find(r => r.brand == "goldvibe" && r.changes.exists(_.after.contains("10"))))
  }

  test("the sweep removes only what is past each brand's retention, and records what it removed per month") {
    kit.request("PUT", "/v1/audit-retention/goldvibe", Some("""{"years":10}"""), kit.asOwner()): Unit
    val eight    = yearsAgo(8)
    val oldSpin  = keep("spinvibe", Kit.record(brand = "spinvibe", id = kit.fresh("old"), occurredAt = eight))
    val oldGold  = keep("goldvibe", Kit.record(id = kit.fresh("old"), occurredAt = eight))
    val freshSpin = keep("spinvibe", Kit.record(brand = "spinvibe", id = kit.fresh("new"), occurredAt = yearsAgo(6, 300)))
    SweepTimer.run(kit.components): Unit
    val swept = kit.eventually(60.seconds)(Option(kit.components.forWorkflow(Sweep.entityId("spinvibe", kit.now)).call(Sweep.get).invoke())
      .filter(_.finished))
    assert(swept.removed.get(Sweep.month(eight)).exists(_ >= 1), swept.toString)
    kit.eventually()(Option.when(kit.views.forView(Records).get(oldSpin.key).isEmpty)(()))
    assertEquals(kit.views.forView(Records).get(oldGold.key), Some(oldGold))
    assertEquals(kit.views.forView(Records).get(freshSpin.key), Some(freshSpin))
    val record = kit.eventually()(kit.own(Sweep.Action).find(_.brand == "spinvibe"))
    assertEquals((record.actorKind, record.changes.find(_.field == s"removed.${Sweep.month(eight)}").isDefined), ("system", true))
    // The same day again starts nothing more.
    assertEquals(SweepTimer.run(kit.components).find(_.brand == "spinvibe").map(_.removed), Some(swept.removed))
  }

  test("a raised generation re-reads each brand's records from the restore point, and what it stores is what was stored") {
    val topics = com.thinkmorestupidless.ankka.runtime.InMemoryBroker()
    kit.mirrors = Vector(topics)
    val before = keep("goldvibe", Kit.record(id = kit.fresh("before")))
    Thread.sleep(1100)
    val restorePoint = Instant.now()
    Thread.sleep(1100)
    val afterRecord = Kit.record(id = kit.fresh("after"))
    val after = keep("goldvibe", afterRecord)
    kit.mirrors = Vector.empty
    val restored = Kit(configure = _.copy(readGeneration = 2, readFrom = Some(restorePoint)), restoring = Some(kit), broker = topics)
    try
      val again = stored(restored, Source.Brand("goldvibe"), afterRecord)
      // The stand-in database starts empty, so its registry issues its own pseudonyms; each names the same person.
      assertEquals(again.copy(arrivedAt = after.arrivedAt, actor = after.actor, subject = after.subject), after)
      def realId(k: Kit, pseudonym: String) = k.components.forKeyValueEntity(Identity.entityId(pseudonym)).call(Identity.get).invoke().realId
      assertEquals((realId(restored, again.actor), realId(restored, again.subject)), (realId(kit, after.actor), realId(kit, after.subject)))
      Thread.sleep(2000)
      assertEquals(restored.views.forView(Records).get(before.key), None)
    finally
      restored.stop()
      kit.rewire()
  }
