package io.eitheror.casino.programme.it

import io.eitheror.casino.attribution.{Reasons, TagOutcome}
import io.eitheror.casino.attribution.tag.Tag

import scala.jdk.CollectionConverters.*

/**
 * A brand's side of the documents (spec 012): it fetches the programme's documents as a public client and
 * verifies a tag with them, calling the programme for nothing else. `Tag` is 012's own verifier.
 */
final class BrandSide(kit: Kit):
  def keys: Map[String, String] =
    val doc = kit.request("GET", "/v1/programme/documents/keys", headers = kit.fromInternet)
    assert(doc.status == 200, doc.text)
    doc.json.get("keys").elements().asScala.map(k => k.get("kid").asText -> k.get("x").asText).toMap

  def revocations: (Set[String], Set[String]) =
    val doc = kit.request("GET", "/v1/programme/documents/revocations", headers = kit.fromInternet).json
    (doc.get("links").elements().asScala.map(_.asText).toSet, doc.get("affiliates").elements().asScala.map(_.asText).toSet)

  def verify(tag: String, keys: Map[String, String], revokedLinks: Set[String] = Set.empty,
      revokedAffiliates: Set[String] = Set.empty): TagOutcome =
    Tag.parse(tag).fold(TagOutcome.Refused(_), p => Tag.verify(p, Tag.Context(networkKnown = true, Some(keys), revokedLinks, revokedAffiliates)))

object BrandSide:
  val RevokedLink  = Reasons.RevokedLink
  val WithdrawnKey = Reasons.WithdrawnKey

/** A brand's own key document, its transition ended or not (spec 012's shape), for the legacy withdrawal. */
final class BrandKeys extends FakeServer():
  @volatile var endedOn: Option[String] = None
  def url(brand: String): String = s"$address/v1/casino/brands/$brand/attribution/keys"
  protected def route(r: Received): Answer =
    Answer(200, s"""{"keys":[],"cacheSeconds":3600,"transition":{"firstDay":"2026-11-01","endsAt":"2027-11-01","endedOn":${endedOn.fold("null")(d => s"\"$d\"")},"endedBy":null,"legacyTagsLast30Days":0}}""")
