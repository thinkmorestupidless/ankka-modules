package io.eitheror.casino.provider.edge.brands

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, KeyValueEntityTestKit, LogCapturing}

import scala.concurrent.duration.*

/** The brand entries and the cache the core reads them through (spec 026 R3). */
class BrandCacheSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout: Duration = 3.minutes

  private def entry(slug: String, site: String = "") = BrandEntry(
    slug, s"https://game-provider-$slug.127.0.0.1.sslip.io", if site.isEmpty then slug else site,
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE", "pt", testBrand = true, Vector.empty, keyDocument = false,
    BrandEntry.DefaultWaitMillis, BrandEntry.DefaultRefreshSeconds, Some("env:PROVIDER_LOCAL_CA"), realMoney = false
  )

  test("an entry is written under its own slug and refused naming the field it breaks") {
    val k = KeyValueEntityTestKit.of(BrandEntryEntity, "goldvibe")
    assertEquals(k.call(BrandEntryEntity.put)(entry("goldvibe")).replyValue.site, "goldvibe")
    assert(k.call(BrandEntryEntity.put)(entry("spinvibe")).errorMessage.contains("slug 'spinvibe'"))
    assert(k.call(BrandEntryEntity.put)(entry("goldvibe").copy(language = "en")).errorMessage.startsWith("language 'en'"))
    assert(k.call(BrandEntryEntity.put)(entry("goldvibe").copy(testBrand = false, keyDocument = true)).errorMessage.startsWith("keyDocument"))
    assert(k.call(BrandEntryEntity.put)(entry("goldvibe").copy(providerWaitMillis = 0)).errorMessage.startsWith("providerWaitMillis"))
    assertEquals(k.call(BrandEntryEntity.get).replyValue.language, "pt")
  }

  test("an entry stating real money and the key document refuses its launches; an empty game list runs every game") {
    val e = entry("goldvibe").copy(realMoney = true, keyDocument = true)
    assert(e.launchesRefused)
    assert(!e.copy(keyDocument = false).launchesRefused)
    assert(e.runs("loteca"))
    assert(!e.copy(games = Vector("test-slot")).runs("loteca"))
  }

  test("the cache answers the core from what was put, and resolves the CA from the service's settings") {
    val cache = BrandCache(Map("PROVIDER_LOCAL_CA" -> "-----BEGIN CERTIFICATE-----"))
    cache.put(entry("goldvibe"))
    cache.put(entry("spinvibe").copy(providerWaitMillis = 2000, testBrand = false))
    assert(cache.known("goldvibe"))
    assert(!cache.known("acmevibe"))
    assertEquals(cache.answerWait("spinvibe"), 2.seconds)
    assert(!cache.testBrand("spinvibe"))
    assertEquals(cache.slugs, Vector("goldvibe", "spinvibe"))
    assertEquals(cache("goldvibe").caPem, Some("-----BEGIN CERTIFICATE-----"))
    assertEquals(cache.bySite("spinvibe").map(_.slug), Some("spinvibe"))
    cache.remove("spinvibe")
    assertEquals(cache.slugs, Vector("goldvibe"))
    assertEquals(BrandCache(Map.empty).tap(_.put(entry("goldvibe")))("goldvibe").caPem, None)
  }

  test("a cache started over the entities reads every entry the index names, and skips one that is gone") {
    val kit = AnkkaTestKit.start(Seq(BrandEntryEntity.descriptor, BrandIndex.descriptor))
    try
      val client = kit.componentClient
      Vector("goldvibe", "spinvibe", "ghostvibe").foreach { s =>
        if s != "ghostvibe" then client.forKeyValueEntity(EntityId(s)).call(BrandEntryEntity.put).invoke(entry(s)): Unit
        client.forKeyValueEntity(EntityId(BrandIndex.Id)).call(BrandIndex.add).invoke(s): Unit
      }
      val (cache, failed) = BrandCache.started(client, Map.empty)
      assertEquals(failed, None)
      assertEquals(cache.slugs, Vector("goldvibe", "spinvibe"))
    finally kit.stop()
  }

  test("a write made at another replica is seen here at the next wake") {
    val kit = AnkkaTestKit.start(Seq(BrandEntryEntity.descriptor, BrandIndex.descriptor))
    val before = io.eitheror.casino.provider.core.Sweep.onWake
    try
      val client = kit.componentClient
      val (here, _) = BrandCache.started(client, Map.empty)
      io.eitheror.casino.provider.core.Sweep.onWake = here.refresh
      // Another replica's write: the entities change, this process's copy does not.
      client.forKeyValueEntity(EntityId("goldvibe")).call(BrandEntryEntity.put).invoke(entry("goldvibe")): Unit
      client.forKeyValueEntity(EntityId(BrandIndex.Id)).call(BrandIndex.add).invoke("goldvibe"): Unit
      assert(!here.known("goldvibe"))
      io.eitheror.casino.provider.core.Sweep.onWake(client)
      assert(here.known("goldvibe"))
    finally
      io.eitheror.casino.provider.core.Sweep.onWake = before
      kit.stop()
  }

  extension [A](a: A) private def tap(f: A => Unit): A = { f(a); a }
