package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.programme.api.Wire.*

/**
 * The programme's own surface (contracts/routes.md `/v1/programme`): the documents and the inbound
 * webhooks, public (the webhook checks its own signature); hosted brands, the legacy withdrawal and
 * the usage report for a platform administrator.
 */
final class ProgrammeEndpoint(api: Api) extends HttpEndpoint("/v1/programme"):
  val acl: Acl = Acl.DenyAll

  withAcl(Access.public) {
    get("/documents/keys") { () => notBuilt("GET /v1/programme/documents/keys") }
  }

  withAcl(api.access.platformAdmin) {
    get("/brands") { () => notBuilt("GET /v1/programme/brands") }
  }
