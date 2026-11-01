package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.programme.api.Wire.*

/**
 * The managers' backoffice (contracts/routes.md `/v1/affiliates`): a manager of the path's brand, the
 * manager routes for its administrators, and three public routes. Each story adds its routes here.
 */
final class OperatorEndpoint(api: Api) extends HttpEndpoint("/v1/affiliates"):
  val acl: Acl = Acl.DenyAll

  withAcl(Access.public) {
    post("/accept-invitation") { () => notBuilt("POST /v1/affiliates/accept-invitation") }
  }

  withAcl(api.access.manager) {
    get("/{brand}/settings") { (brand: String) => notBuilt(s"GET /v1/affiliates/$brand/settings") }
  }

  withAcl(api.access.managerAdmin) {
    get("/{brand}/managers") { (brand: String) => notBuilt(s"GET /v1/affiliates/$brand/managers") }
  }
