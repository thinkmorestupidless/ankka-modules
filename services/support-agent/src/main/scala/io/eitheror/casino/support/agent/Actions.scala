package io.eitheror.casino.support.agent

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.clients.Neighbours
import io.eitheror.casino.support.domain.{Mapping, Summaries}

import java.time.Instant
import scala.util.Try

/**
 * The writes' arguments, checked before the player is asked (FR-011), and the summary the player is asked to
 * confirm (contracts/tools.md), composed from the arguments and the services' answers, never by the model.
 */
object Actions:
  val Periods: Set[String] = Set("daily", "weekly", "monthly")

  private def amount(args: JsonNode): Option[Long] = args.long("amount")

  /**
   * Whether `tool`'s arguments may be put to the player: the player's own currency, an amount above zero, a period
   * the original knew, a break that ends later than now. `Left` is what the model is told.
   */
  def check(tool: String, args: JsonNode, currency: Option[String], now: Instant): Either[String, Unit] =
    def positive = amount(args).filter(_ > 0).toRight("the amount must be a whole number of minor units above zero").map(_ => ())
    def known    = currency.toRight("the player's currency cannot be read right now").map(_ => ())
    tool match
      case "request_withdrawal" =>
        for
          _ <- known
          _ <- args.text("currency").filter(c => currency.contains(c)).toRight(s"withdrawals are made in the player's currency, ${currency.getOrElse("")}")
          _ <- positive
        yield ()
      case "set_deposit_limit" =>
        for
          _ <- known
          _ <- args.text("period").filter(Periods).toRight("period must be 'daily', 'weekly', or 'monthly'")
          _ <- positive
        yield ()
      case "close_active_session" => args.text("game_session_id").toRight("game_session_id is required").map(_ => ())
      case "apply_cool_off" =>
        args.text("cool_off_until").flatMap(t => Try(Instant.parse(t)).toOption)
          .toRight("cool_off_until must be an ISO 8601 instant, as 2026-12-01T00:00:00Z")
          .filterOrElse(_.isAfter(now), "cool_off_until must be later than now").map(_ => ())
      case _ => Right(())

  /**
   * What `confirm_action` states; a withdrawal names the bonus funds the wallet's hold preview says it forfeits, and a
   * deposit limit whether it applies at once, from responsible gambling's current limits (FR-013).
   */
  def summary(n: Neighbours, playerId: String, tool: String, args: JsonNode, currency: Option[String]): String =
    def money = Currency.unsafe(currency.getOrElse(throw IllegalStateException("the player's currency is not known")))
    tool match
      case "request_withdrawal" =>
        val a       = amount(args).getOrElse(0L)
        val forfeit = Some(n.wallet.holdPreview(playerId, money.code, a)).filter(_.ok).flatMap(_.json).flatMap(_.long("totalForfeit"))
        Summaries.withdrawal(a, money, forfeit)
      case "close_active_session" => Summaries.closeSession(args.text("game_session_id").getOrElse(""))
      case "set_deposit_limit" =>
        val (period, a) = (args.text("period").getOrElse(""), amount(args).getOrElse(0L))
        val atOnce = Some(n.rg.limits(playerId, money.code)).filter(_.ok).flatMap(_.json).map(Mapping.depositAppliesAtOnce(_, period, a))
        Summaries.depositLimit(period, a, money, atOnce)
      case "apply_cool_off"       => Summaries.coolOff(Instant.parse(args.text("cool_off_until").getOrElse("")))
      case "apply_self_exclusion" => Summaries.selfExclusion
      case other                  => other
