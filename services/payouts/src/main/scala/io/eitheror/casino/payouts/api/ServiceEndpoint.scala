package io.eitheror.casino.payouts.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.player.{Erase as ErasePlayer, PlayerPayouts}
import io.eitheror.casino.payouts.request.{Erase, WithdrawalRequest}

import Answers.*

/**
 * `/v1/casino/brands/<slug>/payouts` (contracts/routes.md; R12): the erasure `players`' `ErasureWorkflow`
 * calls on every target, until ankka 042's `erase(subject)`. The player's record and each of their
 * requests blank every `Personal` value and keep ids, amounts, statuses and times. The requests are those
 * the record counts and those the queue holds, so one opened a moment ago is not missed for a lagging view.
 * It runs in full on every call: a repeat erases nothing further and answers the same.
 */
final class ServiceEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/payouts"):
  val acl: Acl = Routes.serviceOf("players")

  post("/erasure/players/{playerId}") { (p: String) =>
    val at       = ctx.now()
    val record   = ctx.player(p).call(PlayerPayouts.erase).invoke(ErasePlayer(at)).state
    val requests = (record.recent.map(_.id) ++ ctx.byPlayer(p).map(_.requestId)).distinct
    requests.foreach(id => ctx.request(id).call(WithdrawalRequest.erase).invoke(Erase(at)): Unit)
    ok(Json.obj("erased" -> Json.arr(requests*)))
  }
