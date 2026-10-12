package io.eitheror.casino.simulator.api

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, RequestContext}
import io.eitheror.casino.contract.access.Routes

/**
 * The simulator's ACLs (FR-027; contracts/routes.md), every one a recipe of `contract.access.Routes`
 * and nothing else: public for what a brand, a game client or the original's suite calls; operator
 * for everything else. An operator route names the brand it acts on in its path, and is held to
 * `Routes.operator(<that brand>)` (research R2); the scenario library and the status are held to
 * `Routes.operator("tools")`.
 */
object Access:
  val public: Acl = Routes.public

/** @param authenticate the verifier `Routes` refines; `Oidc.authenticate()` in a deployment. */
final class Access(authenticate: => Acl = Oidc.authenticate()):

  private lazy val verifier = authenticate

  /** `Routes.operator(brand)` for the brand the path names under `/v1/simulator/brands/{brand}/`. */
  val byBrand: Acl = Acl.Authenticate { (context: RequestContext) =>
    val brand = context.path.split('/').filter(_.nonEmpty).toVector match
      case "v1" +: "simulator" +: "brands" +: slug +: _ => slug
      case _                                             => ""
    decide(Routes.operator(brand, verifier), context)
  }

  val tools: Acl = Routes.operator("tools", verifier)

  private def decide(acl: Acl, context: RequestContext): AuthDecision = acl match
    case Acl.Authenticate(f) => f(context)
    case other               => AuthDecision.Forbidden(s"unexpected ACL $other")
