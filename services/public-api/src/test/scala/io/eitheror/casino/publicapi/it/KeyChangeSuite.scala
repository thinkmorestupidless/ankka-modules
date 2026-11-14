package io.eitheror.casino.publicapi.it

/** Original `grant-reload.test.ts`: a change applies from the next request, not after a reload (FR-019). */
class KeyChangeSuite extends munit.FunSuite:
  private lazy val kit = SharedKit.kit

  test("a disable, then an enable, applies from the next request") {
    val k = Keys.noFields(kit)
    assertEquals(kit.v1("/contract", Some(k.secret)).status, 200)
    Keys.act(kit, k, "disable"): Unit
    assertEquals(kit.v1("/contract", Some(k.secret)).status, 403)
    Keys.act(kit, k, "enable"): Unit
    assertEquals(kit.v1("/contract", Some(k.secret)).status, 200)
  }

  test("a delete applies from the next request") {
    val k = Keys.noFields(kit)
    assertEquals(kit.v1("/contract", Some(k.secret)).status, 200)
    kit.request("DELETE", s"/access-keys/${kit.brand}/${k.id}", headers = kit.asKeyManager): Unit
    assertEquals(kit.v1("/contract", Some(k.secret)).status, 401)
  }

  test("a field change and a rate change are what the next admission reads") {
    import com.thinkmorestupidless.ankka.core.EntityId
    import io.eitheror.casino.publicapi.keys.{AccessKey, Admit, RawKeys}
    val k = Keys.noFields(kit)
    Keys.change(kit, k, """{"identifyingFields":["email","city"],"requestsPerMinute":7}"""): Unit
    val a = kit.components.forEventSourcedEntity(EntityId(k.id)).call(AccessKey.admit).invoke(Admit(RawKeys.digest(k.secret), kit.now))
    assertEquals((a.identifyingFields, a.requestsPerMinute), (Set("email", "city"), 7))
  }
