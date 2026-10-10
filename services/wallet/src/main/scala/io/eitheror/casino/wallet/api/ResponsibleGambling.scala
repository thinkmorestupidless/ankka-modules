package io.eitheror.casino.wallet.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}
import com.thinkmorestupidless.ankka.core.Codecs
import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.wallet.entity.DepositSums

import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * `responsible-gambling`'s two checks as the wallet asks them, within the project (FR-007a, FR-022;
 * spec 010 FR-007, FR-010). The endpoints ask; an entity never calls out (research R6).
 *
 * Fail closed (spec 010 FR-011): a timeout, a connection failure, a 5xx or an answer the wallet
 * cannot read is `Unavailable`, and the command is not sent.
 */
enum RgAnswer:
  case Allowed
  case Locked(reason: String)
  case Excluded
  case CoolingOff(endsAt: Option[String])
  case OverLimit(period: String, limit: Long, used: Long, remaining: Long)
  case Unavailable(reason: String)

/** The wire of the two checks; spec 010 serves them (contracts/routes.md, "Calls the wallet makes"). */
final case class MayBetRequest(playerId: String, currency: String, amount: Long)
final case class MayDepositRequest(playerId: String, currency: String, amount: Long, last24h: Long, last7d: Long, last30d: Long)
final case class RgDecision(decision: String, reason: Option[String] = None, endsAt: Option[String] = None,
    period: Option[String] = None, limit: Option[Long] = None, used: Option[Long] = None, remaining: Option[Long] = None)

trait ResponsibleGambling:
  def mayBet(playerId: String, currency: String, amount: Long): RgAnswer
  def mayDeposit(playerId: String, currency: String, amount: Long, sums: DepositSums): RgAnswer

object ResponsibleGambling:
  val ServiceName = "responsible-gambling"
  val Timeout: FiniteDuration = 500.millis

  given JsonValueCodec[MayBetRequest]     = Codecs.make[MayBetRequest]
  given JsonValueCodec[MayDepositRequest] = Codecs.make[MayDepositRequest]
  given JsonValueCodec[RgDecision]        = Codecs.make[RgDecision]

  def mayBetPath(brand: String): String     = s"/v1/casino/brands/$brand/rg/may-bet"
  def mayDepositPath(brand: String): String = s"/v1/casino/brands/$brand/rg/may-deposit"

  /** An answer as the wallet reads it; anything it cannot read is unavailable. */
  def read(decision: RgDecision): RgAnswer = decision.decision match
    case "allowed"     => RgAnswer.Allowed
    case "locked"      => RgAnswer.Locked(decision.reason.getOrElse("locked"))
    case "excluded"    => RgAnswer.Excluded
    case "cooling_off" => RgAnswer.CoolingOff(decision.endsAt)
    case "over_limit" =>
      (decision.limit, decision.used, decision.remaining) match
        case (Some(l), Some(u), Some(r)) => RgAnswer.OverLimit(decision.period.getOrElse(""), l, u, r)
        case _                           => RgAnswer.Unavailable("an over-limit answer without its figures")
    case other => RgAnswer.Unavailable(s"an answer the wallet does not know: '$other'")

  /** Over HTTP to the project's `responsible-gambling`, within `timeout`. */
  def http(services: ServiceClients, brand: String, timeout: FiniteDuration = Timeout): ResponsibleGambling =
    new ResponsibleGambling:
      private def ask(path: String, body: Array[Byte]): RgAnswer =
        try
          val response = CompletableFuture
            .supplyAsync(() => services(ServiceName).request("POST", path, Some(body), Some("application/json")))
            .get(timeout.toMillis, TimeUnit.MILLISECONDS)
          if response.status / 100 == 2 then read(readFromArray[RgDecision](response.body))
          else RgAnswer.Unavailable(s"responsible-gambling answered ${response.status}")
        catch
          case _: java.util.concurrent.TimeoutException => RgAnswer.Unavailable(s"no answer within ${timeout.toMillis} ms")
          case NonFatal(e)                              => RgAnswer.Unavailable(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))

      def mayBet(playerId: String, currency: String, amount: Long): RgAnswer =
        ask(mayBetPath(brand), writeToArray(MayBetRequest(playerId, currency, amount)))

      def mayDeposit(playerId: String, currency: String, amount: Long, sums: DepositSums): RgAnswer =
        ask(mayDepositPath(brand), writeToArray(MayDepositRequest(playerId, currency, amount, sums.last24h, sums.last7d, sums.last30d)))

  /** The refusal code, message and named figures of a refusing answer (FR-007a, FR-022; contracts/wire.md). */
  def refusal(answer: RgAnswer): Option[(String, String, Map[String, String])] = answer match
    case RgAnswer.Allowed           => None
    case RgAnswer.Locked(_)         => Some(("PLAYER_LOCKED", "Player is locked", Map.empty))
    case RgAnswer.Excluded          => Some(("EXCLUDED", "Player is excluded", Map.empty))
    case RgAnswer.CoolingOff(endsAt) =>
      Some(("COOLING_OFF", endsAt.fold("Player is cooling off")(e => s"Player is cooling off until $e"), endsAt.map("endsAt" -> _).toMap))
    case RgAnswer.OverLimit(period, limit, used, remaining) =>
      Some(("OVER_LIMIT", s"Deposit exceeds the $period limit of $limit",
        Map("period" -> period, "limit" -> limit.toString, "used" -> used.toString, "remaining" -> remaining.toString)))
    case RgAnswer.Unavailable(_) => Some(("RG_UNAVAILABLE", "Responsible gambling check unavailable", Map.empty))
