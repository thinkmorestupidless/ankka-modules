package io.eitheror.casino.programme.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.links.*
import io.eitheror.casino.programme.tags.Tags

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** What the link and code routes accept and answer (contracts/routes.md M11, M12, M28 to M30, O35, O36). */
object TrackingAnswers:
  final case class LinkBody(brandId: Option[String] = None, affiliateId: Option[String] = None, campaign: Option[String] = None,
      landingPath: Option[String] = None, utmSource: Option[String] = None, utmMedium: Option[String] = None,
      utmCampaign: Option[String] = None, subIds: Option[Vector[String]] = None)
  final case class CodeBody(code: Option[String] = None, btag: Option[String] = None, offer: Option[String] = None,
      brandId: Option[String] = None)

  /**
   * A link, `btag` its id as the original's was; `tag` the signed tag, and `landingUrl` the brand's
   * landing path carrying it, relative to the brand's site. Its counts are folded from the facts (FR-024).
   */
  final case class LinkAnswer(btag: String, tag: Option[String], landingUrl: Option[String], affiliateId: String, brandId: String,
      campaign: Option[String], landingPath: Option[String], utmSource: Option[String], utmMedium: Option[String],
      utmCampaign: Option[String], subIds: Vector[String], clicks: Long, registrations: Long, createdAt: Option[Instant],
      revokedAt: Option[Instant])
  final case class CodeAnswer(codeId: String, code: String, affiliateId: String, btag: String, brandId: String, offer: Option[String],
      tag: String, status: String, createdAt: Option[Instant])

  def landingUrl(l: LinkState): Option[String] =
    l.tag.map { tag =>
      val path  = l.landingPath.filter(_.nonEmpty).getOrElse("/")
      val query = (("btag" -> tag) +: l.utm.toVector.sorted.map((k, v) => Utm.getOrElse(k, k) -> v))
        .map((k, v) => s"$k=${URLEncoder.encode(v, UTF_8)}").mkString("&")
      s"$path${if path.contains('?') then "&" else "?"}$query"
    }

  private val Utm = Map("utmSource" -> "utm_source", "utmMedium" -> "utm_medium", "utmCampaign" -> "utm_campaign")

  def code(c: BonusCodeState): CodeAnswer =
    CodeAnswer(c.code, c.code, c.affiliate, c.link, c.brand, c.offer, c.tag, c.status.getOrElse(""), c.createdAt)

  given linkBody: JsonValueCodec[LinkBody]            = ProgrammeCodecs.wire[LinkBody]
  given codeBody: JsonValueCodec[CodeBody]            = ProgrammeCodecs.wire[CodeBody]
  given linkAnswer: JsonValueCodec[LinkAnswer]        = ProgrammeCodecs.wire[LinkAnswer]
  given linkAnswers: JsonValueCodec[Vector[LinkAnswer]] = ProgrammeCodecs.wire[Vector[LinkAnswer]]
  given codeAnswer: JsonValueCodec[CodeAnswer]        = ProgrammeCodecs.wire[CodeAnswer]
  given codeAnswers: JsonValueCodec[Vector[CodeAnswer]] = ProgrammeCodecs.wire[Vector[CodeAnswer]]

import TrackingAnswers.{*, given}

/** The links and bonus codes an affiliate holds at a brand, each carrying the programme's tag (US5, FR-023 to FR-025). */
final class Tracking(api: Api):
  def links(affiliate: Option[String], brand: String): Respond[Bytes] =
    val rows = affiliate.fold(api.views.forView(Links).ask(Links.ofBrand, "brand" -> brand))(a =>
      api.views.forView(Links).ask(Links.ofAffiliate, "affiliate" -> a, "brand" -> brand))
    ok(rows.map(answerOf))

  /** M12 and O36: a new link with its tag, for an affiliate with an active membership at the brand. */
  def createLink(affiliate: String, brand: String, b: LinkBody, by: String): Respond[Bytes] =
    val subIds = b.subIds.getOrElse(Vector.empty)
    if !api.membership(affiliate, brand).active then refuse(409, Codes.NotAMember, brand)
    else
      val id  = Link.newId()
      val now = api.now()
      Tags.sign(api.components, api.secrets, affiliate, id, b.campaign, subIds, now) match
        case Left(problem) if problem.startsWith("at most") || problem.startsWith("a campaign") || problem.startsWith("the tag would") =>
          invalid(problem)
        case Left(problem) => refuse(503, "store_unavailable", problem)
        case Right(tag) =>
          val utm = Map("utmSource" -> b.utmSource, "utmMedium" -> b.utmMedium, "utmCampaign" -> b.utmCampaign).collect { case (k, Some(v)) => k -> v }
          val link = api.components.forKeyValueEntity(EntityId(id)).call(Link.createLink)
            .invoke(CreateLink(id, affiliate, brand, b.campaign, b.landingPath.map(_.trim).filter(_.nonEmpty), utm, subIds, Some(tag), None, by, now))
          answer(201, answerOf(link))

  def codes(affiliate: String, brand: String): Respond[Bytes] =
    ok(api.views.forView(BonusCodes).ask(BonusCodes.ofAffiliate, "affiliate" -> affiliate, "brand" -> brand).map(TrackingAnswers.code))

  /**
   * M29 (FR-025): a code on one of the affiliate's links at the brand, its tag naming the affiliate and
   * that link; unique at the brand whatever its case, one of two at once winning.
   */
  def createCode(affiliate: String, brand: String, b: CodeBody): Respond[Bytes] =
    val text = b.code.map(_.trim).getOrElse("")
    val link = b.btag.map(_.trim).filter(_.nonEmpty)
    if text.isEmpty || link.isEmpty then refuse(400, "code_and_btag_required")
    else if !BonusCode.Text.matches(text) then invalid("a code is 1 to 64 letters, digits, '_' or '-'")
    else if !api.membership(affiliate, brand).active then refuse(409, Codes.NotAMember, brand)
    else
      val l = api.components.forKeyValueEntity(EntityId(link.get)).call(Link.get).invoke()
      if !l.live || l.affiliate != affiliate || l.brand != brand then refuse(400, "btag_not_found")
      else
        Tags.sign(api.components, api.secrets, affiliate, l.id, l.campaign, l.subIds, api.now()) match
          case Left(problem) => refuse(503, "store_unavailable", problem)
          case Right(tag) =>
            val c = api.components.forKeyValueEntity(BonusCode.entityId(brand, text)).call(BonusCode.claim)
              .invoke(ClaimCode(text, brand, affiliate, l.id, b.offer.filter(_.nonEmpty), tag, api.now()))
            answer(201, TrackingAnswers.code(c))

  /** M30: the affiliate's own code revoked, its text free again (FR-025). */
  def revokeCode(affiliate: String, brand: String, code: String): Respond[Bytes] =
    api.components.forKeyValueEntity(BonusCode.entityId(brand, code)).call(BonusCode.revoke).invoke(RevokeCode(Some(affiliate), api.now())): Unit
    ok(Answers.Ok(true))

  private def answerOf(l: LinkState): LinkAnswer =
    val counts = LinkStats.of(api.views, l.id)
    LinkAnswer(l.id, l.tag, TrackingAnswers.landingUrl(l), l.affiliate, l.brand, l.campaign, l.landingPath, l.utm.get("utmSource"),
      l.utm.get("utmMedium"), l.utm.get("utmCampaign"), l.subIds, counts.clicks, counts.registrations, l.createdAt, l.revokedAt)
