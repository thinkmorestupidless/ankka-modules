package io.eitheror.casino.attribution.networks

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
import io.eitheror.casino.attribution.networks.Documents.Kind
import io.eitheror.casino.attribution.tag.Signing

import java.time.Instant

class NetworkDocumentsSuite extends munit.FunSuite with LogCapturing:
  private val at        = Instant.parse("2026-10-25T10:00:00Z")
  private val (x, _)    = Signing.generate()
  private val keys      = s"""{"keys":[{"kid":"k1","kty":"OKP","crv":"Ed25519","x":"$x"}],"cacheSeconds":3600}"""
  private val revoked   = """{"refreshSeconds":300,"links":["lnk-01"],"affiliates":[]}"""
  private def kit       = KeyValueEntityTestKit.of(NetworkDocuments, "programme")

  test("put holds a document, parsed on read, fresh until it expires") {
    val k = kit
    val s = k.call(NetworkDocuments.put)(PutDocument(Kind.Keys, keys, at, at.plusSeconds(3600))).replyValue
    assertEquals(s.keys.map(_.keys), Some(Map("k1" -> x)))
    assert(k.call(NetworkDocuments.fresh)(Freshness(Kind.Keys, at.plusSeconds(3599))).replyValue)
    assert(!k.call(NetworkDocuments.fresh)(Freshness(Kind.Keys, at.plusSeconds(3600))).replyValue)
    assert(!k.call(NetworkDocuments.fresh)(Freshness(Kind.Revocations, at)).replyValue)
  }

  test("a copy fetched before the one held is ignored; one fetched later replaces it") {
    val k     = kit
    val (y, _) = Signing.generate()
    val newer = s"""{"keys":[{"kid":"k2","kty":"OKP","crv":"Ed25519","x":"$y"}],"cacheSeconds":3600}"""
    k.call(NetworkDocuments.put)(PutDocument(Kind.Keys, newer, at.plusSeconds(60), at.plusSeconds(3660))): Unit
    val stale = k.call(NetworkDocuments.put)(PutDocument(Kind.Keys, keys, at, at.plusSeconds(3600))).replyValue
    assertEquals(stale.keys.map(_.keys.keySet), Some(Set("k2")))
    val later = k.call(NetworkDocuments.put)(PutDocument(Kind.Keys, keys, at.plusSeconds(120), at.plusSeconds(3720))).replyValue
    assertEquals(later.keys.map(_.keys.keySet), Some(Set("k1")))
  }

  test("failed keeps the old body and its expiry, recording the error") {
    val k = kit
    k.call(NetworkDocuments.put)(PutDocument(Kind.Revocations, revoked, at, at.plusSeconds(300))): Unit
    val s = k.call(NetworkDocuments.failed)(DocumentFailed(Kind.Revocations, "503", at.plusSeconds(310))).replyValue
    val d = s.of(Kind.Revocations).get
    assertEquals((d.body, d.expiresAt, d.lastError), (Some(revoked), Some(at.plusSeconds(300)), Some("503")))
    assertEquals(s.revocations.map(_.links), Some(Set("lnk-01")))
  }

  test("failed with nothing held records the error and holds no body") {
    val s = kit.call(NetworkDocuments.failed)(DocumentFailed(Kind.Keys, "timeout", at)).replyValue
    assertEquals(s.keys, None)
    assert(!s.fresh(Kind.Keys, at))
  }

  test("a body that does not parse is refused, and an unknown kind") {
    val k = kit
    assert(k.call(NetworkDocuments.put)(PutDocument(Kind.Keys, "{}", at, at)).isError)
    assert(k.call(NetworkDocuments.put)(PutDocument("other", keys, at, at)).isError)
    assert(k.call(NetworkDocuments.failed)(DocumentFailed("other", "x", at)).isError)
  }
