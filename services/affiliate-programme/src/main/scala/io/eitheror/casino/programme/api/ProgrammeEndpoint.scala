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

  private val documents = Documents(api)

  withAcl(Access.public) {
    get("/documents/keys") { () => attempt(documents.keys) }
    get("/documents/revocations") { () => attempt(documents.revocations) }
    get("/documents/legacy-tags") { () => attempt(documents.legacyTags) }
    get("/documents/codes/{brand}") { (brand: String) => attempt(documents.codes(brand)) }
  }

  withAcl(api.access.platformAdmin) {
    post("/documents/legacy-tags/withdraw") { () => attempt(documents.withdraw(principal.subject)) }
    get("/brands") { () => notBuilt("GET /v1/programme/brands") }
  }
