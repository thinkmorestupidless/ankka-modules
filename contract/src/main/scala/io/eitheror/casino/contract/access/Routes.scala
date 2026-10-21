package io.eitheror.casino.contract.access

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Caller, Callers, CallerMatcher, HttpProblem, Principal, RequestContext}
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
    /** An operator of the payments backoffice reading or changing outside their level (spec 004 FR-002). */
    val ScopeViolation  = "scope_violation"
    /** A staff token with no role of the payments backoffice's privilege table (spec 004 FR-033). */
    val NotPaymentsOperator = "not_payments_operator"

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

  /**
   * The payments backoffice's privilege table (spec 004 research R10; the original's
   * `PrivilegeCheck.roleLevels`): a role and its level. An operator holds the highest of theirs.
   */
  val PaymentsRoles: Map[String, Int] = Map(
    "platform_admin"     -> 100,
    "compliance_officer" -> 60,
    "fraud_analyst"      -> 60,
    "resource_owner"     -> 50,
    "resource_admin"     -> 40,
    "support_agent"      -> 30,
    "resource_operator"  -> 30,
    "resource_finance"   -> 25,
    "resource_developer" -> 20,
    "resource_viewer"    -> 10
  )

  /** The claims an operator's level comes from (decision 0050); never `customer_id`, which names the deployment. */
  val ScopeLevelClaim = "eitheror_scope_level"
  val ScopeIdClaim    = "eitheror_scope_id"

  /** The levels an operator of the payments backoffice may hold, as the claim spells them. */
  val PaymentsScopeLevels: Set[String] = Set("platform", "holding", "company", "sub_brand")

  /**
   * An operator of the shared payments (spec 004 FR-033): signed in at the `eitheror` realm, holding
   * a role of the payments privilege table, with a level and, below the platform, the id of the
   * holding, company or sub-brand they stand at. What the level permits is the service's to check
   * against its hierarchy; a token with no level grants nothing rather than everything.
   */
  def paymentsOperator(authenticate: => Acl = Oidc.authenticate()): Acl =
    refine(authenticate) { p =>
      val level = p.claims.get(ScopeLevelClaim).map(_.trim).filter(_.nonEmpty)
      val id    = p.claims.get(ScopeIdClaim).map(_.trim).filter(_.nonEmpty)
      if !p.issuer.contains(StaffIssuer) then Some(s"${Refusal.WrongIssuer}: operators sign in at the eitheror realm")
      else if !p.roles.exists(PaymentsRoles.contains) then Some(s"${Refusal.NotPaymentsOperator}: no role of the payments backoffice")
      else
        level match
          case None => Some(s"${Refusal.ScopeViolation}: the token names no $ScopeLevelClaim")
          case Some(l) if !PaymentsScopeLevels(l) => Some(s"${Refusal.ScopeViolation}: '$l' is not an operator's level")
          case Some(l) if l != "platform" && id.isEmpty => Some(s"${Refusal.ScopeViolation}: the token names no $ScopeIdClaim")
          case _ => None
    }

  /**
   * A service of `project` by its identity, or through the installation's ingress a payments operator
   * (a status page both read, spec 005 FR-023). The project is named because an authenticating ACL is
   * not told the service's own; outside a cluster every caller is local and admitted, as
   * `Acl.allowCallers` admits it.
   */
  def projectOrPaymentsOperator(project: String, authenticate: => Acl = Oidc.authenticate()): Acl =
    lazy val operator = paymentsOperator(authenticate)
    Acl.Authenticate { (context: RequestContext) =>
      context.caller match
        case Caller.Service(`project`, name) => AuthDecision.Allow(Principal(s"service:$project/$name"))
        case Caller.Local                    => AuthDecision.Allow(Principal("local"))
        case Caller.Gateway =>
          operator match
            case Acl.Authenticate(decide) => decide(context)
            case _                        => AuthDecision.Forbidden("not permitted by this endpoint's acl")
        case _ => AuthDecision.Forbidden("not permitted by this endpoint's acl")
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
