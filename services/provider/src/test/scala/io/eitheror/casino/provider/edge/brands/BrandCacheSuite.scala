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

  test("a write made at another replica is seen by each instance's own refresh") {
    val kit = AnkkaTestKit.start(Seq(BrandEntryEntity.descriptor, BrandIndex.descriptor))
    try
      val client = kit.componentClient
      // Two instances' copies, each with its own refresh, as two replicas have (no singleton timer).
      val (one, _) = BrandCache.started(client, Map.empty)
      val (two, _) = BrandCache.started(client, Map.empty)
      val refreshes = Seq(one.refreshEvery(client, 200.millis), two.refreshEvery(client, 200.millis))
      try
        // Another replica's write: the entities change, neither copy does.
        client.forKeyValueEntity(EntityId("goldvibe")).call(BrandEntryEntity.put).invoke(entry("goldvibe")): Unit
        client.forKeyValueEntity(EntityId(BrandIndex.Id)).call(BrandIndex.add).invoke("goldvibe"): Unit
        val deadline = System.nanoTime() + 10.seconds.toNanos
        while !(one.known("goldvibe") && two.known("goldvibe")) && System.nanoTime() < deadline do Thread.sleep(50)
        assert(one.known("goldvibe") && two.known("goldvibe"))
      finally refreshes.foreach(_.close())
    finally kit.stop()
  }

  extension [A](a: A) private def tap(f: A => Unit): A = { f(a); a }
