package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.programme.accounts.{Membership, MembershipState}
import io.eitheror.casino.programme.api.Answers.given
import io.eitheror.casino.programme.api.Bodies.{*, given}
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.brands.Plan
import io.eitheror.casino.programme.settlement.{PayoutCodecs, Payouts}

/**
 * The affiliate's portal (contracts/routes.md `/v1/my`): an affiliate, acting on their own account or
 * a descendant's; any other target is `404 account_not_found`, as the original. Every read takes
 * `?brand=`; without it, every brand the account is a member of (active or suspended). Each story adds
 * its routes.
 */
final class PortalEndpoint(api: Api) extends HttpEndpoint("/v1/my"):
  val acl: Acl = Acl.DenyAll
  private val reading = Reading(api)

  withAcl(api.access.affiliate) {
    get("/{affiliate}/revenue") { (aff: String) =>
      asTarget(aff)(_ => brands(aff)(bs => reading.revenue(Some(aff), bs, query.raw("period"))))
    }
    get("/{affiliate}/revenue/{period}/breakdown") { (aff: String, period: String) =>
      asTarget(aff)(_ => brands(aff)(bs => reading.range(Some(period))((_, r) => ok(Reports.cpa(reading.rows(Some(aff), bs, r)._1)))))
    }
    get("/{affiliate}/revenue/by-campaign") { (aff: String) =>
      asTarget(aff)(_ => brands(aff)(bs => reading.range(query.raw("period"))((_, r) => ok(Reports.byCampaign(reading.rows(Some(aff), bs, r)._2)))))
    }
    get("/{affiliate}/deductions/{period}") { (aff: String, period: String) =>
      asTarget(aff)(_ => brands(aff)(bs => reading.range(Some(period))((_, r) => ok(Reports.deductions(reading.rows(Some(aff), bs, r)._1)))))
    }
    get("/{affiliate}/payouts") { (aff: String) =>
      asTarget(aff)(_ => brands(aff) { bs =>
        val rows = api.views.forView(Payouts).ask(Payouts.byAffiliate, "affiliate" -> aff).filter(p => bs.contains(p.brand))
        ok(rows)(using PayoutCodecs.wireList)
      })
    }
    get("/{affiliate}/account") { (aff: String) => asTarget(aff)(_ => attempt(account(aff))) }
    patchBody("/{affiliate}/account") { (aff: String, body: String) =>
      asTarget(aff)(_ => attempt(withBody[AccountPatch](body)(p => Patching(api).apply(aff, p, principal.subject, manager = false).getOrElse(account(aff)))))
    }
    get("/{affiliate}/plan") { (aff: String) =>
      asTarget(aff)(_ => atBrand(aff) { m =>
        m.plan.map(api.plan(m.brand, _)).filter(_.exists) match
          case Some(p) => ok(Answers.plan(p))
          case None    => refuse(404, Codes.NoPlanAssigned)
      })
    }
    get("/{affiliate}/available-plans") { (aff: String) =>
      asTarget(aff)(_ => atBrand(aff)(m => ok(api.plansOf(m.brand).filter(_.status == Plan.Active).map(Answers.plan))))
    }
    get("/{affiliate}/programme") { (aff: String) => asTarget(aff)(_ => atBrand(aff)(m => ok(Answers.programme(api.brand(m.brand))))) }
  }

  /** The route's body when the caller reaches the target, else the original's 404. */
  private def asTarget(target: String)(f: String => Respond[Bytes]): Respond[Bytes] =
    Access.callerOf(principal) match
      case Some(caller) if Access.reaches(caller, target, api.parentOf) => f(caller)
      case _ => refuse(404, Codes.AccountNotFound)

  private def receives(m: MembershipState) = m.status.exists(s => s == Membership.Active || s == Membership.Suspended)

  /** The brands a read covers: `?brand=`, which must be one the account is a member of, or all of them. */
  private def brands(affiliate: String)(f: Vector[String] => Respond[Bytes]): Respond[Bytes] =
    query.raw("brand").map(_.trim).filter(_.nonEmpty) match
      case Some(b) => if api.membership(affiliate, b).exists then f(Vector(b)) else refuse(409, Codes.NotAMember, b)
      case None    => f(api.membershipsOf(affiliate).filter(receives).map(_.brand).distinct.sorted)

  /** A read of one brand's (M7 to M9): `?brand=` is required and must be a membership's. */
  private def atBrand(affiliate: String)(f: MembershipState => Respond[Bytes]): Respond[Bytes] =
    query.raw("brand").map(_.trim).filter(_.nonEmpty) match
      case None => invalid("brand is required")
      case Some(b) =>
        val m = api.membership(affiliate, b)
        if m.exists then f(m) else refuse(409, Codes.NotAMember, b)

  /** M6: the account and every membership it holds. */
  private def account(affiliate: String): Respond[Bytes] =
    val a = api.account(affiliate)
    if !a.exists then refuse(404, Codes.AccountNotFound)
    else
      val held = api.membershipsOf(affiliate).map(m => api.membership(affiliate, m.brand))
      api.identity(affiliate, a).fold(e => refuse(503, "store_unavailable", e), id => ok(Answers.account(a, id, held, None)))

