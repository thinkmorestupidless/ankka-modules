package io.eitheror.casino.programme.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.contract.facts.{AffiliatesAttribution, AttributionFact}
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.accounts.Secrets
import io.eitheror.casino.programme.api.BackofficeAnswers.{PlanBody, ProgrammeBody, SettingsBody, given}
import io.eitheror.casino.programme.api.ManagerAnswers.{ManagerAnswer, ManagerBody, given}
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.brands.{AddBrand, BrandProgramme, BrandState, Brands, Plan, UpdateBrand}
import io.eitheror.casino.programme.intake.{Inbox, InboxRow, Intake, IntakeCalls, Received}
import io.eitheror.casino.programme.ledger.Ledger
import io.eitheror.casino.programme.settlement.Payouts
import io.eitheror.casino.programme.accounts.{Membership, Memberships}

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.time.{Instant, YearMonth, ZoneId}
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

/** The shapes of the programme's own brand routes (FR-040, FR-042) and the webhook's answer (contracts/inbound.md). */
object HostingAnswers:
  final case class BrandBody(brandId: Option[String] = None, origin: Option[String] = None, name: Option[String] = None,
      reportingZone: Option[String] = None, keyDocumentUrl: Option[String] = None, settings: Option[SettingsBody] = None,
      programme: Option[ProgrammeBody] = None, defaultPlan: Option[PlanBody] = None, firstManager: Option[ManagerBody] = None)
  final case class BrandPatch(name: Option[String] = None, keyDocumentUrl: Option[String] = None, retired: Option[Boolean] = None)
  final case class BrandAnswer(brandId: String, origin: String, name: Option[String], reportingZone: String,
      keyDocumentUrl: Option[String], defaultPlanId: Option[String], retiredAt: Option[Instant], addedBy: Option[String],
      addedAt: Option[Instant], duplicates: Long, mismatches: Long)
  /** The secret appears here once: it is kept in the secret store and never read back by a route. */
  final case class BrandAdded(brand: BrandAnswer, webhookUrl: String, webhookSecret: String, defaultPlanId: Option[String],
      firstManager: Option[ManagerAnswer])
  final case class InboundAnswer(outcome: String)
  final case class Usage(brandId: String, month: String, reportingZone: String, from: Instant, to: Instant,
      factsReceived: Map[String, Int], activeMemberships: Map[String, Int], commissionRecorded: Map[String, Long],
      commissionPaid: Map[String, Long])

  given brandBody: JsonValueCodec[BrandBody]               = ProgrammeCodecs.wire[BrandBody]
  given brandPatch: JsonValueCodec[BrandPatch]             = ProgrammeCodecs.wire[BrandPatch]
  given brandAnswer: JsonValueCodec[BrandAnswer]           = ProgrammeCodecs.wire[BrandAnswer]
  given brandAnswers: JsonValueCodec[Vector[BrandAnswer]]  = ProgrammeCodecs.wire[Vector[BrandAnswer]]
  given brandAdded: JsonValueCodec[BrandAdded]             = ProgrammeCodecs.wire[BrandAdded]
  given inboundAnswer: JsonValueCodec[InboundAnswer]       = ProgrammeCodecs.wire[InboundAnswer]
  given usage: JsonValueCodec[Usage]                       = ProgrammeCodecs.wire[Usage]

  def brand(b: BrandState): BrandAnswer =
    BrandAnswer(b.slug, b.origin, b.name, b.reportingZone, b.keyDocumentUrl, b.defaultPlan, b.retired, b.addedBy, b.addedAt, b.duplicates,
      b.mismatches)

  /** What a fact without an amount counts under in `factsReceived`. */
  val NoCurrency = "none"

import HostingAnswers.{*, given}

/** A brand elsewhere or hosted delivers its facts here (contracts/inbound.md *A brand elsewhere: its webhook*). */
final class Inbound(api: Api):
  def receive(slug: String, body: Array[Byte], header: String => Option[String]): Respond[Bytes] =
    val brand = api.brand(slug)
    if !brand.byWebhook || !brand.live then refuse(404, Codes.BrandUnknown, slug)
    else if body.length > Inbound.MaxBody then refuse(413, "too_large", s"a fact is at most ${Inbound.MaxBody} bytes")
    else if !header("X-Attribution-Protocol").map(_.trim).contains("1") then refuse(400, "protocol", "X-Attribution-Protocol is 1")
    else
      Secrets(api.secrets).brandSecret(slug, Secrets.WebhookSecret) match
        case Left(e) => refuse(503, "store_unavailable", e.reason)
        case Right(secret) if !secret.exists(s => Inbound.verifies(body, s, header("X-Attribution-Signature"))) =>
          refuse(401, "signature", "X-Attribution-Signature does not verify")
        case Right(_) =>
          Try(readFromArray[AttributionFact](body)(using AttributionFact.given_JsonValueCodec_AttributionFact)).toOption match
            case None => refuse(400, "fact", "the body is not a fact of affiliates.attribution")
            case Some(fact) if !header("X-Attribution-Fact-Id").contains(fact.factId) =>
              refuse(400, "fact_id", "X-Attribution-Fact-Id is not the body's factId")
            case Some(fact) =>
              Intake(IntakeCalls.of(api.components), api.now).handle(fact, s"webhook:$slug", slug) match
                case Received.Mismatched  => answer(202, InboundAnswer("mismatched"))
                case Received.Duplicate   => ok(InboundAnswer("duplicate"))
                case Received.Recorded(_) => ok(InboundAnswer("new"))

object Inbound:
  val MaxBody: Int = 256 * 1024

  /** `sha256=<hex>` of HMAC-SHA256(body, secret), compared in constant time. */
  def verifies(body: Array[Byte], secret: String, signature: Option[String]): Boolean =
    signature.map(_.trim).filter(_.startsWith("sha256=")).flatMap(s => Try(HexFormat.of().parseHex(s.drop(7))).toOption).exists { sent =>
      MessageDigest.isEqual(sign(body, secret), sent)
    }

  def sign(body: Array[Byte], secret: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"))
    mac.doFinal(body)

/**
 * Brands added by an operator of the installation (FR-040) and their usage (FR-042). Adding one makes
 * the brand, then its settings, programme page, default plan and first manager through the manager's
 * own routes' code, then the webhook secret, answered once.
 */
final class Hosting(api: Api):
  private val backoffice = Backoffice(api)
  private val managers   = Managers(api)
  private val random     = SecureRandom()
  private val Slug       = "[a-z0-9][a-z0-9-]{0,62}".r

  def list: Respond[Bytes] =
    ok(api.views.forView(Brands).ask(Brands.every).map(HostingAnswers.brand))

  /** The steps after the brand is made: a failure answers what the manager's route would, the brand made so far kept. */
  private def step(r: Respond[Bytes])(next: => Respond[Bytes]): Respond[Bytes] = if r.status < 300 then next else r

  def add(b: BrandBody, by: String): Respond[Bytes] =
    val origin = b.origin.getOrElse(BrandProgramme.Hosted)
    b.brandId.map(_.trim) match
      case None                                   => invalid("brandId is required")
      case Some(slug) if !Slug.matches(slug)      => invalid("brandId is lower case letters, digits and dashes")
      case Some(_) if b.defaultPlan.exists(_.planName.forall(_.trim.isEmpty)) => invalid("defaultPlan.planName is required")
      case Some(_) if b.firstManager.exists(_.email.forall(_.trim.isEmpty))   => invalid("firstManager.email is required")
      case Some(slug) if api.brand(slug).exists   => refuse(409, "brand_exists", slug)
      case Some(slug) =>
        val zone = b.reportingZone.getOrElse("UTC")
        if Try(ZoneId.of(zone)).isFailure then invalid(s"reportingZone: '$zone' is not a time zone")
        else
          val entity = api.components.forKeyValueEntity(EntityId(slug))
          entity.call(BrandProgramme.add).invoke(AddBrand(slug, origin, b.name, zone, by, api.now(), b.keyDocumentUrl)): Unit
          val planId = b.defaultPlan.map(p => Plan.idOf(slug, p.planName.get.trim))
          step(b.settings.fold(noContent)(backoffice.putSettings(slug, _, by))) {
            step(b.defaultPlan.fold(noContent)(backoffice.createPlan(slug, _, by))) {
              val page = b.programme.getOrElse(ProgrammeBody()).copy(defaultPlanId = planId.orElse(b.programme.flatMap(_.defaultPlanId)))
              step(backoffice.putProgramme(slug, page, by)) {
                val manager = b.firstManager.map(managers.add(slug, _, by))
                manager.filter(_.status >= 300).getOrElse {
                  val secret = { val bytes = new Array[Byte](32); random.nextBytes(bytes); HexFormat.of().formatHex(bytes) }
                  Secrets(api.secrets).putBrandSecret(slug, Secrets.WebhookSecret, secret) match
                    case Left(e) => refuse(503, "store_unavailable", e.reason)
                    case Right(_) =>
                      audit(slug, "BRAND_ADDED", by)
                      answer(201, BrandAdded(HostingAnswers.brand(api.brand(slug)), s"${api.settings.publicUrl}/v1/programme/inbound/$slug",
                        secret, planId, manager.map(r => readFromArray[ManagerAnswer](r.body.body))))
                }
              }
            }
          }

  def patch(slug: String, p: BrandPatch, by: String): Respond[Bytes] =
    val b = api.brand(slug)
    if !b.exists then refuse(404, Codes.BrandUnknown, slug)
    else if p.retired.contains(false) && b.retired.isDefined then refuse(409, "brand_retired", "a retired brand stays retired")
    else
      val entity = api.components.forKeyValueEntity(EntityId(slug))
      if p.name.isDefined || p.keyDocumentUrl.isDefined then
        entity.call(BrandProgramme.update).invoke(UpdateBrand(name = p.name, keyDocumentUrl = p.keyDocumentUrl)): Unit
      if p.retired.contains(true) then entity.call(BrandProgramme.retire).invoke(api.now()): Unit
      audit(slug, if p.retired.contains(true) then "BRAND_RETIRED" else "BRAND_CHANGED", by)
      ok(HostingAnswers.brand(api.brand(slug)))

  /**
   * FR-042: the brand's calendar month in its reporting zone. Facts received per currency (`none` for a
   * fact with no amount), memberships active in the month per their plan's CPA currency (decided
   * 2026-11-02), commission recorded per currency (payable facts recorded in the month) and commission
   * paid per currency (payouts paid in the month). No fee.
   */
  def usage(slug: String, month: Option[String]): Respond[Bytes] =
    val b = api.brand(slug)
    if !b.exists then refuse(404, Codes.BrandUnknown, slug)
    else
      month.flatMap(m => Try(YearMonth.parse(m)).toOption) match
        case None => refuse(400, Codes.InvalidPeriod, "month is yyyy-MM")
        case Some(ym) =>
          val from = ym.atDay(1).atStartOfDay(b.zone).toInstant
          val to   = ym.plusMonths(1).atDay(1).atStartOfDay(b.zone).toInstant
          def within(at: Instant) = !at.isBefore(from) && at.isBefore(to)
          val facts = api.views.forView(Inbox).ask(Inbox.byBrandAndDays, "brand" -> slug, "from" -> from.minusSeconds(86_400).toString.take(10),
            "to" -> to.plusSeconds(86_400).toString.take(10)).filter(_.receivedAt.exists(within))
          val active = api.views.forView(Memberships).ask(Memberships.byBrand, "brand" -> slug)
            .filter(m => m.active && m.since.forall(_.isBefore(to)))
          val recorded = api.views.forView(Ledger).ask(Ledger.ofBrand, "brand" -> slug, "from" -> ym.minusMonths(1).toString,
            "to" -> ym.plusMonths(1).toString).filter(r => r.kind.payable && within(r.recordedAt))
          val paid = api.views.forView(Payouts).ask(Payouts.byBrand, "brand" -> slug).filter(_.paidAt.exists(within))
          ok(Usage(slug, ym.toString, b.reportingZone, from, to,
            facts.groupMapReduce(_.currency.getOrElse(NoCurrency))(_ => 1)(_ + _),
            active.groupMapReduce(m => m.plan.map(api.plan(slug, _)).filter(_.exists).fold(NoCurrency)(_.cpaCurrency))(_ => 1)(_ + _),
            recorded.groupMapReduce(_.currency)(_.amount)(_ + _),
            paid.groupMapReduce(_.currency)(_.total)(_ + _)))

  private def audit(slug: String, action: String, by: String): Unit =
    api.components.forKeyValueEntity(EntityId(io.eitheror.casino.programme.activity.AuditEntry.newId()))
      .call(io.eitheror.casino.programme.activity.AuditEntry.write)
      .invoke(io.eitheror.casino.programme.activity.Audit("", Some(slug), action, by, api.now())): Unit
