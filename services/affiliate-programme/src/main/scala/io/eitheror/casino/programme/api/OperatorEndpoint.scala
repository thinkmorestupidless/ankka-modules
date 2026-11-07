package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.api.Answers.{*, given}
import io.eitheror.casino.programme.api.Bodies.{*, given}
import io.eitheror.casino.programme.api.SettlementAnswers.given
import io.eitheror.casino.programme.api.TrackingAnswers.given
import io.eitheror.casino.programme.api.BackofficeAnswers.given
import io.eitheror.casino.programme.api.RecordAnswers.given
import io.eitheror.casino.programme.api.ManagerAnswers.given
import io.eitheror.casino.programme.api.PasswordAnswers.{AcceptBody, ForgotBody, given}
import io.eitheror.casino.programme.api.SubjectAnswers.{EmailBody, given}
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.network.Network
import io.eitheror.casino.programme.sealing.Identity

/**
 * The managers' backoffice (contracts/routes.md `/v1/affiliates`): a manager of the path's brand, the
 * manager routes for its administrators, and three public routes. Each story adds its routes here.
 */
final class OperatorEndpoint(api: Api) extends HttpEndpoint("/v1/affiliates"):
  val acl: Acl = Acl.DenyAll
  private val reading  = Reading(api)
  private val networks = Networks(api)
  private val settlement = Settlement(api)
  private val backoffice = Backoffice(api)
  private val records    = Records(api)
  private val managers   = Managers(api)
  private val passwords  = Passwords(api)
  private val subjects   = DataSubjects(api)

  withAcl(Access.public) {
    postBody("/accept-invitation") { (body: String) => attempt(withBody[AcceptBody](body)(passwords.accept)) }
    postBody("/{brand}/forgot-password") { (brand: String, body: String) => attempt(withBody[ForgotBody](body)(passwords.forgot(brand, _))) }
    postBody("/{brand}/apply") { (brand: String, body: String) => attempt(withBody[ApplyBody](body)(b => live(brand)(applyTo(brand, b)))) }
    // O19 is not served (change 13): a status changes only through its transitions.
    patchBody("/{brand}/accounts/{affiliate}/status") { (_: String, _: String, _: String) =>
      refuse(404, "not_found", "a membership's status changes through approve, reject, suspend and reactivate")
    }
  }

  withAcl(api.access.manager) {
    postBody("/{brand}/accounts/{affiliate}/approve") { (brand: String, aff: String, body: String) =>
      attempt(withBody[ApproveBody](body)(b => live(brand)(approve(brand, aff, b))))
    }
    postBody("/{brand}/accounts/{affiliate}/reject") { (brand: String, aff: String, body: String) =>
      attempt(withBody[RejectBody](body)(b => live(brand)(member(brand, aff) { _ =>
        api.components.forEventSourcedEntity(Membership.entityId(aff, brand)).call(Membership.reject)
          .invoke(Reject(b.reason, principal.subject, api.now())): Unit
        networks.audit(aff, Some(brand), "APPLICATION_REJECTED", principal.subject, b.reason.map("reason" -> _).toMap)
        ok(Ok(true))
      })))
    }
    get("/{brand}/accounts/{affiliate}") { (brand: String, aff: String) => attempt(member(brand, aff)(m => accountAt(aff, m))) }
    // US7: an invitation sent again, and a reset sent by a manager (O16, O17).
    post("/{brand}/accounts/{affiliate}/resend-invitation") { (brand: String, aff: String) =>
      attempt(member(brand, aff)(_ => passwords.resendInvitation(brand, aff, principal.subject)))
    }
    post("/{brand}/accounts/{affiliate}/reset-password") { (brand: String, aff: String) =>
      attempt(member(brand, aff)(_ => passwords.reset(brand, aff, principal.subject)))
    }
    // US8: the account as a data subject (FR-037), and its players by pseudonym (O26, FR-038).
    postBody("/{brand}/accounts/{affiliate}/update-email") { (brand: String, aff: String, body: String) =>
      attempt(withBody[EmailBody](body)(b => member(brand, aff)(_ => subjects.updateEmail(brand, aff, b, principal.subject))))
    }
    post("/{brand}/accounts/{affiliate}/close") { (brand: String, aff: String) =>
      attempt(member(brand, aff)(_ => subjects.close(brand, aff, principal.subject, Routes.brandsOf(principal).holds)))
    }
    post("/{brand}/accounts/{affiliate}/erasure") { (brand: String, aff: String) =>
      attempt(member(brand, aff)(_ => subjects.erasure(brand, aff, principal.subject, Routes.brandsOf(principal).holds)))
    }
    get("/{brand}/accounts/{affiliate}/players") { (brand: String, aff: String) =>
      attempt(member(brand, aff)(_ => subjects.players(aff, Vector(brand), network = false)))
    }
    patchBody("/{brand}/accounts/{affiliate}") { (brand: String, aff: String, body: String) =>
      attempt(withBody[AccountPatch](body)(p => member(brand, aff)(_ => patch(brand, aff, p, AccountPatch.clearsOverride(body)))))
    }
    get("/{brand}/accounts/{affiliate}/revenue") { (brand: String, aff: String) =>
      attempt(member(brand, aff)(_ => reading.revenue(Some(aff), Vector(brand), query.raw("period"))))
    }
    // US3: an account's network at the brand, and the operator's transitions of a sub there.
    get("/{brand}/accounts/{affiliate}/network-health") { (brand: String, aff: String) =>
      attempt(member(brand, aff)(_ => networks.health(aff, Vector(brand))))
    }
    get("/{brand}/accounts/{affiliate}/network") { (brand: String, aff: String) =>
      attempt(member(brand, aff)(_ => networks.tree(aff, Vector(brand), query.raw("maxDepth"), query.raw("period"))))
    }
    get("/{brand}/accounts/{affiliate}/sub-affiliates") { (brand: String, aff: String) =>
      attempt(member(brand, aff)(_ => networks.subs(aff, Vector(brand), query.raw("period"))))
    }
    for action <- Seq("approve", "suspend", "reactivate") do
      post(s"/{brand}/network/{sub}/$action") { (brand: String, sub: String) =>
        attempt(live(brand)(member(brand, sub)(_ => networks.transition(sub, brand, action, principal.subject, Membership.Operator))))
      }
    // US5: the brand's links; a link made for one of its affiliates.
    get("/{brand}/tracking-links") { (brand: String) => attempt(Tracking(api).links(None, brand)) }
    postBody("/{brand}/tracking-links") { (brand: String, body: String) =>
      attempt(withBody[TrackingAnswers.LinkBody](body)(b => b.affiliateId match
        case None      => invalid("affiliateId is required")
        case Some(aff) => member(brand, aff)(_ => Tracking(api).createLink(aff, brand, b, principal.subject))))
    }
    // US4: closing a period, the payouts, adjustments and the export.
    post("/{brand}/payouts/close") { (brand: String) => attempt(settlement.close(brand, query.raw("period"), principal.subject)) }
    get("/{brand}/payouts") { (brand: String) => attempt(settlement.list(brand, query.raw("period"))) }
    get("/{brand}/payouts/export") { (brand: String) => attempt(settlement.exported(brand, query.raw("period"), principal.subject)) }
    patchBody("/{brand}/payouts/{payout}/status") { (brand: String, payout: String, body: String) =>
      attempt(withBody[SettlementAnswers.StatusBody](body)(settlement.transition(brand, payout, _, principal.subject)))
    }
    postBody("/{brand}/accounts/{affiliate}/adjustments") { (brand: String, aff: String, body: String) =>
      attempt(withBody[SettlementAnswers.AdjustmentBody](body)(b => member(brand, aff)(_ => settlement.adjust(brand, aff, b, principal.subject))))
    }
    get("/{brand}/revenue") { (brand: String) => attempt(reading.revenue(None, Vector(brand), query.raw("period"))) }
    get("/{brand}/revenue/by-campaign") { (brand: String) =>
      attempt(reading.range(query.raw("period"))((_, r) => ok(Reports.byCampaign(reading.rows(None, Vector(brand), r)._2))))
    }
    get("/{brand}/revenue/geo") { (brand: String) =>
      attempt(reading.range(query.raw("period"))((_, r) => ok(Reports.geo(reading.rows(None, Vector(brand), r)._2))))
    }
  }

  withAcl(api.access.manager) {
    // US6: plans, settings and the programme page.
    get("/{brand}/commission-plans") { (brand: String) => attempt(backoffice.plans(brand)) }
    postBody("/{brand}/commission-plans") { (brand: String, body: String) =>
      attempt(withBody[BackofficeAnswers.PlanBody](body)(backoffice.createPlan(brand, _, principal.subject)))
    }
    patchBody("/{brand}/commission-plans/{plan}") { (brand: String, plan: String, body: String) =>
      attempt(withBody[BackofficeAnswers.PlanBody](body)(backoffice.updatePlan(brand, plan, _, principal.subject)))
    }
    get("/{brand}/settings") { (brand: String) => attempt(backoffice.settings(brand)) }
    putBody("/{brand}/settings") { (brand: String, body: String) =>
      attempt(withBody[BackofficeAnswers.SettingsBody](body)(backoffice.putSettings(brand, _, principal.subject, principal.roles.exists(Access.ManagerAdminRoles))))
    }
    get("/{brand}/programme") { (brand: String) => attempt(backoffice.programme(brand)) }
    putBody("/{brand}/programme") { (brand: String, body: String) =>
      attempt(withBody[BackofficeAnswers.ProgrammeBody](body)(backoffice.putProgramme(brand, _, principal.subject)))
    }
    // The brand's accounts, and one made active by a manager.
    get("/{brand}/accounts") { (brand: String) => attempt(backoffice.accounts(brand, k => query.raw(k).map(_.trim).filter(_.nonEmpty))) }
    postBody("/{brand}/accounts") { (brand: String, body: String) =>
      attempt(withBody[BackofficeAnswers.CreateBody](body)(b => live(brand)(backoffice.createAccount(brand, b, principal.subject))))
    }
    // Notes, the audit, and the read-only view of what the affiliate sees at the brand (FR-033): reads only.
    get("/{brand}/accounts/{affiliate}/notes") { (brand: String, aff: String) => attempt(member(brand, aff)(_ => records.notesOf(aff, brand))) }
    postBody("/{brand}/accounts/{affiliate}/notes") { (brand: String, aff: String, body: String) =>
      attempt(withBody[RecordAnswers.NoteBody](body)(b => member(brand, aff)(_ => records.note(aff, brand, b, principal.subject))))
    }
    get("/{brand}/accounts/{affiliate}/audit") { (brand: String, aff: String) => attempt(member(brand, aff)(_ => records.auditOf(aff, brand))) }
    for page <- OperatorEndpoint.ViewPages do
      get(s"/{brand}/accounts/{affiliate}/view/$page") { (brand: String, aff: String) =>
        attempt(member(brand, aff)(_ => view(page, brand, aff)))
      }
    postBody("/{brand}/messages") { (brand: String, body: String) =>
      attempt(withBody[BackofficeAnswers.MessageBody](body)(backoffice.message(brand, _, principal.subject)))
    }
    get("/{brand}/fraud-flags") { (brand: String) => attempt(records.flagsOf(brand)) }
    patchBody("/{brand}/fraud-flags/{flag}/dismiss") { (brand: String, flag: String, body: String) =>
      attempt(withBody[RecordAnswers.DismissBody](body)(records.dismiss(brand, flag, _, principal.subject)))
    }
    get("/{brand}/unattributed") { (brand: String) => attempt(records.unattributedOf(brand)) }
    get("/{brand}/creatives") { (brand: String) => attempt(records.creativesOf(brand)) }
    postBody("/{brand}/creatives") { (brand: String, body: String) =>
      attempt(withBody[RecordAnswers.CreativeBody](body)(records.creative(brand, _, principal.subject)))
    }
    delete("/{brand}/creatives/{creative}") { (brand: String, id: String) => attempt(records.deleteCreative(brand, id, principal.subject)) }
  }

  withAcl(api.access.managerAdmin) {
    get("/{brand}/managers") { (brand: String) => attempt(managers.list(brand)) }
    postBody("/{brand}/managers") { (brand: String, body: String) =>
      attempt(withBody[ManagerAnswers.ManagerBody](body)(managers.add(brand, _, principal.subject)))
    }
    post("/{brand}/managers/{user}/reset-password") { (brand: String, user: String) => attempt(managers.reset(brand, user, principal.subject)) }
    delete("/{brand}/managers/{user}") { (brand: String, user: String) => attempt(managers.revoke(brand, user, principal.subject)) }
  }

  /** O29 to O34: what the portal answers the affiliate, at this brand only. */
  private def view(page: String, brand: String, aff: String): Respond[Bytes] = page match
    case "dashboard" | "stats" => reading.revenue(Some(aff), Vector(brand), query.raw("period"))
    case "commission" =>
      reading.range(query.raw("period"))((_, r) => ok(Reports.cpa(reading.rows(Some(aff), Vector(brand), r)._1)))
    case "links"   => Tracking(api).links(Some(aff), brand)
    case "payouts" => settlement.of(aff, Vector(brand))
    case "postbacks" => Feed(api).postbacks(aff, Vector(brand))
    case _         => ok(records.notificationsOf(aff, Vector(brand)))

  /** A retired brand takes no application and changes no membership's status; closes, payouts and reads go on. */
  private def live(brand: String)(f: => Respond[Bytes]): Respond[Bytes] =
    if api.brand(brand).retired.isDefined then refuse(409, OperatorEndpoint.BrandRetired, brand) else f

  /** The route's body for an account with a membership at the brand; any other is not found (FR-041). */
  private def member(brand: String, affiliate: String)(f: MembershipState => Respond[Bytes]): Respond[Bytes] =
    val m = api.membership(affiliate, brand)
    if m.exists then f(m) else refuse(404, Codes.AccountNotFound)

  /**
   * An application (P7, FR-003): a new email makes an account, its identity sealed and its email indexed;
   * an email already known applies with its account. A pending or active membership at the brand is a
   * duplicate; a rejected one may apply again.
   */
  private def applyTo(brand: String, b: ApplyBody): Respond[Bytes] =
    val enrolment = Enrolment(api)
    val email     = enrolment.address(b.email)
    if !api.brand(brand).exists then refuse(404, Codes.BrandUnknown, brand)
    else if email.isEmpty then invalid("email is required")
    else
      enrolment.lookup(email.get).fold(e => refuse(503, "store_unavailable", e), { (digest, known) =>
        val affiliate = known.getOrElse(Affiliate.newId())
        if known.exists(a => api.membership(a, brand).holds) then refuse(409, Codes.EmailRegistered)
        else
          val identity = Identity(b.name.getOrElse(email.get), b.name, email.get, b.companyName, b.website, b.trafficSource)
          val made =
            if known.isDefined then Right(())
            else
              enrolment.open(affiliate, digest, identity, None, 0,
                AccountFields(termsVersion = b.termsVersion, termsAcceptedAt = b.termsVersion.map(_ => api.now())), "self")
          made.fold(e => refuse(503, "store_unavailable", e), { _ =>
            api.components.forEventSourcedEntity(Membership.entityId(affiliate, brand)).call(Membership.applyFor)
              .invoke(Apply(affiliate, brand, Application(api.now(), b.termsVersion, "self"))): Unit
            answer(201, Answers.Applied(affiliate, Membership.Pending))
          })
      })

  /** O14: on the plan named or the brand's default; the override percent and the parent with it, as the original. */
  private def approve(brand: String, affiliate: String, b: ApproveBody): Respond[Bytes] =
    member(brand, affiliate) { _ =>
      b.planId.orElse(api.brand(brand).defaultPlan) match
        case None => invalid("planId is required: the brand has no default plan")
        case Some(planId) if !api.plan(brand, planId).exists => refuse(404, Codes.PlanNotFound, planId)
        case Some(planId) =>
          b.parentAffiliateId.filter(p => !api.account(p).exists) match
            case Some(missing) => refuse(400, Codes.ParentNotFound, missing)
            case None if b.parentAffiliateId.exists(p => Network.wouldCycle(affiliate, p, api.parentOf)) =>
              refuse(409, Codes.ParentCycle, b.parentAffiliateId.get)
            case None =>
              val account = api.components.forEventSourcedEntity(EntityId(affiliate))
              b.parentAffiliateId.foreach(p => account.call(Affiliate.setParent).invoke(SetParent(Some(p), principal.subject, api.now())): Unit)
              b.overridePercent.foreach(p => account.call(Affiliate.setOverride).invoke(SetOverride(p.bp, principal.subject, api.now())): Unit)
              api.components.forEventSourcedEntity(Membership.entityId(affiliate, brand)).call(Membership.approve)
                .invoke(Approve(planId, principal.subject, api.now())): Unit
              networks.audit(affiliate, Some(brand), "APPLICATION_APPROVED", principal.subject,
                Map("planId" -> planId) ++ b.parentAffiliateId.map("parentAffiliateId" -> _) ++
                  b.overridePercent.map(p => "overridePercent" -> Percent.show(p.bp).toString))
              ok(Ok(true))
    }

  /** O20: the account as seen from one brand, its other memberships left out (FR-041). */
  private def accountAt(affiliate: String, m: MembershipState): Respond[Bytes] =
    val a = api.account(affiliate)
    if !a.exists then refuse(404, Codes.AccountNotFound)
    else api.identity(affiliate, a).fold(e => refuse(503, "store_unavailable", e), id => ok(Answers.account(a, id, Vector(m), Some(m))))

  /** O21: the plan and RevShare override at this brand; the identity re-sealed; the account's own fields. */
  private def patch(brand: String, affiliate: String, p: AccountPatch, clearOverride: Boolean): Respond[Bytes] =
    p.planId.filter(id => !api.plan(brand, id).exists) match
      case Some(missing) => refuse(404, Codes.PlanNotFound, missing)
      case None =>
        val membership = api.components.forEventSourcedEntity(Membership.entityId(affiliate, brand))
        p.planId.foreach { id =>
          membership.call(Membership.setPlan).invoke(SetPlan(id, principal.subject, api.now())): Unit
          networks.audit(affiliate, Some(brand), "PLAN_CHANGED", principal.subject, Map("planId" -> id))
        }
        if clearOverride || p.revShareOverride.isDefined then
          membership.call(Membership.setRevShareOverride)
            .invoke(SetRevShareOverride(p.revShareOverride.map(_.bp), principal.subject, api.now())): Unit
          networks.audit(affiliate, Some(brand), "REVSHARE_OVERRIDE_CHANGED", principal.subject,
            p.revShareOverride.map(o => "revShareOverride" -> Percent.show(o.bp).toString).toMap)
        Patching(api).apply(affiliate, p, principal.subject, manager = true).getOrElse(accountAt(affiliate, api.membership(affiliate, brand)))

object OperatorEndpoint:
  val ViewPages    = Vector("dashboard", "stats", "commission", "links", "payouts", "notifications", "postbacks")
  val BrandRetired = "brand_retired"
