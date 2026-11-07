package io.eitheror.casino.engagement.config

import com.thinkmorestupidless.ankka.sdk.SecretStore
import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit
import io.eitheror.casino.engagement.TestKeyring

import java.time.{Duration, Instant}
import scala.collection.mutable

/** data-model.md's `Configuration` and the secrets' cache (FR-004, R12). */
class ConfigurationSuite extends munit.FunSuite:
  private val config = CrmConfig("site-1", "eu", "hello@goldvibe.example", Map("DepositConfirmed" -> "7"))

  test("a put keeps the configuration and answers the fields it changed, by name") {
    TestKeyring.around { _ =>
      val kit = KeyValueEntityTestKit.of(Configuration, "goldvibe")
      assertEquals(kit.call(Configuration.put)(config).replyValue.changed, Vector("siteId", "region", "fromEmail", "triggers.DepositConfirmed"))
      val next = config.copy(triggers = Map("DepositConfirmed" -> "8", "PaymentFailed" -> "9"))
      assertEquals(kit.call(Configuration.put)(next).replyValue.changed, Vector("triggers.DepositConfirmed", "triggers.PaymentFailed"))
      assertEquals(kit.call(Configuration.put)(next).replyValue.changed, Vector.empty)
      assertEquals(kit.call(Configuration.get).replyValue, next)
    }
  }

  test("a trigger with an empty id is disabled, and absent is the same as empty") {
    val c = config.copy(triggers = Map("DepositConfirmed" -> "", "PaymentFailed" -> "9"))
    assertEquals((c.transactional("DepositConfirmed"), c.transactional("PaymentFailed"), c.transactional("BonusGranted")), (None, Some("9"), None))
    assertEquals(CrmConfig(triggers = Map("X" -> "")).changes(CrmConfig()), Vector.empty)
  }

  test("the region is eu or us, anything else us") {
    assertEquals(Seq("eu", " EU ", "us", "asia", "").map(CrmConfig.region), Seq("eu", "eu", "us", "us", "us"))
  }

  private final class Store extends SecretStore:
    val values = mutable.Map.empty[String, String]
    var reads  = 0
    def put(name: String, value: String): Unit = values.update(name, value)
    def get(name: String): Option[String]      = { reads += 1; values.get(name) }
    def delete(name: String): Unit             = values.remove(name): Unit

  test("the keys are read under crm/<slug>/…, held for the ttl, and a write is read back at once") {
    var now     = Instant.parse("2026-11-03T10:00:00Z")
    val secrets = Secrets(Duration.ofSeconds(30), () => now)
    val store   = Store()
    store.put("crm/goldvibe/track-key", "tk-1")
    assertEquals(secrets.keys(store, "goldvibe"), CrmKeys(Some("tk-1"), None, None))
    store.put("crm/goldvibe/track-key", "tk-2")
    assertEquals(secrets.keys(store, "goldvibe").track, Some("tk-1"))
    now = now.plusSeconds(31)
    assertEquals(secrets.keys(store, "goldvibe").track, Some("tk-2"))
    secrets.write(store, Secrets.app("goldvibe"), Some("ak"))
    assertEquals(secrets.keys(store, "goldvibe").app, Some("ak"))
    secrets.write(store, Secrets.app("goldvibe"), None)
    assertEquals((store.values.get("crm/goldvibe/app-key"), secrets.keys(store, "goldvibe").app), (None, None))
  }
