package io.eitheror.casino.attribution.networks

import io.eitheror.casino.attribution.Recorded
import io.eitheror.casino.attribution.networks.Documents.Kind
import io.eitheror.casino.attribution.tag.Signing

import java.time.Instant
import scala.collection.mutable

/** The documents' refresh (T026), called directly with a scripted fetch and a recording client. */
class DocumentRefreshSuite extends munit.FunSuite:
  private val t0     = Instant.parse("2026-10-25T10:00:00Z")
  private val (x, _) = Signing.generate()
  private val keys   = s"""{"keys":[{"kid":"k1","kty":"OKP","crv":"Ed25519","x":"$x"}],"cacheSeconds":3600}"""
  private val revs   = """{"refreshSeconds":300,"links":[],"affiliates":[]}"""
  private val net    = NetworkState("programme", Some(Addresses("https://n.example/keys", "https://n.example/revocations")))

  /** A fetch answering from a map of address to answer, recording each address asked. */
  final class FakeFetch(answers: Map[String, Either[String, (Int, String)]]) extends DocumentFetch:
    val asked = mutable.ArrayBuffer.empty[String]
    def fetch(url: String): Either[String, (Int, String)] = { asked += url; answers.getOrElse(url, Left("no answer")) }

  private def recorded(held: DocumentsState = DocumentsState()) =
    Recorded(c => if c.method == "get" then NetworkDocumentsCodecs.given_Serializer_DocumentsState.toBytes(held) else "{}".getBytes)

  test("fetches each expired document and writes it, cached for the seconds it names") {
    val f = FakeFetch(Map("https://n.example/keys" -> Right(200 -> keys), "https://n.example/revocations" -> Right(200 -> revs)))
    val r = recorded()
    val out = DocumentRefresher(r.client, () => Vector(net), f, () => t0).run()
    assertEquals(out, Vector(("programme", Kind.Keys, true), ("programme", Kind.Revocations, true)))
    val puts = r.calls.filter(_.method == "put").map(_.as[PutDocument](using NetworkDocumentsCodecs.given_Serializer_PutDocument))
    assertEquals(puts.map(p => (p.kind, p.expiresAt)), Vector(Kind.Keys -> t0.plusSeconds(3600), Kind.Revocations -> t0.plusSeconds(300)))
  }

  test("a failure is recorded and the old body kept: put is not called for it") {
    val f = FakeFetch(Map("https://n.example/keys" -> Right(503 -> ""), "https://n.example/revocations" -> Left("timeout")))
    val r = recorded()
    DocumentRefresher(r.client, () => Vector(net), f, () => t0).run(): Unit
    assert(!r.calls.exists(_.method == "put"))
    val failed = r.calls.filter(_.method == "failed").map(_.as[DocumentFailed](using NetworkDocumentsCodecs.given_Serializer_DocumentFailed))
    assertEquals(failed.map(d => (d.kind, d.error)), Vector(Kind.Keys -> "the document answered 503", Kind.Revocations -> "timeout"))
  }

  test("a document current beyond the next two runs is not fetched; one expiring sooner is; a removed network is not refreshed") {
    val held = DocumentsState(Map(Kind.Keys -> CachedDocument(Some(t0), Some(t0.plusSeconds(600)), Some(keys))))
    val f = FakeFetch(Map("https://n.example/revocations" -> Right(200 -> revs)))
    DocumentRefresher(recorded(held).client, () => Vector(net), f, () => t0).run(): Unit
    assertEquals(f.asked.toVector, Vector("https://n.example/revocations"))
    // Current for a minute more: fetched now, since a registration never fetches (DocumentRefresh.Ahead).
    val soon = DocumentsState(Map(Kind.Keys -> CachedDocument(Some(t0), Some(t0.plusSeconds(60)), Some(keys))))
    val h = FakeFetch(Map("https://n.example/keys" -> Right(200 -> keys), "https://n.example/revocations" -> Right(200 -> revs)))
    DocumentRefresher(recorded(soon).client, () => Vector(net), h, () => t0).run(): Unit
    assert(h.asked.contains("https://n.example/keys"), h.asked.toString)
    val g = FakeFetch(Map.empty)
    assertEquals(DocumentRefresher(recorded().client, () => Vector(net.copy(removed = Some(t0))), g, () => t0).run(), Vector.empty)
    assert(g.asked.isEmpty)
  }
