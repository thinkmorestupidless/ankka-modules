package io.eitheror.casino.kyc.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.kyc.Json

import Answers.*

/** `/v1/casino/kyc-requirements/<slug>`: the categories the brand requires, to anyone, and nothing else of the policy (FR-029). */
final class PublicEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/kyc-requirements/${ctx.slug}"):
  val acl: Acl = Routes.public

  get("") { () => ok(Json.obj("requiredDocTypes" -> ctx.policy.required.map(_.wire))) }
