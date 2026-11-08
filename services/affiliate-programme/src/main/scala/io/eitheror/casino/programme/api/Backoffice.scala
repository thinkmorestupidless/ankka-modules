package io.eitheror.casino.programme.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.api.Answers.{AccountAnswer, Applied, Ok, given}
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.brands.*
import io.eitheror.casino.programme.comms.{EmailSend, Notification, SendEmail, WriteNotification}
import io.eitheror.casino.programme.ledger.{Figure, Revenue}
import io.eitheror.casino.programme.periods.Periods
import io.eitheror.casino.programme.sealing.Identity

import java.time.Instant
import java.util.UUID

/** What the backoffice's plan, settings, programme, message and account routes take and answer. */
object BackofficeAnswers:
  final case class PlanBody(planName: Option[String] = None, planType: Option[String] = None, cpaAmount: Option[Long] = None,
      cpaCurrency: Option[String] = None, revSharePercent: Option[Percent] = None, minimumDepositThreshold: Option[Long] = None,
      negativeCarryover: Option[Boolean] = None, status: Option[String] = None)
  final case class PlanCreated(planId: String, planName: String)
  final case class AccountCreated(affiliateId: String, email: String, status: String)
  /** O8 for an affiliate of another brand: invited to apply here, nothing made (FR-041). */
  final case class AccountInvited(email: String, status: String)
  final case class PlanChanged(planId: String)

  final case class SettingsBody(adminFeePercent: Option[Percent] = None, paymentFeePercent: Option[Percent] = None,
      providerFeePercent: Option[Percent] = None, bonusCostThreshold: Option[Int] = None, chargebackThreshold: Option[Int] = None,
      payoutCycleDay: Option[Int] = None, reportingZone: Option[String] = None, ggrSource: Option[String] = None,
      ggrSourceFrom: Option[String] = None, senderRegion: Option[String] = None, senderMessages: Option[Map[String, String]] = None,
      defaultPlanId: Option[String] = None, customerIoKey: Option[String] = None)
  final case class SettingsAnswer(brandId: String, origin: String, adminFeePercent: BigDecimal, paymentFeePercent: BigDecimal,
      providerFeePercent: BigDecimal, bonusCostThreshold: Int, chargebackThreshold: Int, payoutCycleDay: Option[Int], reportingZone: String,
      ggrSource: String, ggrSourceFrom: Option[String], previousGgrSource: String, senderRegion: Option[String],
      senderMessages: Map[String, String], defaultPlanId: Option[String], retiredAt: Option[Instant], customerIoKeySet: Boolean)

  final case class ProgrammeBody(terms: Option[String] = None, termsVersion: Option[String] = None, contact: Option[String] = None,
      description: Option[String] = None, payoutCycleDays: Option[Int] = None, minimumPayoutAmount: Option[Long] = None,
      defaultPlanId: Option[String] = None)

  final case class MessageBody(body: Option[String] = None, targetAffiliateId: Option[String] = None, `type`: Option[String] = None)
  final case class Sent(sent: Int)

  final case class CreateBody(email: Option[String] = None, name: Option[String] = None, displayName: Option[String] = None,
      companyName: Option[String] = None, website: Option[String] = None, paymentMethod: Option[String] = None,
      planId: Option[String] = None, parentAffiliateId: Option[String] = None, overridePercent: Option[Percent] = None,
      minimumPayout: Option[Map[String, Long]] = None)
  /** O7's row: the account at the brand, and its figures this month in the brand's zone, per currency. */
  final case class AccountRow(account: AccountAnswer, currentMonth: Vector[Figure])

  /** The settings, and whether a Customer.io key is held: the key itself is never answered. */
  def settings(b: BrandState, keySet: Boolean): SettingsAnswer =
    SettingsAnswer(b.slug, b.origin, Percent.show(b.fees.admin), Percent.show(b.fees.payment), Percent.show(b.fees.provider),
      b.thresholds.bonusCost, b.thresholds.chargeback, b.payoutCycleDay, b.reportingZone, b.ggrSource.current, b.ggrSource.from,
      b.ggrSource.previous, b.sender.region, b.sender.messages, b.defaultPlan, b.retired, keySet)

  given planBody: JsonValueCodec[PlanBody]           = ProgrammeCodecs.wire[PlanBody]
  given planCreated: JsonValueCodec[PlanCreated]     = ProgrammeCodecs.wire[PlanCreated]
  given accountCreated: JsonValueCodec[AccountCreated] = ProgrammeCodecs.wire[AccountCreated]
  given accountInvited: JsonValueCodec[AccountInvited] = ProgrammeCodecs.wire[AccountInvited]
  given planChanged: JsonValueCodec[PlanChanged]     = ProgrammeCodecs.wire[PlanChanged]
  given settingsBody: JsonValueCodec[SettingsBody]   = ProgrammeCodecs.wire[SettingsBody]
  given settingsAnswer: JsonValueCodec[SettingsAnswer] = ProgrammeCodecs.wire[SettingsAnswer]
  given programmeBody: JsonValueCodec[ProgrammeBody] = ProgrammeCodecs.wire[ProgrammeBody]
  given messageBody: JsonValueCodec[MessageBody]     = ProgrammeCodecs.wire[MessageBody]
  given sent: JsonValueCodec[Sent]                   = ProgrammeCodecs.wire[Sent]
  given createBody: JsonValueCodec[CreateBody]       = ProgrammeCodecs.wire[CreateBody]
  given accountRows: JsonValueCodec[Vector[AccountRow]] = ProgrammeCodecs.wire[Vector[AccountRow]]

import BackofficeAnswers.{*, given}

/** A brand's programme as its managers run it (US6): plans, settings, the programme page, messages and accounts. */
final class Backoffice(api: Api):
  private def audit(affiliate: String, brand: String, action: String, by: String, details: Map[String, String]): Unit =
    Networks(api).audit(affiliate, Some(brand), action, by, details)

  // ── plans (O4 to O6) ──────────────────────────────────────────────────────────────────────────

  def plans(brand: String): Respond[Bytes] = ok(api.plansOf(brand).map(Answers.plan))

  /** Refused `plan_already_exists` by name, as the original; it applies from the next fact of a membership naming it. */
  def createPlan(brand: String, b: PlanBody, by: String): Respond[Bytes] =
    b.planName.map(_.trim).filter(_.nonEmpty) match
      case None => invalid("planName is required")
      case Some(name) =>
        val id = Plan.idOf(brand, name)
        val p = api.components.forKeyValueEntity(Plan.entityId(brand, id)).call(Plan.createPlan)
          .invoke(CreatePlan(brand, name, b.planType.fold(Plan.Cpa)(_.toLowerCase), b.cpaAmount.getOrElse(0L), b.cpaCurrency.getOrElse("EUR"),
            b.revSharePercent.fold(0)(_.bp), b.minimumDepositThreshold.getOrElse(0L), b.negativeCarryover.getOrElse(false), by, api.now()))
        audit("", brand, "PLAN_CREATED", by, Map("planId" -> p.id, "planName" -> name))
        answer(201, PlanCreated(p.id, name))

  def updatePlan(brand: String, plan: String, b: PlanBody, by: String): Respond[Bytes] =
    val u = UpdatePlan(b.cpaAmount, b.cpaCurrency, b.revSharePercent.map(_.bp), b.minimumDepositThreshold, b.negativeCarryover,
      b.status.map(_.toLowerCase))
    if u == UpdatePlan() then invalid("no updatable fields provided")
    else if !api.plan(brand, plan).exists then refuse(404, Codes.PlanNotFound, plan)
    else
      api.components.forKeyValueEntity(Plan.entityId(brand, plan)).call(Plan.update).invoke(u): Unit
      audit("", brand, "PLAN_UPDATED", by, Map("planId" -> plan) ++ changed(u))
      ok(PlanChanged(plan))

  private def changed(u: Product): Map[String, String] =
    u.productElementNames.zip(u.productIterator).collect { case (k, Some(v)) => k -> v.toString }.toMap

  // ── settings and the programme page (O43 to O46) ──────────────────────────────────────────────

  def settings(brand: String): Respond[Bytes] =
    Secrets(api.secrets).brandSecret(brand, Secrets.CustomerIoKey) match
      case Left(u)    => refuse(503, "store_unavailable", u.reason)
      case Right(key) => ok(BackofficeAnswers.settings(api.brand(brand), key.isDefined))

  /**
   * `customerIoKey` is the brand's Customer.io app API key (research R13): written to the store, never
   * answered, and set only by a brand or platform administrator (`admin`).
   */
  def putSettings(brand: String, b: SettingsBody, by: String, admin: Boolean): Respond[Bytes] =
    val s   = api.brand(brand)
    val key = b.customerIoKey.map(_.trim)
    b.defaultPlanId.filter(p => !api.plan(brand, p).exists) match
      case _ if key.isDefined && !admin => refuse(403, Routes.NotAffiliateManager, "customerIoKey: brand_admin or platform_admin is required")
      case _ if key.exists(_.isEmpty)   => invalid("customerIoKey is empty")
      case Some(missing) => refuse(404, Codes.PlanNotFound, missing)
      case None if b.ggrSource.isDefined && b.ggrSourceFrom.isEmpty => invalid("ggrSourceFrom is required with ggrSource: the day it takes effect")
      case None =>
        val fees = Option.when(b.adminFeePercent.isDefined || b.paymentFeePercent.isDefined || b.providerFeePercent.isDefined)(
          Fees(b.adminFeePercent.fold(s.fees.admin)(_.bp), b.paymentFeePercent.fold(s.fees.payment)(_.bp), b.providerFeePercent.fold(s.fees.provider)(_.bp)))
        val thresholds = Option.when(b.bonusCostThreshold.isDefined || b.chargebackThreshold.isDefined)(
          Thresholds(b.bonusCostThreshold.getOrElse(s.thresholds.bonusCost), b.chargebackThreshold.getOrElse(s.thresholds.chargeback)))
        val sender = Option.when(b.senderRegion.isDefined || b.senderMessages.isDefined)(
          Sender(b.senderRegion.orElse(s.sender.region), b.senderMessages.getOrElse(s.sender.messages)))
        val u = UpdateBrand(fees, thresholds, b.payoutCycleDay, b.reportingZone, b.defaultPlanId, None, sender)
        BrandProgramme.problem(s, u) match
          case Some(p) if p == Codes.BrandUnknown => refuse(404, p, brand)
          case Some(p)                            => invalid(p)
          case None =>
            key.map(k => Secrets(api.secrets).putBrandSecret(brand, Secrets.CustomerIoKey, k)) match
              case Some(Left(unavailable)) => refuse(503, "store_unavailable", unavailable.reason)
              case _ =>
                val entity = api.components.forKeyValueEntity(EntityId(brand))
                entity.call(BrandProgramme.update).invoke(u): Unit
                b.ggrSource.foreach(g => entity.call(BrandProgramme.setGgrSource).invoke(SetGgrSource(g, b.ggrSourceFrom.get)): Unit)
                audit("", brand, "SETTINGS_CHANGED", by, changed(b).view.mapValues(_ => "changed").toMap)
                ok(Ok(true))

  def programme(brand: String): Respond[Bytes] = ok(Answers.programme(api.brand(brand)))

  /** What applicants to the brand read (FR-007): the page, and the plan a new membership starts on. */
  def putProgramme(brand: String, b: ProgrammeBody, by: String): Respond[Bytes] =
    val s = api.brand(brand)
    if !s.exists then refuse(404, Codes.BrandUnknown, brand)
    else
      b.defaultPlanId.filter(p => !api.plan(brand, p).exists) match
        case Some(missing) => refuse(404, Codes.PlanNotFound, missing)
        case None =>
          val p = s.page
          val page = ProgrammePage(b.terms.orElse(p.terms), b.termsVersion.orElse(p.termsVersion), b.contact.orElse(p.contact),
            b.description.orElse(p.description), b.payoutCycleDays.orElse(p.payoutCycleDays), b.minimumPayoutAmount.orElse(p.minimumPayoutAmount))
          api.components.forKeyValueEntity(EntityId(brand)).call(BrandProgramme.update)
            .invoke(UpdateBrand(page = Some(page), defaultPlan = b.defaultPlanId)): Unit
          audit("", brand, "PROGRAMME_CHANGED", by, changed(b).view.mapValues(_ => "changed").toMap)
          ok(Ok(true))

  // ── messages (O47) ────────────────────────────────────────────────────────────────────────────

  /** A notification to one member of the brand, or to every active one, as the original's broadcast. */
  def message(brand: String, b: MessageBody, by: String): Respond[Bytes] =
    b.body.map(_.trim).filter(_.nonEmpty) match
      case None => invalid("body is required")
      case Some(text) =>
        val recipients = b.targetAffiliateId match
          case Some(target) => if api.membership(target, brand).exists then Right(Vector(target)) else Left(refuse(404, Codes.AccountNotFound))
          case None =>
            Right(api.views.forView(Memberships).ask(Memberships.byBrandAndStatus, "brand" -> brand, "status" -> Membership.Active)
              .map(_.affiliate).distinct)
        recipients.fold(identity, { to =>
          val at = api.now()
          to.foreach { a =>
            api.components.forKeyValueEntity(EntityId(UUID.randomUUID().toString)).call(Notification.write)
              .invoke(WriteNotification(a, Some(brand), b.`type`.getOrElse("OPERATOR_MESSAGE"), text, at)): Unit
          }
          audit(b.targetAffiliateId.getOrElse(""), brand, "MESSAGE_SENT", by, Map("recipients" -> to.size.toString))
          ok(Sent(to.size))
        })

  // ── accounts (O7, O8) ─────────────────────────────────────────────────────────────────────────

  /**
   * O7 (research R9): the brand's memberships, filtered by status, manager, group and tags (the
   * membership's own) before any account is read, then by search with the accounts opened, since the
   * sealed fields cannot be searched in the view; with each
   * account's figures this month, read once for the brand. A page of `limit` (100, at most 500) from
   * `offset`: the view's and the account's own fields are filtered first, and an identity is opened only
   * for the page, or for every candidate when a search or a sort by name or email needs it.
   */
  def accounts(brand: String, q: String => Option[String]): Respond[Bytes] =
    val b      = api.brand(brand)
    val month  = Periods.Range.parse(Periods.periodOf(api.now(), b.zone)).get
    val tags   = q("tags").fold(Set.empty[String])(_.split(',').map(_.trim).filter(_.nonEmpty).toSet)
    val needle = q("search").map(_.trim.toLowerCase).filter(_.nonEmpty)
    val limit  = q("limit").flatMap(_.toIntOption).fold(Backoffice.PageSize)(l => math.max(1, math.min(l, Backoffice.MaxPage)))
    val offset = q("offset").flatMap(_.toIntOption).fold(0)(math.max(0, _))
    val sortBy = q("sortBy").getOrElse("createdAt")
    val desc   = q("sortDir").forall(_.equalsIgnoreCase("desc"))
    val candidates = api.views.forView(Memberships).ask(Memberships.byBrand, "brand" -> brand)
      .filter { m =>
        q("status").forall(s => m.status.exists(_.equalsIgnoreCase(s))) && q("affiliateManagerId").forall(x => m.managerId.contains(x)) &&
          q("group").forall(g => m.group.contains(g)) && (tags.isEmpty || m.tags.exists(tags))
      }
      .flatMap(m => Some(api.account(m.affiliate)).filter(_.exists).map(m -> _))
    def opened(c: Vector[(MembershipState, AffiliateState)]) = c.map((m, a) => (m, a, api.identity(m.affiliate, a).toOption.flatten))
    def ordered[K: Ordering](rows: Vector[(MembershipState, AffiliateState, Option[Identity])])(k: ((MembershipState, AffiliateState, Option[Identity])) => K) =
      val up = rows.sortBy(k)
      if desc then up.reverse else up
    val page =
      if needle.isDefined || sortBy == "name" || sortBy == "email" then
        val found = opened(candidates).filter { (_, a, id) =>
          needle.forall(n => (Vector(a.id) ++ id.toVector.flatMap(i => Vector(i.name, i.email) ++ i.displayName ++ i.companyName))
            .exists(_.toLowerCase.contains(n)))
        }
        val sorted = sortBy match
          case "name"   => ordered(found)(_._3.fold("")(i => i.displayName.getOrElse(i.name)).toLowerCase)
          case "email"  => ordered(found)(_._3.fold("")(_.email).toLowerCase)
          case "status" => ordered(found)(_._1.status.getOrElse(""))
          case _        => ordered(found)(_._2.createdAt.getOrElse(Instant.EPOCH))
        sorted.slice(offset, offset + limit)
      else
        val cheap = candidates.map((m, a) => (m, a, Option.empty[Identity]))
        val sorted = if sortBy == "status" then ordered(cheap)(_._1.status.getOrElse("")) else ordered(cheap)(_._2.createdAt.getOrElse(Instant.EPOCH))
        opened(sorted.slice(offset, offset + limit).map((m, a, _) => (m, a)))
    val (ledger, players) = Reading(api).rows(None, Vector(brand), month)
    val ledgerOf  = ledger.groupBy(_.affiliate)
    val playersOf = players.groupBy(_.affiliate)
    ok(page.map { (m, a, id) =>
      AccountRow(Answers.account(a, id, Vector(m), Some(m)),
        Revenue.figures(ledgerOf.getOrElse(a.id, Vector.empty), playersOf.getOrElse(a.id, Vector.empty)))
    })

  /**
   * O8: an account made active at the brand by a manager, on the plan named or the brand's default, and
   * sent the invitation to set a password. An email whose account applied or is active here is refused.
   * One that is another brand's affiliate is sent an invitation to apply here (`AffiliateBrandInvite`) and
   * answered `202 invited`: a manager must not give another brand's affiliate a membership, and with it
   * their identity, without them, nor learn which brand they work with (FR-041).
   */
  def createAccount(brand: String, b: CreateBody, by: String): Respond[Bytes] =
    val enrolment = Enrolment(api)
    enrolment.address(b.email) match
      case None => invalid("email is required")
      case Some(email) =>
        b.planId.orElse(api.brand(brand).defaultPlan) match
          case None => invalid("planId is required: the brand has no default plan")
          case Some(plan) if !api.plan(brand, plan).exists => refuse(404, Codes.PlanNotFound, plan)
          case Some(plan) =>
            b.parentAffiliateId.filter(p => !api.account(p).exists) match
              case Some(missing) => refuse(400, Codes.ParentNotFound, missing)
              case None =>
                enrolment.lookup(email).fold(e => refuse(503, "store_unavailable", e), { (digest, known) =>
                  if known.exists(a => api.membership(a, brand).holds) then refuse(409, Codes.EmailRegistered)
                  else if known.isDefined then
                    inviteToApply(known.get, brand, by)
                    answer(202, AccountInvited(email, "invited"))
                  else
                    val affiliate = Affiliate.newId()
                    val identity = Identity(b.name.getOrElse(email), b.displayName.orElse(b.name), email, b.companyName, b.website, None,
                      b.paymentMethod)
                    enrolment.open(affiliate, digest, identity, b.parentAffiliateId, b.overridePercent.fold(0)(_.bp),
                      AccountFields(minimumPayout = b.minimumPayout), by).fold(e => refuse(503, "store_unavailable", e), { _ =>
                      api.components.forEventSourcedEntity(Membership.entityId(affiliate, brand)).call(Membership.activateInvited)
                        .invoke(ActivateInvited(affiliate, brand, plan, Application(api.now(), None, "operator", b.parentAffiliateId), by)): Unit
                      Networks(api).signIn(affiliate, brand)
                      audit(affiliate, brand, "ACCOUNT_CREATED", by, Map("planId" -> plan))
                      // As the original: the account and the address it was made for.
                      answer(201, AccountCreated(affiliate, email, Membership.Active))
                    })
                })

  /** Once per affiliate and brand: the invitation's id is theirs, so a manager asking again sends nothing more. */
  private def inviteToApply(affiliate: String, brand: String, by: String): Unit =
    val apply = s"${api.settings.portalBaseUrl}/apply?brand=$brand"
    api.components.forWorkflow(EntityId(s"brand-invite/$brand/$affiliate")).call(EmailSend.start)
      .invoke(SendEmail(affiliate, brand, Backoffice.BrandInvite, Map("brand" -> brand, "apply_url" -> apply))): Unit
    audit(affiliate, brand, "BRAND_INVITATION_SENT", by, Map.empty)

object Backoffice:
  /** The email inviting another brand's affiliate to apply at this one (O8). */
  val BrandInvite = "AffiliateBrandInvite"

  /** O7's page: 100 rows unless the caller asks for fewer, or more up to 500. */
  val PageSize = 100
  val MaxPage  = 500
