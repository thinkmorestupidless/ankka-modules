package io.eitheror.casino.attribution.it

import io.eitheror.casino.attribution.TagOutcome
import io.eitheror.casino.attribution.carriers.{FeedFact, WebhookHttp}
import io.eitheror.casino.attribution.networks.{DocumentFetch, Documents}
import io.eitheror.casino.attribution.tag.Tag

import java.time.{Duration, Instant}

/** The fakes speak the protocol: their documents parse with the service's parsers, their tokens verify. */
class FakesSuite extends munit.FunSuite:
  private val network  = FakeNetwork()
  private val receiver = WebhookReceiver()
  private val fetch    = DocumentFetch.http(Duration.ofSeconds(5))

  override def afterAll(): Unit = { network.stop(); receiver.stop() }

  private def body(url: String) = fetch.fetch(url).toOption.get._2

  test("the four documents parse; a failed one answers 503") {
    network.legacy = Map("3f2b1c4d-aaaa-4bbb-8ccc-0123456789ab" -> ("aff-1", "lnk-1"))
    network.codes = Map("SUMMER50" -> ("offer-3", network.mint("aff-1", "lnk-1")))
    val keys = Documents.keyDocument(body(network.keyDocumentUrl)).toOption.get
    assertEquals(keys.keys.keySet, Set(network.current.kid))
    assert(Documents.revocationList(body(network.revocationsUrl)).isRight)
    assertEquals(Documents.legacyTagDocument(body(network.legacyUrl)).toOption.get.tags.size, 1)
    assertEquals(Documents.codeDocument(body(network.codesUrl("goldvibe"))).toOption.get.brand, "goldvibe")
    network.fail("keys")
    assertEquals(fetch.fetch(network.keyDocumentUrl).map(_._1), Right(503))
    network.reset()
  }

  test("a minted token verifies under the key document; after rotate the old key is withdrawn") {
    val old   = network.current
    val token = body(s"${network.address}/tag?aff=aff-1&lnk=lnk-1&iat=${Instant.now().getEpochSecond}")
    val keys  = Documents.keyDocument(body(network.keyDocumentUrl)).toOption.get.keys
    val ok    = Tag.verify(Tag.parse(token).toOption.get, Tag.Context(networkKnown = true, keys = Some(keys)))
    assert(ok.isInstanceOf[TagOutcome.Valid], ok.toString)
    network.rotate(): Unit
    assert(!Documents.keyDocument(body(network.keyDocumentUrl)).toOption.get.keys.contains(old.kid))
  }

  test("the receiver records posts, answers its script and checks signatures") {
    receiver.answer(Some(503))
    val f    = FeedFact("click/c-1", "click", "{}")
    val http = WebhookHttp.client(Duration.ofSeconds(5))
    assertEquals(http.post(receiver.hookUrl, f.json, WebhookHttp.headers(f, "s3cret")), Right(503))
    assertEquals(http.post(receiver.hookUrl, f.json, WebhookHttp.headers(f, "wrong")), Right(200))
    assertEquals(receiver.posts.map(receiver.signed), Vector(true, false))
  }
