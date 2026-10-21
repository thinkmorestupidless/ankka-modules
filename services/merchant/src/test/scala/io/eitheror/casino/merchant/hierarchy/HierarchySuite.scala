package io.eitheror.casino.merchant.hierarchy

import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

import java.time.Instant

/** FR-001 to FR-006 at the entities: the hierarchy, merchant keys and credential records. */
class HierarchySuite extends munit.FunSuite:

  private val at    = Instant.parse("2026-10-12T10:00:00Z")
  private val op    = Actor.operator("op-1")

  test("a holding is created once with its actor; the same create again records nothing; another create conflicts") {
    val kit = EventSourcedTestKit.of(Holding, "h-1")
    val first = kit.call(Holding.create)(CreateOrg("Acme Holdings", None, op, at))
    assertEquals(first.events, Vector(OrgEvent.Created("Acme Holdings", None, op, at)))
    assertEquals(kit.currentState.status, OrgStatus.Active)
    assert(!kit.call(Holding.create)(CreateOrg("Acme Holdings", None, Actor.seed, at)).persisted)
    assertEquals(kit.call(Holding.create)(CreateOrg("Other", None, op, at)).error.code, ErrorCode.Conflict)
  }

  test("a company records its holding; a status changes once and an unknown status is refused") {
    val kit = EventSourcedTestKit.of(Company, "c-1")
    kit.call(Company.create)(CreateOrg("Acme Ltd", Some("h-1"), op, at)): Unit
    assertEquals(kit.currentState.parentId, Some("h-1"))
    assertEquals(kit.call(Company.changeStatus)(ChangeOrgStatus("Suspended", op, at)).events.size, 1)
    assert(!kit.call(Company.changeStatus)(ChangeOrgStatus("Suspended", op, at)).persisted)
    assertEquals(kit.call(Company.changeStatus)(ChangeOrgStatus("Gone", op, at)).error.code, ErrorCode.BadRequest)
  }

  private def subBrand(t: String = SubBrandType.Casino, brand: Option[String] = Some("acme")) =
    val kit = EventSourcedTestKit.of(SubBrand, "sb-1")
    kit.call(SubBrand.create)(CreateSubBrand("c-1", "h-1", "Acme Casino", t, brand, op, at)): Unit
    kit

  test("a sub-brand records its company and holding, its type and its brand") {
    val s = subBrand().currentState
    assertEquals((s.companyId, s.holdingId, s.subBrandType, s.brand, s.status), ("c-1", "h-1", "Casino", Some("acme"), "Active"))
  }

  test("a casino or combined sub-brand names its brand; a payments one names none") {
    val kit = EventSourcedTestKit.of(SubBrand, "sb-2")
    assertEquals(kit.call(SubBrand.create)(CreateSubBrand("c", "h", "x", "Casino", None, op, at)).error.code, ErrorCode.BadRequest)
    assertEquals(kit.call(SubBrand.create)(CreateSubBrand("c", "h", "x", "Payments", Some("acme"), op, at)).error.code, ErrorCode.BadRequest)
    assert(kit.call(SubBrand.create)(CreateSubBrand("c", "h", "x", "Payments", None, op, at)).persisted)
  }

  test("a generated key is recorded by its digest only; the same key id again answers the first") {
    val kit = subBrand()
    val r   = kit.call(SubBrand.generateKey)(GenerateKey("k-1", "frontend", "d1g3st", op, at))
    assertEquals(r.replyValue, MerchantKey("k-1", "frontend", "d1g3st", active = true, at))
    assert(!kit.call(SubBrand.generateKey)(GenerateKey("k-1", "frontend", "other", op, at)).persisted)
    assertEquals(kit.call(SubBrand.disableKey)(DisableKey("k-1", op, at)).events.size, 1)
    assert(!kit.currentState.keys.head.active)
    assertEquals(kit.call(SubBrand.disableKey)(DisableKey("k-9", op, at)).error.code, ErrorCode.NotFound)
  }

  test("a credential is recorded without secrets, and the same provider, name and environment is returned, not duplicated") {
    val kit = subBrand()
    val first = kit.call(SubBrand.registerCredential)(RegisterCredential("cr-1", "dreamzpay", "main", "sandbox", Some("VISA"), Vector("secureKey"), op, at))
    assert(first.replyValue.created)
    assertEquals(first.replyValue.record.provider, "DREAMZPAY")
    val again = kit.call(SubBrand.registerCredential)(RegisterCredential("cr-2", "DREAMZPAY", "main", "sandbox", None, Vector.empty, op, at))
    assertEquals(again.replyValue, Registered(first.replyValue.record, created = false))
    assert(!again.persisted)
  }

  test("the usable credential is the first active one with secrets, and a second is named as unreachable (FR-008)") {
    val kit = subBrand()
    kit.call(SubBrand.registerCredential)(RegisterCredential("cr-0", "DREAMZPAY", "empty", "live", None, Vector.empty, op, at)): Unit
    kit.call(SubBrand.registerCredential)(RegisterCredential("cr-1", "DREAMZPAY", "main", "live", None, Vector("secureKey"), op, at)): Unit
    kit.call(SubBrand.registerCredential)(RegisterCredential("cr-2", "DREAMZPAY", "spare", "live", None, Vector("secureKey"), op, at)): Unit
    assertEquals(kit.currentState.usableCredential("dreamzpay").map(_.id), Some("cr-1"))
    assertEquals(kit.currentState.usable("dreamzpay").map(_.id), Vector("cr-1", "cr-2"))
    kit.call(SubBrand.changeCredentialStatus)(ChangeCredentialStatus("cr-1", CredentialRecord.Disabled, op, at)): Unit
    assertEquals(kit.currentState.usableCredential("dreamzpay").map(_.id), Some("cr-2"))
    kit.call(SubBrand.deleteCredential)(DeleteCredential("cr-2", op, at)): Unit
    assertEquals(kit.currentState.usableCredential("dreamzpay"), None)
  }

  test("a credential's secrets changing records the key names and never a value (SC-007)") {
    val kit = subBrand()
    kit.call(SubBrand.registerCredential)(RegisterCredential("cr-1", "T365", "main", "live", None, Vector.empty, op, at)): Unit
    val r = kit.call(SubBrand.changeCredentialSecrets)(ChangeCredentialSecrets("cr-1", Vector("secretKey", "apiKey"), op, at))
    assertEquals(r.events, Vector(SubBrandEvent.CredentialSecretsChanged("cr-1", Vector("apiKey", "secretKey"), op, at)))
  }
