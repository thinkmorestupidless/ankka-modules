package io.eitheror.casino.contract.access

import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.RotatingTls

/**
 * Requests as ankka's server would present them, and its admission rule for each ACL, so the
 * recipes can be decided without a server. The rule mirrors ankka-http's HttpServer: AllowCallers
 * admits by certificate identity, Authenticate decides on the token.
 */
object Requests:

  final case class Request(caller: Caller = Caller.Gateway, token: Option[String] = None, path: String = "/") extends RequestContext:
    def method       = "GET"
    def query        = QueryParams.empty
    def headers      = token.toVector.map(t => "Authorization" -> s"Bearer $t")
    def remoteAddress = None
    def principal    = None

  enum Outcome:
    case Served
    case Refused(status: Int, reason: String)

  def decide(acl: Acl, request: Request, self: RotatingTls.Identity): Outcome = acl match
    case Acl.AllowAll           => Outcome.Served
    case Acl.DenyAll            => Outcome.Refused(403, "deny all")
    case Acl.AllowIf(predicate) => if predicate(request) then Outcome.Served else Outcome.Refused(403, "predicate")
    case Acl.AllowCallers(ms)   => if ms.exists(_.admits(request.caller, self)) then Outcome.Served else Outcome.Refused(403, "caller not admitted")
    case Acl.Authenticate(decide) =>
      decide(request) match
        case AuthDecision.Allow(_)           => Outcome.Served
        case AuthDecision.Unauthenticated(c) => Outcome.Refused(401, c)
        case AuthDecision.Forbidden(r)       => Outcome.Refused(403, r)
        case AuthDecision.Unavailable(r)     => Outcome.Refused(503, r)

  /**
   * Stands in for ankka-auth-oidc's verifier: a token is `<issuer name>|<iss>|<roles,...>`, which a
   * real verifier would have checked the signature of. What is tested is what the recipes decide
   * once a token is verified.
   */
  val verified: Acl = Acl.Authenticate { context =>
    context.header("authorization").map(_.stripPrefix("Bearer ")) match
      case None => AuthDecision.Unauthenticated("realm=\"casino\"")
      case Some(token) =>
        token.split('|') match
          case Array(issuer, iss, roles) =>
            AuthDecision.Allow(Principal("subject", roles = roles.split(',').filter(_.nonEmpty).toSet, claims = Map("iss" -> iss), issuer = Some(issuer)))
          case Array(issuer, iss, roles, claims) =>
            val extra = claims.split(',').toVector.flatMap(_.split('=') match { case Array(k, v) => Some(k -> v); case _ => None }).toMap
            AuthDecision.Allow(Principal("subject", roles = roles.split(',').filter(_.nonEmpty).toSet, claims = extra + ("iss" -> iss), issuer = Some(issuer)))
          case _ => AuthDecision.Unauthenticated("realm=\"casino\", error=\"invalid_token\"")
    }

  val keycloak = "https://auth-casino.example/realms"

  def playerOf(brand: String): String         = s"player|$keycloak/casino-$brand|player"
  def operatorWith(roles: String*): String    = s"staff|$keycloak/eitheror|${roles.mkString(",")}"
  /** A payments operator: roles and the scope claims of decision 0050 (`claim=value,…`). */
  def paymentsOperator(roles: Seq[String], claims: (String, String)*): String =
    s"staff|$keycloak/eitheror|${roles.mkString(",")}|${claims.map((k, v) => s"$k=$v").mkString(",")}"
  /** A token from a brand's player realm presented to a service that configured it under the wrong name. */
  def playerRealmAsStaff(brand: String): String = s"staff|$keycloak/casino-$brand|brand_$brand"
