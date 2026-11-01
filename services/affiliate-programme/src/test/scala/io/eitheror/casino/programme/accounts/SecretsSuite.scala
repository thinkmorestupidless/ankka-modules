package io.eitheror.casino.programme.accounts

import com.thinkmorestupidless.ankka.testkit.InMemorySecretStore

import java.time.{Duration, Instant}

class SecretsSuite extends munit.FunSuite:
  test("a data key is made once and read back; destroyed, it is gone") {
    val store = InMemorySecretStore()
    val s     = Secrets(store)
    val k     = s.dataKey("aff-1").toOption.get
    assertEquals(k.length, 32)
    assert(java.util.Arrays.equals(s.dataKey("aff-1").toOption.get, k))
    assert(java.util.Arrays.equals(Secrets(store).existingDataKey("aff-1").toOption.get.get, k))
    s.destroy("aff-1"): Unit
    assertEquals(s.existingDataKey("aff-1").map(_.isDefined), Right(false))
    assertEquals(Secrets(store).existingDataKey("aff-1").map(_.isDefined), Right(false))
  }

  test("reads are cached for 60 seconds") {
    val store = InMemorySecretStore()
    var now   = Instant.parse("2026-11-01T10:00:00Z")
    val s     = Secrets(store, now = () => now)
    assertEquals(s.brandSecret("goldvibe", Secrets.WebhookSecret), Right(None))
    store.put(Secrets.brand("goldvibe", Secrets.WebhookSecret), "whsec")
    assertEquals(s.brandSecret("goldvibe", Secrets.WebhookSecret), Right(None))
    now = now.plus(Duration.ofSeconds(61))
    assertEquals(s.brandSecret("goldvibe", Secrets.WebhookSecret), Right(Some("whsec")))
  }

  test("the email index key is one key") {
    val s = Secrets(InMemorySecretStore())
    assert(java.util.Arrays.equals(s.emailIndexKey.toOption.get, s.emailIndexKey.toOption.get))
  }

  test("a store that cannot be read is unavailable, never none") {
    assert(Secrets(Secrets.failing("down")).existingDataKey("aff-1").isLeft)
  }
