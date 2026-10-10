package io.eitheror.casino.sample

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes

/** `GET /v1/casino/brands/{brand}/access/<class>` answers `served` when the class's recipe admits the caller. */
final class AccessEndpoint(brand: String) extends HttpEndpoint("/v1/casino/brands"):

  val acl: Acl = Acl.DenyAll

  private def answer(segment: String): String =
    Routes.brandSegment(brand, segment).fold(problem => throw problem, _ => "served")

  withAcl(Routes.public)(get("/{brand}/access/public")((b: String) => answer(b)))
  withAcl(Routes.player(brand))(get("/{brand}/access/player")((b: String) => answer(b)))
  withAcl(Routes.operator(brand))(get("/{brand}/access/operator")((b: String) => answer(b)))
  withAcl(Routes.affiliate())(get("/{brand}/access/affiliate")((b: String) => answer(b)))
  withAcl(Routes.partner(brand))(get("/{brand}/access/partner")((b: String) => answer(b)))
  withAcl(Routes.service())(get("/{brand}/access/service")((b: String) => answer(b)))
