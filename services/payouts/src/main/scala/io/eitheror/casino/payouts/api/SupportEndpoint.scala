package io.eitheror.casino.payouts.api

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.player.PlayerState
import io.eitheror.casino.payouts.request.Refusal
import io.eitheror.casino.payouts.terminals.{Catalogue, Eligibility, Gateways, Terminal}

import scala.util.Try

import Answers.*

/**
 * `/v1/casino/payouts-service/<slug>` (spec 017 amendment A1): `support-agent`'s withdrawal request for a player,
 * after the player approved it. It is the player route's request, through the same gates and workflow, with the
 * body's `key`: `MISSING_KEY` and `KEY_REUSED` answer as there. The chat names no terminal (as the original's did
 * not): a body without one is given the player's default saved destination and the first terminal paying to it
 * that the player may use ([[SupportEndpoint.route]]); a player with neither is `NO_WITHDRAWAL_ROUTE`.
 */
final class SupportEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/payouts-service/${ctx.slug}"):
  val acl: Acl = Routes.serviceOf("support-agent")

  postBody("/players/{playerId}/withdrawal-requests") { (p: String, text: String) =>
    Json.parseObject(text).fold(badBody) { b =>
      if b.text("terminalId").isDefined then Requests.request(ctx, p, b)
      else
        val currency  = b.text("currency").flatMap(c => Try(Currency.unsafe(c.toUpperCase)).toOption)
        val terminals = currency.fold(Vector.empty[Terminal])(c => Catalogue.read(ctx.components, ctx.gateway, ctx.slug, c, ctx.now()))
        SupportEndpoint.route(ctx.playerState(p), terminals) match
          case None => refused(SupportEndpoint.NoRoute)
          case Some((terminal, destination)) =>
            val routed = b.deepCopy[ObjectNode]()
            routed.put("terminalId", terminal)
            routed.put("destinationId", destination)
            Requests.request(ctx, p, routed)
    }
  }

object SupportEndpoint:
  val NoRoute: Refusal = Refusal(409, "NO_WITHDRAWAL_ROUTE",
    "The player has no saved destination and terminal for a withdrawal from support; the cashier can take it")

  /**
   * The terminal and destination of a withdrawal the chat asks for: the player's default active destination, and
   * the first of the terminals the player may use (in `Eligibility`'s order) that pays to a saved destination
   * and needs nothing else (a crypto address or Gigadat's method fields are the cashier's to ask for).
   */
  def route(state: PlayerState, terminals: Vector[Terminal]): Option[(String, String)] =
    for
      destination <- state.activeDestinations.find(_.default)
      terminal <- Eligibility.eligible(terminals, state.deposits)
        .find(t => t.requiresPayoutDestination && !t.crypto && !Gateways.isGigadat(t.gateway))
    yield (terminal.id, destination.id)
