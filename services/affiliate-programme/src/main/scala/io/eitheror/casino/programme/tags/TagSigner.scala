package io.eitheror.casino.programme.tags

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker, named}

import java.nio.charset.StandardCharsets.US_ASCII
import java.security.{KeyFactory, KeyPairGenerator, Signature}
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * The programme's tags (spec 012 contracts/protocol.md *The tag*): a JWS compact token, `EdDSA` over
 * Ed25519, header `typ eo-tag` and the key's `kid`, payload version 1 naming the network `programme`,
 * the affiliate, the link, optionally the campaign and up to five sub-ids, and the issue time. A tag is
 * at most 1,024 characters; a link whose tag would be longer is refused.
 */
object TagSigner:
  val Network   = "programme"
  val MaxLength = 1024
  val MaxSubIds = 5

  private val enc = Base64.getUrlEncoder.withoutPadding
  private val dec = Base64.getUrlDecoder
  private val X509PrefixLength = 12

  final case class Header(alg: String, kid: String, typ: String)
  final case class Payload(v: Int, net: String, aff: String, lnk: String, cmp: Option[String], @named("sub") subIds: Vector[String], iat: Long)

  private given JsonValueCodec[Header]  = JsonCodecMaker.make
  private given JsonValueCodec[Payload] = JsonCodecMaker.make(CodecMakerConfig.withTransientNone(true).withTransientEmpty(true))

  /** A new key pair: the public `x` (32 bytes) and the private key (PKCS #8), each base64url without padding. */
  def generate(): (String, String) =
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    (enc.encodeToString(pair.getPublic.getEncoded.drop(X509PrefixLength)), enc.encodeToString(pair.getPrivate.getEncoded))

  /** The tag, or why there is none: too many sub-ids, a campaign or sub-id outside 1 to 64 characters, or too long. */
  def sign(affiliate: String, link: String, campaign: Option[String], subIds: Vector[String], iat: Instant,
      kid: String, privateKey: String): Either[String, String] =
    if subIds.size > MaxSubIds then Left(s"at most $MaxSubIds sub-ids")
    else if (campaign.toVector ++ subIds).exists(v => v.isEmpty || v.length > 64) then Left("a campaign or sub-id is 1 to 64 characters")
    else
      val header  = enc.encodeToString(writeToArray(Header("EdDSA", kid, "eo-tag")))
      val payload = enc.encodeToString(writeToArray(Payload(1, Network, affiliate, link, campaign, subIds, iat.getEpochSecond)))
      val input   = s"$header.$payload"
      val s       = Signature.getInstance("Ed25519")
      s.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(dec.decode(privateKey))))
      s.update(input.getBytes(US_ASCII))
      val token = s"$input.${enc.encodeToString(s.sign())}"
      Either.cond(token.length <= MaxLength, token, s"the tag would be ${token.length} characters; at most $MaxLength")
