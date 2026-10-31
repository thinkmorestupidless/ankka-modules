package io.eitheror.casino.payouts.api

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.player.*
import io.eitheror.casino.payouts.request.Refusals
import io.eitheror.casino.payouts.terminals.{Catalogue, Eligibility, Terminal}

import java.util.UUID

import Answers.*

/**
 * The player's terminals and saved destinations (US3; contracts/routes.md), apart from the endpoint so a suite
 * can read it. The listing ranks with the rule the request checks with (FR-010), so what is offered and what is
 * accepted cannot differ.
 */
object Accounts:

  /** The query's currency, else the one the player registered in, which is their wallet's. */
  def terminals(ctx: Ctx, playerId: String, query: Option[String]): Reply =
    val state = ctx.playerState(playerId)
    query.map(_.trim).filter(_.nonEmpty).orElse(state.currency).map(_.toUpperCase).flatMap(Currency.from(_).toOption) match
      case None => refused(Refusals.required("currency"))
      case Some(currency) =>
        val offered = Eligibility.eligible(Catalogue.read(ctx.components, ctx.gateway, ctx.slug, currency, ctx.now()), state.deposits)
        ok(Json.obj("terminals" -> Json.arr(offered.map(terminal)*)))

  def terminal(t: Terminal): JsonNode =
    Json.obj("id" -> t.id, "name" -> t.name, "scheme" -> t.scheme, "gateway" -> t.gateway,
      "requiresPayoutDestination" -> t.requiresPayoutDestination, "coins" -> Json.arr(t.coins*))

  /** Active destinations only, the IBAN as its last four. */
  def destinations(ctx: Ctx, playerId: String): Reply =
    val state = ctx.playerState(playerId)
    ok(Json.obj("destinations" -> Json.arr(state.activeDestinations.map(destination(_, currencyOf(state)))*)))

  /**
   * A destination as the original answered it, with the holder's names beside: an IBAN is a bank transfer
   * (`eubanking`, the original's `method`), in the player's currency, as the original defaulted it to EUR.
   */
  def destination(d: Destination, currency: String): JsonNode =
    Json.obj("id" -> d.id, "label" -> d.label.reveal, "firstName" -> d.givenName.reveal, "lastName" -> d.familyName.reveal,
      "ibanLastFour" -> d.iban.reveal.takeRight(4), "scheme" -> BankScheme, "currency" -> currency, "isDefault" -> d.default,
      "createdAt" -> d.createdAt.toString)

  private val BankScheme = "eubanking"

  def currencyOf(state: PlayerState): String = state.currency.getOrElse("EUR")

  /** `{firstName, lastName, iban, label?, setAsDefault?}`, the original's names. */
  def save(ctx: Ctx, playerId: String, b: JsonNode): Reply =
    def field(name: String) = b.text(name).map(_.trim).filter(_.nonEmpty).toRight(Refusals.required(name))
    val saved = for
      first <- field("firstName")
      last  <- field("lastName")
      iban  <- field("iban")
    yield SaveDestination(UUID.randomUUID().toString, first, last, iban, b.text("label").getOrElse(""),
      b.bool("setAsDefault").getOrElse(false), ctx.now())
    saved.fold(refused, c => answered(ctx.player(playerId).call(PlayerPayouts.saveDestination).invoke(c))(d =>
      created(destination(d, currencyOf(ctx.playerState(playerId))))))

  /** `{setAsDefault: true}`, which stands for the original's `set-default`; an already default one writes nothing. */
  def setDefault(ctx: Ctx, playerId: String, id: String, b: JsonNode): Reply =
    if !b.bool("setAsDefault").contains(true) then refused(Refusals.required("setAsDefault"))
    else
      val a = ctx.player(playerId).call(PlayerPayouts.setDefault).invoke(SetDefault(id, ctx.now()))
      if a.refusal.contains(PlayerCodes.AlreadyDefault) then ok(Json.obj("destinationId" -> id, "isDefault" -> true))
      else answered(a)(d => ok(Json.obj("destinationId" -> d.id, "isDefault" -> d.default)))

  def delete(ctx: Ctx, playerId: String, id: String): Reply =
    answered(ctx.player(playerId).call(PlayerPayouts.deleteDestination).invoke(DeleteDestination(id, ctx.now())))(_ => noContent)

  private def answered(a: PlayerAnswer)(f: Destination => Reply): Reply = (a.refusal, a.destination) match
    case (None, Some(d))    => f(d)
    case (None, None)       => refusal(404, PlayerCodes.NotFound, "no such destination")
    case (Some(code), _)    => refusal(status(code), code, message(code))

  private def status(code: String): Int = code match
    case "PLAYER_NOT_FOUND" | PlayerCodes.NotFound => 404
    case PlayerCodes.Deleted                       => 409
    case _                                         => 422

  private def message(code: String): String = code match
    case PlayerCodes.InvalidIban        => "the IBAN is not valid"
    case "PROFILE_INCOMPLETE"           => "your profile has no name to check the destination's against"
    case "PAYOUT_DESTINATION_AML_BLOCKED" => "the destination's name does not match your profile's"
    case PlayerCodes.NotFound           => "no such destination"
    case PlayerCodes.Deleted            => "the destination was deleted"
    case "PLAYER_NOT_FOUND"             => "no such player"
    case other                          => other
