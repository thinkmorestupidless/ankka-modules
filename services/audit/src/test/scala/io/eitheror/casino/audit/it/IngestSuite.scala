package io.eitheror.casino.audit.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.audit.ids.Ids
import io.eitheror.casino.audit.ingest.{Quarantined, SourceState}
import io.eitheror.casino.audit.registry.{Identity, RegistryKey}
import io.eitheror.casino.audit.store.Records
import io.eitheror.casino.audit.stored.{Source, Stored}
import io.eitheror.casino.contract.facts.{AuditChange, PlatformAudit, PlatformAuditFact}

import scala.concurrent.duration.*

/** Two brands' and payments' records read into the store (FR-007–FR-012), over Postgres and the in-memory broker. */
class IngestSuite extends munit.FunSuite:
  override val munitTimeout: Duration = 120.seconds
  private var kit: Kit = null

  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()
  override def beforeEach(context: BeforeEach): Unit = kit.rewire()

  private def key(source: Source, recordId: String) =
    s"${source.name}/${Ids.digest(RegistryKey.required(kit.secrets), source.project, recordId)}"

  private def stored(source: Source, recordId: String): Stored =
    kit.eventually()(kit.views.forView(Records).get(key(source, recordId)))

  private def counts(source: String) =
    kit.components.forKeyValueEntity(SourceState.entityId(source)).call(SourceState.get).invoke()

  test("two brands' and payments' records are each kept once, every person by a pseudonym bound in the registry") {
    val gold  = Kit.record(brand = "goldvibe", actorId = kit.fresh("op"), subjectId = kit.fresh("p"))
    val spin  = Kit.record(brand = "spinvibe", actorId = kit.fresh("op"), subjectId = kit.fresh("p"))
    val pay   = Kit.record(brand = "goldvibe", service = "merchant", actorId = kit.fresh("op"), subjectKind = "merchant", subjectId = "m-1")
    kit.publish("goldvibe", gold)
    kit.publish("spinvibe", spin)
    kit.publish("payments", pay)
    val g = stored(Source.Brand("goldvibe"), gold.recordId)
    val s = stored(Source.Brand("spinvibe"), spin.recordId)
    val p = stored(Source.Payments, pay.recordId)
    assertEquals((g.brand, s.brand, p.brand, p.source), ("goldvibe", "spinvibe", "goldvibe", "payments"))
    assertEquals(p.subject, "m-1")
    val identity = kit.components.forKeyValueEntity(Identity.entityId(g.subject)).call(Identity.get).invoke()
    assertEquals((identity.brand, identity.kind, identity.realId), ("goldvibe", "player", gold.subjectId))
  }

  test("a money event no person caused is passed over and counted; an operator's adjustment is kept with its amount") {
    val before = counts("brand:goldvibe")
    val bet = Kit.record(service = "wallet", action = "wallet.bet", actorKind = "service", actorId = "wallet", category = "money")
    val adj = Kit.record(service = "wallet", action = "wallet.adjust", actorId = kit.fresh("op"), category = "money", reasonCode = Some("goodwill"),
      changes = Vector(AuditChange("amount", None, Some("2500")), AuditChange("currency", None, Some("EUR"))))
    kit.publish("goldvibe", bet)
    kit.publish("goldvibe", adj)
    assertEquals(stored(Source.Brand("goldvibe"), adj.recordId).amount, Some(2500L))
    kit.eventually()(Option.when(counts("brand:goldvibe").passedOver > before.passedOver)(()))
    assertEquals(kit.views.forView(Records).get(key(Source.Brand("goldvibe"), bet.recordId)), None)
  }

  test("each quarantine reason: undecodable, another brand's, a personal value, a collision; none stops the source") {
    val bytes = s"""{"type":"audit_record","recordId":"${kit.fresh("bad")}"""".getBytes
    kit.publishBytes("goldvibe", "bad", bytes)
    val mismatched = Kit.record(brand = "spinvibe")
    kit.publish("goldvibe", mismatched)
    val personal = Kit.record(changes = Vector(AuditChange("email", Some("someone@example.com"), None)))
    kit.publish("goldvibe", personal)
    val first = Kit.record(id = kit.fresh("r"))
    kit.publish("goldvibe", first)
    stored(Source.Brand("goldvibe"), first.recordId)
    val collision = first.copy(outcome = "refused")
    kit.publish("goldvibe", collision)
    def held(b: Array[Byte]) =
      kit.components.forKeyValueEntity(Quarantined.entityId("brand:goldvibe", b)).call(Quarantined.get).invoke().why
    def asBytes(r: io.eitheror.casino.contract.facts.AuditRecord) = PlatformAudit.serializer.toBytes(r: PlatformAuditFact)
    kit.eventually()(Option.when(held(bytes) == "undecodable")(()))
    kit.eventually()(Option.when(held(asBytes(mismatched)) == "brand_mismatch")(()))
    kit.eventually()(Option.when(held(asBytes(personal)) == "personal_value")(()))
    kit.eventually()(Option.when(held(asBytes(collision)) == "collision")(()))
    assertEquals(stored(Source.Brand("goldvibe"), first.recordId).outcome, "done")
    val after = Kit.record()
    kit.publish("goldvibe", after)
    stored(Source.Brand("goldvibe"), after.recordId)
  }

  test("a redelivered record changes nothing (FR-010)") {
    val r = Kit.record(actorId = kit.fresh("op"))
    kit.publish("goldvibe", r)
    val first = stored(Source.Brand("goldvibe"), r.recordId)
    val storedBefore = counts("brand:goldvibe").stored
    kit.publish("goldvibe", r)
    val marker = Kit.record()
    kit.publish("goldvibe", marker)
    stored(Source.Brand("goldvibe"), marker.recordId)
    assertEquals(kit.views.forView(Records).get(first.key), Some(first))
    assertEquals(counts("brand:goldvibe").stored, storedBefore + 1)
  }

  test("a revoked source stops at its last record, and its state holds since when") {
    val last = Kit.record(brand = "spinvibe")
    kit.publish("spinvibe", last)
    stored(Source.Brand("spinvibe"), last.recordId)
    val since = counts("brand:spinvibe").lastReceived
    kit.revoked = Set("spinvibe")
    try
      val unseen = Kit.record(brand = "spinvibe")
      kit.publish("spinvibe", unseen)
      kit.publish("goldvibe", Kit.record())
      Thread.sleep(1000)
      assertEquals(kit.views.forView(Records).get(key(Source.Brand("spinvibe"), unseen.recordId)), None)
      assertEquals(counts("brand:spinvibe").lastReceived, since)
      assert(since.isDefined)
    finally kit.revoked = Set.empty
  }
