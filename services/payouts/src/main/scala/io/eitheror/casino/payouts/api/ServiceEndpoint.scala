package io.eitheror.casino.payouts.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.player.{Erase as ErasePlayer, PlayerPayouts, PlayerState}
import io.eitheror.casino.payouts.request.{Erase, WithdrawalRequest}

import Answers.*

/**
 * `/v1/casino/brands/<slug>/payouts` (contracts/routes.md; R12): the erasure and the export `players`' `ErasureWorkflow`
 * calls on every target, until ankka 042's `erase(subject)`. The player's record and each of their
 * requests blank every `Personal` value and keep ids, amounts, statuses and times. The requests are those
 * the record counts and those the queue holds, so one opened a moment ago is not missed for a lagging view.
 * It runs in full on every call: a repeat erases nothing further and answers the same.
 */
final class ServiceEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/payouts"):
  val acl: Acl = Routes.serviceOf("players")

  private def requestsOf(record: PlayerState, p: String): Vector[String] =
    (record.recent.map(_.id) ++ ctx.byPlayer(p).map(_.requestId)).distinct

  post("/erasure/players/{playerId}") { (p: String) =>
    val at       = ctx.now()
    val record   = ctx.player(p).call(PlayerPayouts.erase).invoke(ErasePlayer(at)).state
    val requests = requestsOf(record, p)
    requests.foreach(id => ctx.request(id).call(WithdrawalRequest.erase).invoke(Erase(at)): Unit)
    ok(Json.obj("erased" -> Json.arr(requests*)))
  }

  /**
   * The player's part of their export (spec 008 FR-040; found during implementation, research.md): their
   * destinations as the player sees them, and each request with its money, statuses and times. The whole IBAN
   * and the operators' reasons are not the player's to take away, as kyc's part leaves out its objects.
   */
  get("/exports/players/{playerId}") { (p: String) =>
    val record = ctx.playerState(p)
    val requests = requestsOf(record, p).map(ctx.requestState).filter(_.opened).sortBy(_.createdAt).map { s =>
      Json.obj("requestId" -> s.requestId, "amount" -> s.amount, "paidAmount" -> s.paidAmount, "currency" -> s.currency,
        "terminal" -> s.terminal.map(_.name), "status" -> s.status.upper, "payoutStatus" -> s.payoutStatus.upper,
        "createdAt" -> s.createdAt.toString, "initiatedAt" -> s.initiatedAt.map(_.toString), "settledAt" -> s.settledAt.map(_.toString))
    }
    ok(Json.obj("service" -> "payouts", "playerId" -> p,
      "destinations" -> Json.arr(record.activeDestinations.map(Accounts.destination(_, Accounts.currencyOf(record)))*), "requests" -> Json.arr(requests*)))
  }
