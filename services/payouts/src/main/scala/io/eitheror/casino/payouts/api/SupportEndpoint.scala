package io.eitheror.casino.payouts.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.payouts.Json

import Answers.*

/**
 * `/v1/casino/payouts-service/<slug>` (spec 017 amendment A1): `support-agent`'s withdrawal request for a player,
 * after the player approved it. It is the player route's request, through the same gates and workflow, with the
 * body's `key`: `MISSING_KEY` and `KEY_REUSED` answer as there.
 */
final class SupportEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/payouts-service/${ctx.slug}"):
  val acl: Acl = Routes.serviceOf("support-agent")

  postBody("/players/{playerId}/withdrawal-requests") { (p: String, text: String) =>
    Json.parseObject(text).fold(badBody)(Requests.request(ctx, p, _))
  }
