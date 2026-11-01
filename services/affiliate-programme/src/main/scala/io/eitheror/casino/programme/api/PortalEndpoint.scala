package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.programme.api.Wire.*

/**
 * The affiliate's portal (contracts/routes.md `/v1/my`): an affiliate, acting on their own account or
 * a descendant's; any other target is `404 account_not_found`, as the original. Each story adds its routes.
 */
final class PortalEndpoint(api: Api) extends HttpEndpoint("/v1/my"):
  val acl: Acl = Acl.DenyAll

  withAcl(api.access.affiliate) {
    get("/{affiliate}/account") { (affiliate: String) => asTarget(affiliate)(_ => notBuilt(s"GET /v1/my/$affiliate/account")) }
  }

  /** The route's body when the caller reaches the target, else the original's 404. */
  private def asTarget(target: String)(f: String => Respond[Bytes]): Respond[Bytes] =
    Access.callerOf(principal) match
      case Some(caller) if Access.reaches(caller, target, api.parentOf) => f(caller)
      case _ => refuse(404, Codes.AccountNotFound)
