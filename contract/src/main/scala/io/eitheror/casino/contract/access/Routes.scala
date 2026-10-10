package io.eitheror.casino.contract.access

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Callers, CallerMatcher, HttpProblem, Principal, RequestContext}
import io.eitheror.casino.contract.Layout
import io.eitheror.casino.contract.Layout.GrantTarget

/**
 * The six route classes of spec 001 FR-020, as the only ACLs a casino service declares
 * (contracts/routes.md, SC-003). Issuers are configured by the brands tool: `player` is the brand's
 * `casino-<slug>` realm, `staff` the `eitheror` realm (research R6). Every check is on the verified
 * token's issuer and realm roles, never on a claim a client stamps (decision 0045).
 */
object Routes:

  /** Refusal codes, as the routes contract names them. */
  object Refusal:
    val WrongBrand      = "wrong_brand"
    val BrandNotInRoles = "brand_not_in_roles"
    val NotAffiliate    = "not_affiliate"
    val NotPartner      = "not_partner"
    val WrongIssuer     = "wrong_issuer"

  /** The issuer names the brands tool renders into `ANKKA_AUTH_ISSUERS`. */
  val PlayerIssuer = "player"
  val StaffIssuer  = "staff"

  /** The project's own brand, from the environment the brands tool renders (`CASINO_BRAND`). */
  def brandFromEnvironment: String =
    sys.env.getOrElse("CASINO_BRAND", throw IllegalStateException("CASINO_BRAND is not set: a brand service is deployed by the brands tool"))

  /** Served with no sign-in: registration, sign-in flows, a click, a join link. A provider's webhook verifies its own signature. */
  val public: Acl = Acl.AllowAll

  /** A player of this brand, signed in at `casino-<slug>`. */
  def player(slug: String, authenticate: => Acl = Oidc.authenticate()): Acl =
    refine(authenticate) { p =>
      if p.issuer.contains(PlayerIssuer) && realmOf(p).contains(s"casino-$slug") then None
      else Some(s"${Refusal.WrongBrand}: this is brand $slug")
    }

  /** An operator whose realm roles name this brand, or a platform administrator. */
  def operator(slug: String, authenticate: => Acl = Oidc.authenticate()): Acl =
    refine(authenticate) { p =>
      if !p.issuer.contains(StaffIssuer) then Some(s"${Refusal.WrongIssuer}: operators sign in at the eitheror realm")
      else if p.roles.contains(s"brand_$slug") || p.roles.contains("platform_admin") then None
      else Some(s"${Refusal.BrandNotInRoles}: no role names brand $slug")
    }

  /** An affiliate, on the programme's routes. */
  def affiliate(authenticate: => Acl = Oidc.authenticate()): Acl =
    refine(authenticate) { p =>
      if p.issuer.contains(StaffIssuer) && p.roles.contains("affiliate") then None
      else Some(s"${Refusal.NotAffiliate}: the affiliate role is required")
    }

  /** A partner's confidential client for this brand: role `partner_<slug>` (spec 016 FR-015). */
  def partner(slug: String, authenticate: => Acl = Oidc.authenticate()): Acl =
    refine(authenticate) { p =>
      if p.issuer.contains(StaffIssuer) && p.roles.contains(s"partner_$slug") then None
      else Some(s"${Refusal.NotPartner}: no partner entitlement for brand $slug")
    }

  /**
   * Another service of this project, by its identity and with no token. For a route a brand grants
   * a shared service (FR-016), that service too, named. ankka 040's `Callers.granted` will replace
   * the named list; until then the list is the grants contract itself (Layout.BrandGrantsMade).
   */
  def service(grantedRoute: Option[GrantTarget.Route] = None): Acl =
    val granted = grantedRoute.toVector.flatMap { route =>
      Layout.BrandGrantsMade.collect {
        case Layout.Grant(principal, `route`) => Layout.grantedCaller(principal)
      }.flatten
    }
    val matchers: Vector[CallerMatcher] = Callers.anyInProject +: granted.map((project, name) => Callers.service(project, name))
    Acl.allowCallers(matchers.head, matchers.tail*)

  /** The routes a brand grants, each with the ACL that admits exactly its grantees. */
  def granted(service: String): Vector[(GrantTarget.Route, Acl)] =
    Layout.BrandGrantsMade.collect { case Layout.Grant(_, r @ GrantTarget.Route(`service`, _, _)) => r }.distinct
      .map(r => r -> Routes.service(Some(r)))

  /** A route's `{brand}` must be this project's (FR-005): a 404 naming the brand it serves. */
  def brandSegment(slug: String, segment: String): Either[HttpProblem, String] =
    if segment == slug then Right(segment) else Left(HttpProblem.notFound(s"${Refusal.WrongBrand}: this is brand $slug"))

  /** The realm a verified token came from: the last segment of its issuer. */
  def realmOf(p: Principal): Option[String] =
    p.claims.get("iss").map(_.stripSuffix("/")).map(_.split('/').last)

  private def refine(base: Acl)(refuse: Principal => Option[String]): Acl =
    base match
      case Acl.Authenticate(decide) =>
        Acl.Authenticate { (context: RequestContext) =>
          decide(context) match
            case allowed @ AuthDecision.Allow(principal) => refuse(principal).fold(allowed)(AuthDecision.Forbidden(_))
            case other                                   => other
        }
      case other => throw IllegalArgumentException(s"a signed-in route needs an authenticating ACL, not $other")
