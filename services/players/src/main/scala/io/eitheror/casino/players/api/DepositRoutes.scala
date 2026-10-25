package io.eitheror.casino.players.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.Principal
import io.eitheror.casino.players.{Json, Secrets}
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.clients.*
import io.eitheror.casino.players.player.{PlayerEntity, Status}

import java.time.Duration

import Answers.{Reply, *}

/**
 * The player's deposit start (FR-043, R15), in order and bounded: `kyc`'s deposit check, the wallet's
 * sums, `responsible-gambling`'s `may-deposit`, then `ingest`'s cashier under the brand's merchant key
 * with the customer fields 001 FR-018 names. A refusal is passed on naming the service; silence is
 * `503` naming it. No idempotency key: a repeat opens another cashier session, as the original did.
 */
final class DepositRoutes(ctx: Ctx):
  import DepositRoutes.*

  def deposit(p: Principal, playerId: String, text: String): Reply =
    PlayerCaller.matching(p, playerId) { _ =>
      val b = Json.parse(text).filter(_.isObject).getOrElse(Json.obj())
      val amount   = Option(b.get("amount")).filter(n => n.isIntegralNumber && n.canConvertToLong).map(_.longValue).filter(_ > 0)
      val currency = b.text("currency").map(_.trim.toUpperCase).filter(_.matches("[A-Z]{3}"))
      (amount, currency) match
        case (None, _) => refusal(400, "invalid_amount", "amount must be a whole number of minor units above zero")
        case (_, None) => refusal(400, "invalid_currency", "currency must be an ISO 4217 code")
        case (Some(a), Some(c)) =>
          val s = ctx.clients.componentClient.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.get).invoke()
          if !s.exists then refusal(404, "player_not_found", "No such player")
          else if s.status != Status.Active then refusal(403, "account_restricted", "the account is restricted", "reason" -> s.status)
          else checked(playerId, a, c, b.textOr("method", ""), s)
    }

  private def checked(playerId: String, amount: Long, currency: String, method: String, s: io.eitheror.casino.players.player.PlayerState): Reply =
    val n = ctx.neighbours
    n.kyc.depositCheck(playerId, amount, CheckBound) match
      case Raced.Answered(KycDecision.Refused(code, message)) => refusal(403, code, message, "service" -> Kyc.Service)
      case Raced.Answered(KycDecision.Allowed) =>
        n.wallet.depositSums(playerId, currency, CheckBound) match
          case Raced.Answered(sums) =>
            n.rg.mayDeposit(playerId, currency, amount, sums, CheckBound) match
              case Raced.Answered(DepositDecision.Refused(code, body)) =>
                val o = Json.obj("error" -> code, "message" -> body.textOr("message", code.replace('_', ' ')), "service" -> Rg.Service)
                Option(body.get("reason")).foreach(r => o.set[JsonNode]("reason", r): Unit)
                json(403, o)
              case Raced.Answered(DepositDecision.Allowed) => cashier(playerId, amount, currency, method, s)
              case _                                       => silent(Rg.Service)
          case _ => silent(Wallet.Service)
      case _ => silent(Kyc.Service)

  private def cashier(playerId: String, amount: Long, currency: String, method: String, s: io.eitheror.casino.players.player.PlayerState): Reply =
    (ctx.neighbours.ingest, ctx.neighbours.secret(Secrets.MerchantKey)) match
      case (None, _) | (_, Left(_)) => silent(Ingest.Service)
      case (Some(ingest), Right(key)) =>
        val profile = s.profile
        val body = Json.obj("amountCents" -> amount, "currency" -> currency, "customerId" -> s"${ctx.slug}:$playerId",
          "country" -> profile.get("country").getOrElse(""), "redirectUrl" -> s"${ctx.settings.playerSiteBase}/cashier/return",
          "paymentMethod" -> method, "customerEmail" -> s.primary.map(_.address.reveal).getOrElse(""),
          "customerFirstName" -> profile.get("firstName").getOrElse(""), "customerLastName" -> profile.get("lastName").getOrElse(""),
          "customerPhone" -> profile.get("phone").getOrElse(""), "customerMobile" -> profile.get("mobile").getOrElse(""),
          "customerProvince" -> profile.get("province").getOrElse(""), "customerCountry" -> profile.get("country").getOrElse(""),
          "customerCity" -> profile.get("city").getOrElse(""), "customerAddress" -> profile.get("addressLine1").getOrElse(""),
          "customerPostCode" -> profile.get("postalCode").getOrElse(""), "customerState" -> profile.get("stateProv").getOrElse(""))
        ingest.createSession(body, key, CashierBound) match
          case Raced.Answered(CashierOutcome.Opened(url, id))     => ok(Json.obj("redirectUrl" -> url, "paymentId" -> id))
          case Raced.Answered(CashierOutcome.Refused(status, code)) => refusal(status, code, code.replace('_', ' '), "service" -> Ingest.Service)
          case _                                                  => silent(Ingest.Service)

  private def silent(service: String): Reply =
    refusal(503, "service_unavailable", s"$service did not answer", "service" -> service)

object DepositRoutes:
  /** R15's bounds: 500 ms for each check, 2 s for the cashier. */
  val CheckBound: Duration   = Duration.ofMillis(500)
  val CashierBound: Duration = Duration.ofSeconds(2)
