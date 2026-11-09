package io.eitheror.casino.audit.it

import io.eitheror.casino.audit.ids.Ids
import io.eitheror.casino.audit.registry.{RegistryKey, Seed}
import io.eitheror.casino.audit.store.{Record, Records}
import io.eitheror.casino.audit.stored.Stored

/** The store and the registry key over Postgres: what the stories stand on (research R4, R5). */
class StoreSuite extends munit.FunSuite:
  private var kit: Kit = null

  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()

  private def stored(key: String) =
    Stored(key, "brand:goldvibe", "goldvibe", "brand", "wallet", "wallet.adjust", "done", None, "operator", kit.now, "operator",
      "ps-1", "player", "ps-2", None, Vector.empty, None, None, "", "", key.split('/').last, kit.now)

  test("a record's row is in the view, and a drop deletes the row (research R4: no tombstone needed)") {
    val s = stored(s"brand:goldvibe/${kit.fresh("d")}")
    val record = kit.components.forKeyValueEntity(Record.entityId(s.key))
    assert(record.call(Record.keep).invoke(s).isNew)
    kit.eventually()(kit.views.forView(Records).get(s.key).filter(_ == s))
    assertEquals(record.call(Record.drop).invoke("sweep-1").outcome, Record.Dropped)
    kit.eventually()(Option.when(kit.views.forView(Records).get(s.key).isEmpty)(()))
  }

  test("two starts read the same registry key") {
    val key = RegistryKey.required(kit.secrets)
    RegistryKey.forget()
    Seed.run(kit.secrets)
    assertEquals(Ids.digest(RegistryKey.required(kit.secrets), "goldvibe", "x"), Ids.digest(key, "goldvibe", "x"))
  }
