package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Principal, RequestContext}
import io.eitheror.casino.contract.access.Routes

/**
 * The programme's ACLs (research R11, R12), each a recipe of `contract.access.Routes` refined:
 * a manager acts only at a brand `Routes.brandsOf` holds, read from the path's `{brand}` under
 * `/v1/affiliates/` (`403 brand_not_held`), as the simulator's `Access`; the manager routes need
 * `brand_admin` or `platform_admin`, as the original; hosted brands and the usage report need
 * `platform_admin`. The portal's own check, that the target is the caller or a descendant, is
 * [[Access.reaches]], made by the route with the accounts it reads.
 */
object Access:
  val public: Acl = Routes.public

  /** The brand a manager's route names: the segment after `/v1/affiliates/`. */
  def brandOfPath(path: String): Option[String] =
    path.split('/').filter(_.nonEmpty).toVector match
      case "v1" +: "affiliates" +: brand +: _ => Some(brand)
      case _                                  => None

  /** The account a portal token names (`eitheror_scope_id`, as the original provisions it). */
  def callerOf(p: Principal): Option[String] = p.claims.get(Routes.ScopeIdClaim).map(_.trim).filter(_.nonEmpty)

  /** The longest chain of parents walked: deeper than any network the original allowed. */
  val MaxDepth = 64

  /** Whether `target` is `caller` or below it, walking up from the target by `parentOf`. */
  def reaches(caller: String, target: String, parentOf: String => Option[String]): Boolean =
    Iterator.iterate(Option(target))(_.flatMap(parentOf)).take(MaxDepth + 1).takeWhile(_.isDefined).flatten.contains(caller)

  val ManagerAdminRoles: Set[String] = Set("brand_admin", "platform_admin")

/** @param authenticate the verifier `Routes` refines; `Oidc.authenticate()` in a deployment. */
final class Access(authenticate: => Acl = Oidc.authenticate()):
  private lazy val verifier = authenticate

  private def refine(base: Acl)(refuse: (Principal, RequestContext) => Option[String]): Acl =
    Acl.Authenticate { (context: RequestContext) =>
      base match
        case Acl.Authenticate(decide) =>
          decide(context) match
            case allowed @ AuthDecision.Allow(p) => refuse(p, context).fold(allowed)(AuthDecision.Forbidden(_))
            case other                           => other
        case other => AuthDecision.Forbidden(s"unexpected ACL $other")
    }

  private def holdsPathBrand(p: Principal, context: RequestContext): Option[String] =
    Access.brandOfPath(context.path) match
      case Some(brand) if Routes.brandsOf(p).holds(brand) => None
      case Some(brand)                                    => Some(s"${Wire.Codes.BrandNotHeld}: $brand is not among your brands")
      case None                                           => Some(s"${Wire.Codes.BrandNotHeld}: the route names no brand")

  /** An affiliate manager of the path's brand. */
  lazy val manager: Acl = refine(Routes.affiliateManager(verifier))(holdsPathBrand)

  /** A brand or platform administrator of the path's brand: the manager routes (O1 to O3). */
  lazy val managerAdmin: Acl = refine(Routes.affiliateManager(verifier)) { (p, c) =>
    if !p.roles.exists(Access.ManagerAdminRoles) then Some(s"${Routes.NotAffiliateManager}: brand_admin or platform_admin is required")
    else holdsPathBrand(p, c)
  }

  /** A platform administrator: hosted brands, the legacy withdrawal and the usage report. */
  lazy val platformAdmin: Acl = refine(Routes.affiliateManager(verifier)) { (p, _) =>
    if p.roles.contains("platform_admin") then None else Some(s"${Routes.NotAffiliateManager}: platform_admin is required")
  }

  /** An affiliate, signed in at the `eitheror` realm; the target is checked by the route. */
  lazy val affiliate: Acl = Routes.affiliate(verifier)
