package io.eitheror.casino.programme.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.accounts.{AffiliateState, MembershipState}
import io.eitheror.casino.programme.brands.{BrandState, PlanState}
import io.eitheror.casino.programme.sealing.Identity

import java.time.Instant

/**
 * A percentage as the original's bodies carry it, `"5"`, `5` or `5.5`, held as basis points; `null` in a
 * patch clears what it names.
 */
final case class Percent(bp: Int)

object Percent:
  def of(text: String): Option[Percent] =
    text.trim.toDoubleOption.filter(p => p >= 0 && p <= 100).map(p => Percent(math.round(p * 100).toInt))

  /** Basis points as the percentage a reader sees: 3000 is 30, 550 is 5.5. */
  def show(bp: Int): BigDecimal = BigDecimal(bp) / 100

  given JsonValueCodec[Percent] = new JsonValueCodec[Percent]:
    def decodeValue(in: JsonReader, default: Percent): Percent =
      val text =
        if in.isNextToken('"') then { in.rollbackToken(); in.readString(null) }
        else { in.rollbackToken(); in.readBigDecimal(null).toString }
      of(text).getOrElse(in.decodeError("a percentage between 0 and 100"))
    def encodeValue(x: Percent, out: JsonWriter): Unit = out.writeVal(show(x.bp))
    def nullValue: Percent = null

/** What the routes accept (contracts/routes.md), each field the original's name. */
object Bodies:
  final case class ApplyBody(email: Option[String] = None, name: Option[String] = None, website: Option[String] = None,
      trafficSource: Option[String] = None, termsVersion: Option[String] = None, companyName: Option[String] = None)
  final case class ApproveBody(planId: Option[String] = None, overridePercent: Option[Percent] = None, parentAffiliateId: Option[String] = None)
  final case class RejectBody(reason: Option[String] = None)
  /**
   * A patch of an account: a manager's at a brand (O21) or the affiliate's own (M6, identity and postback
   * only). `revShareOverride: null` clears the override (`AccountPatch.clears`); absent leaves it.
   */
  final case class AccountPatch(
      planId: Option[String] = None,
      revShareOverride: Option[Percent] = None,
      name: Option[String] = None,
      displayName: Option[String] = None,
      companyName: Option[String] = None,
      website: Option[String] = None,
      trafficSource: Option[String] = None,
      paymentMethod: Option[String] = None,
      postbackUrl: Option[String] = None,
      managerId: Option[String] = None,
      group: Option[String] = None,
      tags: Option[Vector[String]] = None,
      minimumPayout: Option[Map[String, Long]] = None
  )

  object AccountPatch:
    /** The fields that are the account's, not the brand's membership's (FR-041): all but the plan, the override, the manager, group and tags. */
    def touchesAccount(p: AccountPatch): Boolean =
      Seq(p.name, p.displayName, p.companyName, p.website, p.trafficSource, p.paymentMethod, p.postbackUrl, p.minimumPayout).exists(_.isDefined)

    private val ClearsOverride = """"revShareOverride"\s*:\s*null""".r
    def clearsOverride(body: String): Boolean = ClearsOverride.findFirstIn(body).isDefined

  given JsonValueCodec[ApplyBody]    = ProgrammeCodecs.wire[ApplyBody]
  given JsonValueCodec[ApproveBody]  = ProgrammeCodecs.wire[ApproveBody]
  given JsonValueCodec[RejectBody]   = ProgrammeCodecs.wire[RejectBody]
  given JsonValueCodec[AccountPatch] = ProgrammeCodecs.wire[AccountPatch]

/** What the routes answer. */
object Answers:
  final case class Ok(ok: Boolean)
  final case class Applied(affiliateId: String, status: String)

  final case class MembershipAnswer(brandId: String, status: String, planId: Option[String], revShareOverride: Option[BigDecimal],
      appliedAt: Option[Instant], since: Option[Instant], rejectionReason: Option[String])

  final case class AccountAnswer(
      affiliateId: String,
      email: Option[String],
      name: Option[String],
      displayName: Option[String],
      companyName: Option[String],
      website: Option[String],
      trafficSource: Option[String],
      paymentMethod: Option[String],
      parentAffiliateId: Option[String],
      overridePercent: BigDecimal,
      postbackUrl: Option[String],
      minimumPayout: Map[String, Long],
      managerId: Option[String],
      group: Option[String],
      tags: Vector[String],
      createdAt: Option[Instant],
      firstLoginAt: Option[Instant],
      lastLoginAt: Option[Instant],
      closedAt: Option[Instant],
      /** At one brand (the operator's read): its status and plan, as the original's single-brand account. */
      status: Option[String],
      planId: Option[String],
      memberships: Vector[MembershipAnswer],
      /** The brand of `status` and `planId`, and the realm user the account signs in as, as the original's account. */
      brandId: Option[String] = None,
      keycloakUserId: Option[String] = None
  )

  def membership(m: MembershipState): MembershipAnswer =
    MembershipAnswer(m.brand, m.status.getOrElse(""), m.plan, m.revShareOverride.map(Percent.show), m.application.map(_.appliedAt), m.since,
      m.rejectedReason)

  /** What an erased account's identity reads as (data-model.md *Affiliate*): its name and email `erased`, the rest absent. */
  val ErasedIdentity: Identity = Identity("erased", email = "erased")

  def account(a: AffiliateState, opened: Option[Identity], memberships: Vector[MembershipState], atBrand: Option[MembershipState]): AccountAnswer =
    val identity = if a.erased.isDefined then Some(ErasedIdentity) else opened
    AccountAnswer(a.id, identity.map(_.email), identity.map(_.name), identity.flatMap(_.displayName), identity.flatMap(_.companyName),
      identity.flatMap(_.website), identity.flatMap(_.trafficSource), identity.flatMap(_.paymentMethod), a.parent, Percent.show(a.overrideBp),
      a.postbackUrl, a.minimumPayout, atBrand.flatMap(_.managerId), atBrand.flatMap(_.group), atBrand.fold(Vector.empty[String])(_.tags), a.createdAt, a.firstLoginAt, a.lastLoginAt, a.closed,
      atBrand.flatMap(_.status), atBrand.flatMap(_.plan), memberships.map(membership), atBrand.map(_.brand), a.keycloakUser)

  final case class PlanAnswer(planId: String, brandId: String, planName: String, planType: String, cpaAmount: Long, cpaCurrency: String,
      revSharePercent: BigDecimal, minimumDeposit: Long, negativeCarryover: Boolean, status: String)

  def plan(p: PlanState): PlanAnswer =
    PlanAnswer(p.id, p.brand, p.name, p.planType, p.cpaAmount, p.cpaCurrency, Percent.show(p.revShareBp), p.minimumDeposit,
      p.negativeCarryover, p.status)

  final case class ProgrammeAnswer(brandId: String, name: Option[String], terms: Option[String], termsVersion: Option[String],
      contact: Option[String], description: Option[String], payoutCycleDays: Option[Int], minimumPayoutAmount: Option[Long],
      defaultPlanId: Option[String])

  def programme(b: BrandState): ProgrammeAnswer =
    ProgrammeAnswer(b.slug, b.name, b.page.terms, b.page.termsVersion, b.page.contact, b.page.description, b.page.payoutCycleDays,
      b.page.minimumPayoutAmount, b.defaultPlan)

  final case class CpaRow(brandId: String, currency: String, playerRef: String, depositDate: Instant, cpaAmount: Long)
  final case class CampaignRow(brandId: String, campaign: String, ftdCount: Long)
  final case class GeoRow(brandId: String, country: String, registrationCount: Long, ftdCount: Long)
  final case class DeductionRow(brandId: String, currency: String, `type`: String, amount: Long, transactionId: String, recordedAt: Instant)

  given JsonValueCodec[Ok]                     = ProgrammeCodecs.wire[Ok]
  given JsonValueCodec[Applied]                = ProgrammeCodecs.wire[Applied]
  given JsonValueCodec[AccountAnswer]          = ProgrammeCodecs.wire[AccountAnswer]
  given JsonValueCodec[PlanAnswer]             = ProgrammeCodecs.wire[PlanAnswer]
  given plansCodec: JsonValueCodec[Vector[PlanAnswer]] = ProgrammeCodecs.wire[Vector[PlanAnswer]]
  given JsonValueCodec[ProgrammeAnswer]        = ProgrammeCodecs.wire[ProgrammeAnswer]
  given cpaCodec: JsonValueCodec[Vector[CpaRow]] = ProgrammeCodecs.wire[Vector[CpaRow]]
  given campaignsCodec: JsonValueCodec[Vector[CampaignRow]] = ProgrammeCodecs.wire[Vector[CampaignRow]]
  given geoCodec: JsonValueCodec[Vector[GeoRow]] = ProgrammeCodecs.wire[Vector[GeoRow]]
  given deductionsCodec: JsonValueCodec[Vector[DeductionRow]] = ProgrammeCodecs.wire[Vector[DeductionRow]]
