package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.programme.api.Invitations.{JoinBody, given}
import io.eitheror.casino.programme.api.Wire.*

/** The invite links (contracts/routes.md `/v1/join`, P3 and P4), public; the brand is a query parameter, as the original. */
final class JoinEndpoint(api: Api) extends HttpEndpoint("/v1/join"):
  val acl: Acl = Acl.DenyAll
  private val invitations = Invitations(api)

  withAcl(Access.public) {
    get("/{token}") { (token: String) => attempt(invitations.preview(token, query.raw("brandId"))) }
    postBody("/{token}") { (token: String, body: String) =>
      attempt(withBody[JoinBody](body)(b => invitations.join(token, query.raw("brandId"), b)))
    }
  }
