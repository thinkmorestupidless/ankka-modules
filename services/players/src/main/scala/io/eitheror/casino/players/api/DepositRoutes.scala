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
 * with the customer fields 001 FR-018 names. A refusal is passed on naming the service, with the figures
 * it answered (kyc's `kycStatus`; responsible gambling's `endsAt`, `period`, `limit`, `used`,
 * `remaining`); silence is `503` naming it. A `requestId` the player sends is the cashier session's
 * `transactionId`, so the cashier answers a repeat with the first session and refuses one with another
 * body as `409 REQUEST_ID_REUSED`; without one the cashier mints it, and a repeat opens another session,
 * as the original did. The answer carries the cashier ticket and is never logged (spec 027 FR-018).
 */
final class DepositRoutes(ctx: Ctx):
  import DepositRoutes.*

  def deposit(p: Principal, playerId: String, text: String): Reply =
    PlayerCaller.matching(p, playerId) { _ =>
      val b = Json.parse(text).filter(_.isObject).getOrElse(Json.obj())
      val amount   = Option(b.get("amount")).filter(n => n.isIntegralNumber && n.canConvertToLong).map(_.longValue).filter(_ > 0)
      val currency = b.text("currency").map(_.trim.toUpperCase).filter(_.matches("[A-Z]{3}"))
      val requestId = requested(b)
      (amount, currency, requestId) match
        case (None, _, _) => refusal(400, "invalid_amount", "amount must be a whole number of minor units above zero")
        case (_, None, _) => refusal(400, "invalid_currency", "currency must be an ISO 4217 code")
        case (_, _, Left(_)) => refusal(400, "invalid_request_id", "requestId must be 1 to 64 letters, digits, '-' or '_'")
        case (Some(a), Some(c), Right(r)) =>
          val s = ctx.clients.componentClient.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.get).invoke()
          if !s.exists then refusal(404, "player_not_found", "No such player")
          else if s.status != Status.Active then refusal(403, "account_restricted", "the account is restricted", "reason" -> s.status)
          else checked(playerId, a, c, b.textOr("method", ""), r, s)
    }

  private def checked(playerId: String, amount: Long, currency: String, method: String, requestId: Option[String],
                      s: io.eitheror.casino.players.player.PlayerState): Reply =
    val n = ctx.neighbours
    n.kyc.depositCheck(playerId, amount, currency, CheckBound) match
      case Raced.Answered(KycDecision.Refused(code, message, kycStatus)) =>
        refusal(403, code, message, "service" -> Kyc.Service, "kycStatus" -> kycStatus)
      case Raced.Answered(KycDecision.Allowed) =>
        n.wallet.depositSums(playerId, currency, CheckBound) match
          case Raced.Answered(sums) =>
            n.rg.mayDeposit(playerId, currency, amount, sums, CheckBound) match
              case Raced.Answered(DepositDecision.Refused(code, body)) =>
                val o = Json.obj("error" -> code, "message" -> body.textOr("message", code.replace('_', ' ')), "service" -> Rg.Service)
                RgFigures.foreach(f => Option(body.get(f)).filterNot(_.isNull).foreach(v => o.set[JsonNode](f, v): Unit))
                json(403, o)
              case Raced.Answered(DepositDecision.Allowed) => cashier(playerId, amount, currency, method, requestId, s)
              case _                                       => silent(Rg.Service)
          case _ => silent(Wallet.Service)
      case _ => silent(Kyc.Service)

  private def cashier(playerId: String, amount: Long, currency: String, method: String, requestId: Option[String],
                      s: io.eitheror.casino.players.player.PlayerState): Reply =
    (ctx.neighbours.ingest, ctx.neighbours.secret(Secrets.MerchantKey)) match
      case (None, _) | (_, Left(_)) => silent(Ingest.Service)
      case (Some(ingest), Right(key)) =>
        val profile = s.profile
        // The player's own country, else the one registration found from their address (spec 027): the
        // original's page sent that one itself; a player who gave none can still deposit.
        val country = profile.get("country").orElse(s.registration.networkCountry).getOrElse("")
        val body = Json.obj("amountCents" -> amount, "currency" -> currency, "customerId" -> s"${ctx.slug}:$playerId",
          "country" -> country, "redirectUrl" -> s"${ctx.settings.playerSiteBase}/cashier/return",
          "paymentMethod" -> method, "customerEmail" -> s.primary.map(_.address.reveal).getOrElse(""),
          "customerFirstName" -> profile.get("firstName").getOrElse(""), "customerLastName" -> profile.get("lastName").getOrElse(""),
          "customerPhone" -> profile.get("phone").getOrElse(""), "customerMobile" -> profile.get("mobile").getOrElse(""),
          "customerProvince" -> profile.get("province").getOrElse(""), "customerCountry" -> country,
          "customerCity" -> profile.get("city").getOrElse(""), "customerAddress" -> profile.get("addressLine1").getOrElse(""),
          "customerPostCode" -> profile.get("postalCode").getOrElse(""), "customerState" -> profile.get("stateProv").getOrElse(""),
          "transactionId" -> requestId)
        ingest.createSession(body, key, CashierBound) match
          case Raced.Answered(CashierOutcome.Opened(url, id, ticket, terminals)) =>
            ok(Json.obj("redirectUrl" -> url, "paymentId" -> id, "cashierTicket" -> ticket, "terminals" -> terminals, "requestId" -> id))
          case Raced.Answered(CashierOutcome.Refused(status, code, fields)) =>
            val named: Vector[(String, Any)] = if code == "REQUEST_ID_REUSED" then requestId.map("requestId" -> _).toVector else Vector.empty
            refusal(status, code, code.replace('_', ' '), ((fields: Vector[(String, Any)]) ++ named :+ ("service" -> Ingest.Service))*)
          case _ => silent(Ingest.Service)

  private def silent(service: String): Reply =
    refusal(503, "service_unavailable", s"$service did not answer", "service" -> service)

object DepositRoutes:
  /** What a request id may be (spec 027 `contracts/amendments.md`): within ingest's `REQUEST_ID_BOUND`. */
  private val RequestId = "[A-Za-z0-9_-]{1,64}".r

  /** The figures of responsible gambling's `may-deposit` refusal passed on with it (010 `contracts/routes.md`). */
  private val RgFigures = Vector("reason", "endsAt", "period", "limit", "used", "remaining")

  /** The body's `requestId`: none when absent or `null`, refused when it is not one. */
  def requested(b: JsonNode): Either[String, Option[String]] =
    Option(b.get("requestId")).filterNot(_.isNull) match
      case None                                             => Right(None)
      case Some(n) if n.isTextual && RequestId.matches(n.asText) => Right(Some(n.asText))
      case Some(_)                                          => Left("invalid_request_id")

  /** R15's bounds: 500 ms for each check, 2 s for the cashier. */
  val CheckBound: Duration   = Duration.ofMillis(500)
  val CashierBound: Duration = Duration.ofSeconds(2)
