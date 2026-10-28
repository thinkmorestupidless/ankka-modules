package io.eitheror.casino.attribution.networks

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.attribution.networks.Documents.*
import io.eitheror.casino.attribution.tag.Signing

import java.time.Instant

/** Each document's example from contracts/protocol.md parses; each refusal names why. */
class DocumentsSuite extends munit.FunSuite with LogCapturing:
  private val (x, _) = Signing.generate()

  test("the key document, with the brand's transition") {
    val body = s"""{"keys":[{"kid":"k-2026-10","kty":"OKP","crv":"Ed25519","x":"$x"}],
      "cacheSeconds":3600,
      "transition":{"firstDay":"2026-11-01","endsAt":"2027-11-01","endedOn":null,"endedBy":null,"legacyTagsLast30Days":12}}"""
    val d = keyDocument(body).toOption.get
    assertEquals(d.keys, Map("k-2026-10" -> x))
    assertEquals(d.cacheSeconds, 3600L)
    assertEquals(d.transition, Some(Transition("2026-11-01", Some("2027-11-01"), None, None, 12)))
  }

  test("a key that is not Ed25519, or whose x is not a key, is refused naming it") {
    assert(keyDocument(s"""{"keys":[{"kid":"k1","kty":"EC","crv":"P-256","x":"$x"}],"cacheSeconds":60}""").left.exists(_.contains("k1")))
    assert(keyDocument("""{"keys":[{"kid":"k2","kty":"OKP","crv":"Ed25519","x":"AAAA"}],"cacheSeconds":60}""").left.exists(_.contains("k2")))
  }

  test("the revocation list") {
    val d = revocationList("""{"asOf":"2026-10-25T10:00:00Z","refreshSeconds":300,"links":["lnk-01"],"affiliates":["aff-9"]}""").toOption.get
    assertEquals(d, RevocationList(Set("lnk-01"), Set("aff-9"), 300, Some(Instant.parse("2026-10-25T10:00:00Z"))))
  }

  test("the legacy tag document, its UUIDs lower-cased") {
    val d = legacyTagDocument("""{"version":3,"cacheSeconds":3600,"tags":{"3F2B1C4D-AAAA-4BBB-8CCC-0123456789AB":{"affiliate":"aff-7f3c","link":"lnk-01"}}}""").toOption.get
    assertEquals(d.tags, Map("3f2b1c4d-aaaa-4bbb-8ccc-0123456789ab" -> ("aff-7f3c", "lnk-01")))
    assertEquals(d.version, 3L)
  }

  test("the code document; codes matched regardless of case") {
    val d = codeDocument("""{"brand":"goldvibe","version":12,"at":"2026-10-25T10:00:00Z","cacheSeconds":300,
      "codes":[{"code":"SUMMER50","offer":"offer-3","tag":"a.b.c"}]}""").toOption.get
    assertEquals(d.brand, "goldvibe")
    assertEquals(d.lookup("summer50"), Some("offer-3" -> "a.b.c"))
    assertEquals(d.lookup(" Summer50 "), Some("offer-3" -> "a.b.c"))
    assertEquals(d.lookup("WINTER"), None)
  }

  test("a document over 1 MiB, not JSON, or missing its cache field is refused naming why") {
    assert(keyDocument("""{"keys":[],"cacheSeconds":1,"pad":"""" + "x" * (MaxBytes + 1) + "\"}").left.exists(_.contains("1 MiB")))
    assert(revocationList("not json").left.exists(_.contains("not a JSON object")))
    assert(revocationList("[1,2]").left.exists(_.contains("not a JSON object")))
    assert(keyDocument("""{"keys":[]}""").left.exists(_.contains("cacheSeconds")))
    assert(revocationList("""{"links":[]}""").left.exists(_.contains("refreshSeconds")))
    assert(legacyTagDocument("""{"tags":{}}""").left.exists(_.contains("cacheSeconds")))
    assert(codeDocument("""{"codes":[]}""").left.exists(_.contains("cacheSeconds")))
  }

  test("each kind's cache time") {
    assertEquals(cacheSeconds(Kind.Revocations, """{"refreshSeconds":300}"""), Right(300L))
    assertEquals(cacheSeconds(Kind.Codes, """{"cacheSeconds":30}"""), Right(30L))
  }
