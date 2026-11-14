package io.eitheror.casino.support.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes

import java.nio.charset.StandardCharsets.UTF_8

/** `/v1/casino/agent-service/<slug>`: the service's health, for the project's services (contracts/routes.md). */
final class ServiceEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/agent-service/${ctx.slug}"):
  val acl: Acl = Routes.service()

  get("/health") { () => Respond(Bytes("application/json", """{"status":"ok"}""".getBytes(UTF_8)), 200, Vector.empty) }
