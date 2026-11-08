package io.eitheror.casino.programme.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.links.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Instant
import scala.util.Try

/** The documents' shapes, spec 012's contracts/protocol.md *The documents a network publishes* exactly. */
object DocumentShapes:
  final case class Key(kid: String, kty: String, crv: String, x: String)
  final case class KeyDocument(keys: Vector[Key], cacheSeconds: Int)
  final case class RevocationList(asOf: Instant, refreshSeconds: Int, links: Vector[String], affiliates: Vector[String])
  final case class Tagged(affiliate: String, link: String)
  final case class LegacyTagDocument(version: Int, cacheSeconds: Int, tags: Map[String, Tagged])
  final case class Code(code: String, offer: Option[String], tag: String)
  final case class CodeDocument(brand: String, version: Int, at: Instant, cacheSeconds: Int, codes: Vector[Code])

  // Every member is written, empty lists included: a brand reads the shape 012 defines.
  given JsonValueCodec[KeyDocument]       = JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false).withTransientDefault(false))
  given JsonValueCodec[RevocationList]    = JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false).withTransientDefault(false))
  given JsonValueCodec[LegacyTagDocument] = JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false).withTransientDefault(false))
  given JsonValueCodec[CodeDocument]      = JsonCodecMaker.make(CodecMakerConfig.withTransientEmpty(false).withTransientDefault(false))

import DocumentShapes.{*, given}

/**
 * The programme's documents (contracts/routes.md, research R10), public and cacheable, read from the
 * views and the signing keys; a brand fetches them and never calls the programme otherwise.
 */
final class Documents(api: Api):
  def keys: Respond[Bytes] =
    val live = signing.live.map(k => Key(k.kid, "OKP", "Ed25519", k.x))
    cached(Documents.KeysSeconds, KeyDocument(live, Documents.KeysSeconds))

  def revocations: Respond[Bytes] =
    val rows = api.views.forView(Revocations).ask(Revocations.listed)
    val list = RevocationList(signing.revocationsAsOf.getOrElse(api.now()), RevocationsStamp.RefreshSeconds,
      rows.filter(_.kind == RevocationRow.Link).map(_.id).distinct, rows.filter(_.kind == RevocationRow.Affiliate).map(_.id).distinct)
    cached(RevocationsStamp.RefreshSeconds, list)

  /** FR-026: every imported tag, keyed by the UUID in lower case; `404` once withdrawn. */
  def legacyTags: Respond[Bytes] =
    if signing.legacyWithdrawnAt.isDefined then refuse(404, "withdrawn", "the transition has ended")
    else
      val tags = imported.flatMap(l => l.legacyBtag.map(_.toLowerCase -> Tagged(l.affiliate, l.id))).toMap
      cached(Documents.KeysSeconds, LegacyTagDocument(tags.size, Documents.KeysSeconds, tags))

  /** FR-023a: the live codes at the brand; the version is the sum of every code's changes, so it rises with each. */
  def codes(brand: String): Respond[Bytes] =
    if !api.brand(brand).exists then refuse(404, Codes.BrandUnknown, brand)
    else
      val rows = api.views.forView(BonusCodes).ask(BonusCodes.ofBrand, "brand" -> brand)
      val at   = rows.flatMap(_.changedAt).maxOption.getOrElse(api.now())
      cached(Documents.CodesSeconds, CodeDocument(brand, rows.map(_.changes).sum, at, Documents.CodesSeconds,
        rows.filter(_.live).map(c => Code(c.code, c.offer, c.tag))))

  /**
   * FR-026: withdrawn once every brand with an imported tag reports in its own key document that its
   * transition ended; else `409 transition_not_ended` naming the brands not ended or not reached.
   */
  def withdraw(by: String): Respond[Bytes] =
    val brands  = imported.map(_.brand).distinct.sorted
    val pending = brands.filterNot(b => endedAt(b).contains(true))
    if pending.nonEmpty then refuse(409, "transition_not_ended", pending.mkString(","))
    else
      api.components.forKeyValueEntity(EntityId(SigningKeys.Id)).call(SigningKeys.withdrawLegacy).invoke(WithdrawLegacy(by, api.now())): Unit
      ok(Answers.Ok(true))

  /** Whether the brand's key document says its transition ended: none when it cannot be read. */
  private def endedAt(brand: String): Option[Boolean] =
    api.brand(brand).keyDocument(api.settings.attributionBaseUrl).flatMap { url =>
      Try {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(api.settings.outboundTimeout).GET().build()
        val reply   = Documents.http.send(request, HttpResponse.BodyHandlers.ofString())
        if reply.statusCode != 200 then None
        else
          val ended = Documents.mapper.readTree(reply.body).path("transition").path("endedOn")
          Some(ended.isTextual && ended.asText.nonEmpty)
      }.toOption.flatten
    }

  private def imported: Vector[LinkState] = api.views.forView(Links).ask(Links.legacy)

  private def signing: SigningKeysState = api.components.forKeyValueEntity(EntityId(SigningKeys.Id)).call(SigningKeys.get).invoke()

  private def cached[A: JsonValueCodec](seconds: Int, a: A): Respond[Bytes] =
    ok(a).copy(headers = Vector("Cache-Control" -> s"max-age=$seconds"))

object Documents:
  val KeysSeconds  = 3600
  val CodesSeconds = 300

  private val http   = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
  private val mapper = ObjectMapper()
