package io.eitheror.casino.attribution.tag

import io.eitheror.casino.attribution.{Json, Named, Reasons, TagOutcome}

import java.time.{Duration, Instant}
import java.util.Base64

class TagSuite extends munit.FunSuite:
  private val (x, priv)   = Signing.generate()
  private val iat         = Instant.parse("2026-10-01T00:00:00Z")
  private val fields      = Tag.Fields("programme", "aff-7f3c", "lnk-01", Some("summer"), Vector("s1", "s2"), iat)
  private val token       = Tag.mint(fields, "k-2026-10", priv)
  private val keys        = Some(Map("k-2026-10" -> x))
  private val ok          = Tag.Context(networkKnown = true, keys = keys)
  private val month       = Duration.ofDays(30)

  private def parsed(t: String = token): Tag.Parsed = Tag.parse(t).toOption.get
  private def refused(outcome: TagOutcome): String = outcome match
    case TagOutcome.Refused(r) => r
    case other                 => fail(s"not refused: $other")

  private def b64(s: String) = Base64.getUrlEncoder.withoutPadding.encodeToString(s.getBytes("UTF-8"))
  private def forged(header: String, payload: String): String =
    val input = s"${b64(header)}.${b64(payload)}"
    s"$input.${Signing.encode(Signing.sign(priv, input.getBytes("US-ASCII")))}"

  test("a valid token's fields read back exactly, and it verifies") {
    val p = parsed()
    assertEquals(p.kid, "k-2026-10")
    assertEquals(p.named, Named("programme", "aff-7f3c", "lnk-01", Some("summer"), Vector("s1", "s2")))
    assertEquals(p.iat, iat)
    assertEquals(Tag.verify(p, ok), TagOutcome.Valid(p.named, "k-2026-10", Some(iat)))
    assert(token.length < 400, token.length.toString)
  }

  test("a token of 1,025 characters is malformed; 1,024 is read") {
    assertEquals(Tag.parse("a" * 1025), Left(Reasons.Malformed))
    assertEquals(Tag.parse("a" * 1024), Left(Reasons.Malformed)) // read, and not three segments
  }

  test("a token with a + or a / or a space is malformed") {
    assertEquals(Tag.parse(token.replaceFirst("\\.", "+")), Left(Reasons.Malformed))
    assertEquals(Tag.parse(token + "/"), Left(Reasons.Malformed))
    assertEquals(Tag.parse("a b.c.d"), Left(Reasons.Malformed))
    assertEquals(Tag.parse("nonsense"), Left(Reasons.Malformed))
  }

  test("a header naming HS256, or no eo-tag type, is malformed") {
    val payload = """{"v":1,"net":"n","aff":"a","lnk":"l","iat":1761350400}"""
    assertEquals(Tag.parse(forged("""{"alg":"HS256","kid":"k","typ":"eo-tag"}""", payload)), Left(Reasons.Malformed))
    assertEquals(Tag.parse(forged("""{"alg":"EdDSA","kid":"k","typ":"JWT"}""", payload)), Left(Reasons.Malformed))
    assert(Tag.parse(forged("""{"alg":"EdDSA","kid":"k","typ":"eo-tag"}""", payload)).isRight)
  }

  test("six sub-ids, a campaign of 65 characters, a missing field or another version are malformed") {
    val h = """{"alg":"EdDSA","kid":"k","typ":"eo-tag"}"""
    assertEquals(Tag.parse(forged(h, """{"v":1,"net":"n","aff":"a","lnk":"l","sub":["1","2","3","4","5","6"],"iat":1}""")), Left(Reasons.Malformed))
    assert(Tag.parse(forged(h, """{"v":1,"net":"n","aff":"a","lnk":"l","sub":["1","2","3","4","5"],"iat":1}""")).isRight)
    assertEquals(Tag.parse(forged(h, s"""{"v":1,"net":"n","aff":"a","lnk":"l","cmp":"${"c" * 65}","iat":1}""")), Left(Reasons.Malformed))
    assert(Tag.parse(forged(h, s"""{"v":1,"net":"n","aff":"a","lnk":"l","cmp":"${"c" * 64}","iat":1}""")).isRight)
    assertEquals(Tag.parse(forged(h, """{"v":1,"net":"n","aff":"a","iat":1}""")), Left(Reasons.Malformed))
    assertEquals(Tag.parse(forged(h, """{"v":2,"net":"n","aff":"a","lnk":"l","iat":1}""")), Left(Reasons.Malformed))
    assertEquals(Tag.parse(forged(h, """{"v":1,"net":"n!","aff":"a","lnk":"l","iat":1}""")), Left(Reasons.Malformed))
  }

  test("a UUID, in upper case too, is a legacy tag; a token is not") {
    assert(Tag.isLegacy("3f2b1c4d-aaaa-4bbb-8ccc-0123456789ab"))
    assert(Tag.isLegacy("3F2B1C4D-AAAA-4BBB-8CCC-0123456789AB"))
    assert(!Tag.isLegacy(token))
  }

  test("each reason, in research R6's order") {
    val p = parsed()
    assertEquals(refused(Tag.verify(p, ok.copy(networkKnown = false))), Reasons.UnknownNetwork)
    assertEquals(refused(Tag.verify(p, ok.copy(keys = None))), Reasons.KeyUnreachable)
    assertEquals(refused(Tag.verify(p, ok.copy(keys = Some(Map("k-old" -> x))))), Reasons.WithdrawnKey)
    val (other, _) = Signing.generate()
    assertEquals(refused(Tag.verify(p, ok.copy(keys = Some(Map("k-2026-10" -> other))))), Reasons.BadSignature)
    assertEquals(refused(Tag.verify(p, ok.copy(revokedLinks = Set("lnk-01")))), Reasons.RevokedLink)
    assertEquals(refused(Tag.verify(p, ok.copy(revokedAffiliates = Set("aff-7f3c")))), Reasons.RevokedAffiliate)
    assertEquals(refused(Tag.verify(p, ok.copy(window = Some(iat.plus(Duration.ofDays(31)) -> month)))), Reasons.Stale)
    // an unknown network wins over everything after it
    assertEquals(refused(Tag.verify(p, Tag.Context(networkKnown = false, keys = None, revokedLinks = Set("lnk-01")))), Reasons.UnknownNetwork)
  }

  test("a tag issued exactly 30 days before registration binds; one second older is stale") {
    val p = parsed()
    assert(Tag.verify(p, ok.copy(window = Some(iat.plus(month) -> month))).isInstanceOf[TagOutcome.Valid])
    assertEquals(refused(Tag.verify(p, ok.copy(window = Some(iat.plus(month).plusSeconds(1) -> month)))), Reasons.Stale)
  }

  test("a tampered payload fails its signature") {
    val Array(h, _, s) = token.split('.')
    val payload = b64(Json.render(Json.obj("v" -> 1, "net" -> "programme", "aff" -> "aff-evil", "lnk" -> "lnk-01", "iat" -> iat.getEpochSecond)))
    assertEquals(refused(Tag.verify(parsed(s"$h.$payload.$s"), ok)), Reasons.BadSignature)
  }

  test("the legacy rule: listed binds to the programme; unlisted or uncached is legacy_unknown; after the transition legacy") {
    val uuid = "3F2B1C4D-AAAA-4BBB-8CCC-0123456789AB"
    val doc  = Some(Map(uuid.toLowerCase -> ("aff-1", "lnk-9")))
    assertEquals(Tag.resolveLegacy(uuid, doc, "programme", transitionOver = false),
      TagOutcome.Valid(Named("programme", "aff-1", "lnk-9", None, Vector.empty), "legacy", None))
    assertEquals(refused(Tag.resolveLegacy("3f2b1c4d-aaaa-4bbb-8ccc-000000000000", doc, "programme", transitionOver = false)), Reasons.LegacyUnknown)
    assertEquals(refused(Tag.resolveLegacy(uuid, None, "programme", transitionOver = false)), Reasons.LegacyUnknown)
    assertEquals(refused(Tag.resolveLegacy(uuid, doc, "programme", transitionOver = true)), Reasons.Legacy)
  }
