package io.eitheror.casino.programme.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.programme.api.Wire.*

/** The invite links (contracts/routes.md `/v1/join`), public; US4 builds them. */
final class JoinEndpoint(@annotation.unused api: Api) extends HttpEndpoint("/v1/join"):
  val acl: Acl = Acl.DenyAll

  withAcl(Access.public) {
    get("/{token}") { (token: String) => notBuilt(s"GET /v1/join/$token") }
  }
