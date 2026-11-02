package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.api.Answers.{*, given}
import io.eitheror.casino.programme.api.Bodies.{*, given}
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.sealing.{Digest, Identity, Sealing}

/**
 * The managers' backoffice (contracts/routes.md `/v1/affiliates`): a manager of the path's brand, the
 * manager routes for its administrators, and three public routes. Each story adds its routes here.
 */
final class OperatorEndpoint(api: Api) extends HttpEndpoint("/v1/affiliates"):
  val acl: Acl = Acl.DenyAll
  private val reading = Reading(api)

  withAcl(Access.public) {
    post("/accept-invitation") { () => notBuilt("POST /v1/affiliates/accept-invitation") }
    postBody("/{brand}/apply") { (brand: String, body: String) => attempt(withBody[ApplyBody](body)(applyTo(brand, _))) }
    // O19 is not served (change 13): a status changes only through its transitions.
    patchBody("/{brand}/accounts/{affiliate}/status") { (_: String, _: String, _: String) =>
      refuse(404, "not_found", "a membership's status changes through approve, reject, suspend and reactivate")
    }
  }

  withAcl(api.access.manager) {
    get("/{brand}/settings") { (brand: String) => notBuilt(s"GET /v1/affiliates/$brand/settings") }
    postBody("/{brand}/accounts/{affiliate}/approve") { (brand: String, aff: String, body: String) =>
      attempt(withBody[ApproveBody](body)(approve(brand, aff, _)))
    }
    postBody("/{brand}/accounts/{affiliate}/reject") { (brand: String, aff: String, body: String) =>
      attempt(withBody[RejectBody](body)(b => member(brand, aff) { _ =>
        api.components.forEventSourcedEntity(Membership.entityId(aff, brand)).call(Membership.reject)
          .invoke(Reject(b.reason, principal.subject, api.now())): Unit
        ok(Ok(true))
      }))
    }
    get("/{brand}/accounts/{affiliate}") { (brand: String, aff: String) => attempt(member(brand, aff)(m => accountAt(aff, m))) }
    patchBody("/{brand}/accounts/{affiliate}") { (brand: String, aff: String, body: String) =>
      attempt(withBody[AccountPatch](body)(p => member(brand, aff)(_ => patch(brand, aff, p, AccountPatch.clearsOverride(body)))))
    }
    get("/{brand}/accounts/{affiliate}/revenue") { (brand: String, aff: String) =>
      attempt(member(brand, aff)(_ => reading.revenue(Some(aff), Vector(brand), query.raw("period"))))
    }
    get("/{brand}/revenue") { (brand: String) => attempt(reading.revenue(None, Vector(brand), query.raw("period"))) }
    get("/{brand}/revenue/by-campaign") { (brand: String) =>
      attempt(reading.range(query.raw("period"))((_, r) => ok(Reports.byCampaign(reading.rows(None, Vector(brand), r)._2))))
    }
    get("/{brand}/revenue/geo") { (brand: String) =>
      attempt(reading.range(query.raw("period"))((_, r) => ok(Reports.geo(reading.rows(None, Vector(brand), r)._2))))
    }
  }

  withAcl(api.access.managerAdmin) {
    get("/{brand}/managers") { (brand: String) => notBuilt(s"GET /v1/affiliates/$brand/managers") }
  }

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
    val email = b.email.map(_.trim).filter(e => e.contains('@') && e.length <= 254)
    if !api.brand(brand).exists then refuse(404, Codes.BrandUnknown, brand)
    else if email.isEmpty then invalid("email is required")
    else
      val secrets = Secrets(api.secrets)
      secrets.emailIndexKey.fold(e => refuse(503, "store_unavailable", e.reason), { indexKey =>
        val digest = Digest.of(email.get, indexKey)
        val index  = api.components.forKeyValueEntity(EntityId(digest))
        val known  = index.call(EmailIndex.get).invoke().affiliate.filter(a => api.account(a).exists)
        val affiliate = known.getOrElse(Affiliate.newId())
        if known.exists(a => api.membership(a, brand).holds) then refuse(409, Codes.EmailRegistered)
        else
          val made = if known.isDefined then Right(()) else
            index.call(EmailIndex.claim).invoke(affiliate): Unit
            secrets.dataKey(affiliate).map { key =>
              val identity = Identity(b.name.getOrElse(email.get), b.name, Digest.normalised(email.get), b.companyName, b.website, b.trafficSource)
              api.components.forEventSourcedEntity(EntityId(affiliate)).call(Affiliate.createAccount)
                .invoke(CreateAffiliate(affiliate, Sealing.seal(identity, key, affiliate), digest, None, 0,
                  AccountFields(termsVersion = b.termsVersion, termsAcceptedAt = b.termsVersion.map(_ => api.now())), "self", api.now())): Unit
            }
          made.fold(e => refuse(503, "store_unavailable", e.reason), { _ =>
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
            case None =>
              val account = api.components.forEventSourcedEntity(EntityId(affiliate))
              b.parentAffiliateId.foreach(p => account.call(Affiliate.setParent).invoke(SetParent(Some(p), principal.subject, api.now())): Unit)
              b.overridePercent.foreach(p => account.call(Affiliate.setOverride).invoke(SetOverride(p.bp, principal.subject, api.now())): Unit)
              api.components.forEventSourcedEntity(Membership.entityId(affiliate, brand)).call(Membership.approve)
                .invoke(Approve(planId, principal.subject, api.now())): Unit
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
        p.planId.foreach(id => membership.call(Membership.setPlan).invoke(SetPlan(id, principal.subject, api.now())): Unit)
        if clearOverride || p.revShareOverride.isDefined then
          membership.call(Membership.setRevShareOverride)
            .invoke(SetRevShareOverride(p.revShareOverride.map(_.bp), principal.subject, api.now())): Unit
        Patching(api).apply(affiliate, p, principal.subject, manager = true).getOrElse(accountAt(affiliate, api.membership(affiliate, brand)))

