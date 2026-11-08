package io.eitheror.casino.programme.tags

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}

import java.nio.charset.StandardCharsets.{US_ASCII, UTF_8}
import java.security.{KeyFactory, Signature}
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.{Base64, HexFormat}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * Every tag the programme signs is one spec 012's `attribution` accepts: checked with a copy of
 * `services/attribution/.../tag/Tag.scala`'s parse and verify, against the key document the programme
 * serves (`{kid, kty OKP, crv Ed25519, x}`).
 */
class TagSignerSuite extends munit.FunSuite:

  private val iat = Instant.parse("2026-11-01T10:00:00Z")

  // ── the copy of attribution's verification ────────────────────────────────────────────────────
  private final case class Named(net: String, aff: String, lnk: String, cmp: Option[String], sub: Vector[String])
  private val mapper  = ObjectMapper()
  private val dec     = Base64.getUrlDecoder
  private val Charset = "[A-Za-z0-9_.-]+".r
  private val Id      = "[A-Za-z0-9_.-]{1,64}".r
  private val X509Prefix = HexFormat.of().parseHex("302a300506032b6570032100")

  private def verify(token: String, keys: Map[String, String]): Either[String, Named] =
    if token.length > 1024 || !Charset.matches(token) then Left("malformed")
    else
      token.split('.') match
        case Array(h, p, s) =>
          (for
            header  <- Try(mapper.readTree(String(dec.decode(h), UTF_8))).toOption
            payload <- Try(mapper.readTree(String(dec.decode(p), UTF_8))).toOption
            sig     <- Try(dec.decode(s)).toOption.filter(_.length == 64)
            kid     <- Option.when(text(header, "alg").contains("EdDSA") && text(header, "typ").contains("eo-tag"))(text(header, "kid")).flatten.filter(Id.matches)
            named   <- claims(payload)
            _       <- Option(payload.get("iat")).filter(_.isIntegralNumber)
          yield (kid, named, s"$h.$p".getBytes(US_ASCII), sig)).toRight("malformed").flatMap { (kid, named, input, sig) =>
            keys.get(kid) match
              case None => Left("withdrawn_key")
              case Some(x) =>
                val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(X509Prefix ++ dec.decode(x)))
                val v   = Signature.getInstance("Ed25519")
                v.initVerify(key)
                v.update(input)
                if v.verify(sig) then Right(named) else Left("bad_signature")
          }
        case _ => Left("malformed")

  private def text(n: JsonNode, f: String) = Option(n.get(f)).filter(_.isTextual).map(_.asText)

  private def claims(payload: JsonNode): Option[Named] =
    for
      _   <- Option(payload.get("v")).filter(n => n.isIntegralNumber && n.asInt == 1)
      net <- text(payload, "net").filter(Id.matches)
      aff <- text(payload, "aff").filter(Id.matches)
      lnk <- text(payload, "lnk").filter(Id.matches)
      cmp <- payload.get("cmp") match
               case null => Some(None)
               case n if n.isTextual && n.asText.nonEmpty && n.asText.length <= 64 => Some(Some(n.asText))
               case _ => None
      sub <- payload.get("sub") match
               case null => Some(Vector.empty)
               case n if n.isArray =>
                 val all = n.elements.asScala.toVector
                 Option.when(all.size <= 5 && all.forall(e => e.isTextual && e.asText.nonEmpty && e.asText.length <= 64))(all.map(_.asText))
               case _ => None
    yield Named(net, aff, lnk, cmp, sub)

  // ── the cases ─────────────────────────────────────────────────────────────────────────────────

  private val (x, privateKey) = TagSigner.generate()
  private val keys            = Map("pk-1" -> x)

  test("a signed tag verifies, naming the programme, the affiliate, the link, the campaign and the sub-ids") {
    val tag = TagSigner.sign("aff-1", "lnk-1", Some("autumn"), Vector("s1", "s2"), iat, "pk-1", privateKey).toOption.get
    assertEquals(verify(tag, keys), Right(Named("programme", "aff-1", "lnk-1", Some("autumn"), Vector("s1", "s2"))))
  }

  test("with no campaign and no sub-ids neither field is in the payload") {
    val tag = TagSigner.sign("aff-1", "lnk-1", None, Vector.empty, iat, "pk-1", privateKey).toOption.get
    val payload = mapper.readTree(String(dec.decode(tag.split('.')(1)), UTF_8))
    assert(payload.get("cmp") == null && payload.get("sub") == null, payload.toString)
    assertEquals(verify(tag, keys), Right(Named("programme", "aff-1", "lnk-1", None, Vector.empty)))
  }

  test("a tag under a withdrawn kid, or verified against another key, is refused") {
    val tag = TagSigner.sign("aff-1", "lnk-1", None, Vector.empty, iat, "pk-1", privateKey).toOption.get
    assertEquals(verify(tag, Map("pk-2" -> x)), Left("withdrawn_key"))
    assertEquals(verify(tag, Map("pk-1" -> TagSigner.generate()._1)), Left("bad_signature"))
  }

  test("six sub-ids, an empty campaign or a tag past 1,024 characters is refused at signing") {
    assert(TagSigner.sign("aff-1", "lnk-1", None, Vector.fill(6)("s"), iat, "pk-1", privateKey).isLeft)
    assert(TagSigner.sign("aff-1", "lnk-1", Some(""), Vector.empty, iat, "pk-1", privateKey).isLeft)
    val long = Vector.fill(5)("x" * 64)
    assert(TagSigner.sign("a" * 64, "l" * 64, Some("c" * 64), long, iat, "k" * 64, privateKey).isLeft)
  }
