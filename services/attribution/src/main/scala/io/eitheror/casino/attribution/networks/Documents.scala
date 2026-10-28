package io.eitheror.casino.attribution.networks

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.attribution.Json
import io.eitheror.casino.attribution.Json.*
import io.eitheror.casino.attribution.tag.Signing

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * The four documents a network publishes (contracts/protocol.md; research R3), parsed. Each says how long
 * it may be cached; one over 1 MiB, not JSON, or without its cache field is refused naming why, and the
 * cache keeps what it had.
 */
object Documents:
  val MaxBytes: Int = 1024 * 1024

  /** The brand's transition, carried only in the brand's own key document (research R16). */
  final case class Transition(firstDay: String, endsAt: Option[String], endedOn: Option[String], endedBy: Option[String],
      legacyTagsLast30Days: Long)

  final case class KeyDocument(keys: Map[String, String], cacheSeconds: Long, transition: Option[Transition] = None)
  final case class RevocationList(links: Set[String], affiliates: Set[String], refreshSeconds: Long, asOf: Option[Instant])
  final case class LegacyTagDocument(tags: Map[String, (String, String)], version: Long, cacheSeconds: Long)
  final case class CodeDocument(brand: String, version: Long, at: Option[Instant], codes: Map[String, (String, String)],
      cacheSeconds: Long)

  /** The four kinds, as the cache and the refresh name them. */
  object Kind:
    val Keys        = "keys"
    val Revocations = "revocations"
    val Legacy      = "legacy"
    val Codes       = "codes"
    val all: Vector[String] = Vector(Keys, Revocations, Legacy, Codes)

  private def json(body: String): Either[String, JsonNode] =
    if body.getBytes("UTF-8").length > MaxBytes then Left("the document is over 1 MiB")
    else Json.parse(body).filter(_.isObject).toRight("the document is not a JSON object")

  private def seconds(node: JsonNode, field: String): Either[String, Long] =
    node.long(field).filter(_ >= 0).toRight(s"the document has no $field")

  def keyDocument(body: String): Either[String, KeyDocument] =
    for
      node  <- json(body)
      cache <- seconds(node, "cacheSeconds")
      keys  <- node.objects("keys").foldLeft[Either[String, Map[String, String]]](Right(Map.empty)) { (acc, k) =>
                 acc.flatMap { m =>
                   (k.text("kid"), k.text("kty"), k.text("crv"), k.text("x")) match
                     case (Some(kid), Some("OKP"), Some("Ed25519"), Some(x)) if Signing.publicKeyFromX(x).isDefined => Right(m + (kid -> x))
                     case (Some(kid), _, crv, _) if !crv.contains("Ed25519") => Left(s"key $kid is not an Ed25519 key")
                     case (Some(kid), _, _, _) => Left(s"key $kid is not an OKP key with a 32-byte x")
                     case _ => Left("a key has no kid")
                 }
               }
    yield KeyDocument(keys, cache, Option(node.get("transition")).filter(_.isObject).map(transition))

  private def transition(t: JsonNode): Transition =
    Transition(t.textOr("firstDay", ""), t.text("endsAt"), t.text("endedOn"), t.text("endedBy"), t.long("legacyTagsLast30Days").getOrElse(0L))

  def revocationList(body: String): Either[String, RevocationList] =
    for
      node    <- json(body)
      refresh <- seconds(node, "refreshSeconds")
    yield RevocationList(node.strings("links").toSet, node.strings("affiliates").toSet, refresh,
      node.text("asOf").flatMap(t => Try(Instant.parse(t)).toOption))

  def legacyTagDocument(body: String): Either[String, LegacyTagDocument] =
    for
      node  <- json(body)
      cache <- seconds(node, "cacheSeconds")
      tags  <- Option(node.get("tags")).filter(_.isObject).toRight("the document has no tags")
    yield LegacyTagDocument(
      tags.properties.asScala.toVector.flatMap { e =>
        for
          affiliate <- e.getValue.text("affiliate")
          link      <- e.getValue.text("link")
        yield e.getKey.toLowerCase -> (affiliate, link)
      }.toMap,
      node.long("version").getOrElse(0L), cache)

  def codeDocument(body: String): Either[String, CodeDocument] =
    for
      node  <- json(body)
      cache <- seconds(node, "cacheSeconds")
    yield CodeDocument(node.textOr("brand", ""), node.long("version").getOrElse(0L),
      node.text("at").flatMap(t => Try(Instant.parse(t)).toOption),
      node.objects("codes").flatMap { c =>
        for
          code  <- c.text("code")
          offer <- c.text("offer")
          tag   <- c.text("tag")
        yield code.toLowerCase -> (offer, tag)
      }.toMap,
      cache)

  extension (codes: CodeDocument)
    /** The offer and tag of a code, matched regardless of case. */
    def lookup(code: String): Option[(String, String)] = codes.codes.get(code.trim.toLowerCase)

  /** How long a parsed document may be cached, in seconds. */
  def cacheSeconds(kind: String, body: String): Either[String, Long] = kind match
    case Kind.Keys        => keyDocument(body).map(_.cacheSeconds)
    case Kind.Revocations => revocationList(body).map(_.refreshSeconds)
    case Kind.Legacy      => legacyTagDocument(body).map(_.cacheSeconds)
    case Kind.Codes       => codeDocument(body).map(_.cacheSeconds)
    case other            => Left(s"no document of kind $other")
