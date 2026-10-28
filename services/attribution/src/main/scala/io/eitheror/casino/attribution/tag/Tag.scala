package io.eitheror.casino.attribution.tag

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.attribution.{Json, Named, Reasons, TagOutcome}

import java.nio.charset.StandardCharsets.US_ASCII
import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters.*

/**
 * The affiliate tag (contracts/protocol.md; research R2, R6): a JWS compact token under `btag`, signed by
 * the network with Ed25519, or during the transition a bare UUID the programme's legacy tag document
 * lists. Parsing and verifying are pure: the documents a verdict needs are passed in, never fetched here.
 */
object Tag:
  val MaxLength = 1024
  val MaxSubIds = 5

  private val Charset = "[A-Za-z0-9_.-]+".r
  private val Id      = "[A-Za-z0-9_.-]{1,64}".r
  private val Uuid    = "[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}".r

  /** A token read but not yet verified: what it claims, the bytes its signature is over, the signature. */
  final case class Parsed(kid: String, named: Named, iat: Instant, signingInput: Array[Byte], signature: Array[Byte])

  /** The fields a network signs into a token. */
  final case class Fields(network: String, affiliate: String, link: String, campaign: Option[String] = None,
      subIds: Vector[String] = Vector.empty, iat: Instant)

  /** The documents and settings a verdict depends on, as the caller holds them now. */
  final case class Context(
      networkKnown: Boolean,
      keys: Option[Map[String, String]],
      revokedLinks: Set[String] = Set.empty,
      revokedAffiliates: Set[String] = Set.empty,
      window: Option[(Instant, Duration)] = None
  )

  def isLegacy(value: String): Boolean = Uuid.matches(value)

  /** A token's claims, or `malformed` naming nothing more (a tag never fails a registration: FR-005). */
  def parse(value: String): Either[String, Parsed] =
    def bad = Left(Reasons.Malformed)
    if value.length > MaxLength || !Charset.matches(value) then bad
    else
      value.split('.') match
        case Array(h, p, s) if h.nonEmpty && p.nonEmpty && s.nonEmpty =>
          val parsed =
            for
              header    <- Signing.decode(h).flatMap(b => Json.parse(String(b, "UTF-8")))
              payload   <- Signing.decode(p).flatMap(b => Json.parse(String(b, "UTF-8")))
              signature <- Signing.decode(s).filter(_.length == 64)
              kid       <- headerKid(header)
              named     <- claims(payload)
              iat       <- Option(payload.get("iat")).filter(_.isIntegralNumber).map(n => Instant.ofEpochSecond(n.asLong))
            yield Parsed(kid, named, iat, s"$h.$p".getBytes(US_ASCII), signature)
          parsed.toRight(Reasons.Malformed)
        case _ => bad

  private def headerKid(header: JsonNode): Option[String] =
    def text(f: String) = Option(header.get(f)).filter(_.isTextual).map(_.asText)
    for
      _   <- text("alg").filter(_ == "EdDSA")
      _   <- text("typ").filter(_ == "eo-tag")
      kid <- text("kid").filter(Id.matches)
    yield kid

  private def claims(payload: JsonNode): Option[Named] =
    def text(f: String) = Option(payload.get(f)).filter(_.isTextual).map(_.asText)
    val sub = Option(payload.get("sub"))
    for
      _        <- Option(payload.get("v")).filter(n => n.isIntegralNumber && n.asInt == 1)
      net      <- text("net").filter(Id.matches)
      aff      <- text("aff").filter(Id.matches)
      lnk      <- text("lnk").filter(Id.matches)
      campaign <- payload.get("cmp") match
                    case null                                                  => Some(None)
                    case n if n.isTextual && n.asText.length >= 1 && n.asText.length <= 64 => Some(Some(n.asText))
                    case _                                                     => None
      subIds   <- sub match
                    case None                => Some(Vector.empty)
                    case Some(n) if n.isArray =>
                      val all = n.elements.asScala.toVector
                      Option.when(all.size <= MaxSubIds && all.forall(e => e.isTextual && e.asText.nonEmpty && e.asText.length <= 64))(
                        all.map(_.asText))
                    case _                   => None
    yield Named(net, aff, lnk, campaign, subIds)

  /**
   * The verdict on a parsed token, in research R6's order: the network, the key, the signature, the
   * revocations, then (at registration only) the window. `keys` is none when no key document is cached.
   */
  def verify(parsed: Parsed, context: Context): TagOutcome =
    import TagOutcome.*
    if !context.networkKnown then Refused(Reasons.UnknownNetwork)
    else
      context.keys match
        case None => Refused(Reasons.KeyUnreachable)
        case Some(keys) =>
          keys.get(parsed.kid) match
            case None => Refused(Reasons.WithdrawnKey)
            case Some(key) if !Signing.verify(key, parsed.signingInput, parsed.signature) => Refused(Reasons.BadSignature)
            case Some(_) if context.revokedLinks.contains(parsed.named.link) => Refused(Reasons.RevokedLink)
            case Some(_) if context.revokedAffiliates.contains(parsed.named.affiliate) => Refused(Reasons.RevokedAffiliate)
            case Some(_) if context.window.exists((registeredAt, window) => parsed.iat.isBefore(registeredAt.minus(window))) =>
              Refused(Reasons.Stale)
            case Some(_) => Valid(parsed.named, parsed.kid, Some(parsed.iat))

  /**
   * A bare UUID (research R16): refused `legacy` once the transition has ended; else the programme's tag
   * when its legacy tag document lists it, `legacy_unknown` when it does not or none is cached.
   */
  def resolveLegacy(uuid: String, legacy: Option[Map[String, (String, String)]], programme: String,
      transitionOver: Boolean): TagOutcome =
    if transitionOver then TagOutcome.Refused(Reasons.Legacy)
    else
      legacy.flatMap(_.get(uuid.toLowerCase)) match
        case Some((affiliate, link)) => TagOutcome.Valid(Named(programme, affiliate, link, None, Vector.empty), "legacy", None)
        case None                    => TagOutcome.Refused(Reasons.LegacyUnknown)

  /** A token for `fields` under the network's key `kid`: for the kit, the fake network and the operator's tests. */
  def mint(fields: Fields, kid: String, privateKey: String): String =
    val header  = Json.obj("alg" -> "EdDSA", "kid" -> kid, "typ" -> "eo-tag")
    val payload = Json.obj("v" -> 1, "net" -> fields.network, "aff" -> fields.affiliate, "lnk" -> fields.link,
      "cmp" -> fields.campaign, "iat" -> fields.iat.getEpochSecond)
    if fields.subIds.nonEmpty then Json.set(payload, "sub", fields.subIds)
    val input = s"${Signing.encode(Json.render(header).getBytes("UTF-8"))}.${Signing.encode(Json.render(payload).getBytes("UTF-8"))}"
    s"$input.${Signing.encode(Signing.sign(privateKey, input.getBytes(US_ASCII)))}"
