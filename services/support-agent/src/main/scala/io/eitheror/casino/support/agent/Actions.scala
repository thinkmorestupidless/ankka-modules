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
   * Whether what the model named is the player's own (FR-011): a game session to close must be one of the player's, as
   * players lists them, since players ends any session of the brand it is given.
   */
  def owned(n: Neighbours, playerId: String, tool: String, args: JsonNode): Either[String, Unit] = tool match
    case "close_active_session" =>
      val id       = args.text("game_session_id").getOrElse("")
      val sessions = n.players.sessions(playerId)
      if !sessions.ok then Left("the player's game sessions cannot be read right now")
      else if sessions.json.toVector.flatMap(_.objects("sessions")).exists(_.text("sessionId").contains(id)) then Right(())
      else Left("game_session_id is not one of the player's game sessions; read them with get_session_history")
    case _ => Right(())

  /**
   * What `confirm_action` states; a withdrawal names the bonus funds the wallet's hold preview says it forfeits, and a
   * deposit limit whether it applies at once, from responsible gambling's current limits (FR-013).
   */
  def summary(n: Neighbours, playerId: String, tool: String, args: JsonNode, currency: Option[String]): String =
    prepare(n, playerId, tool, args, currency)._1

  /** The summary, and for a withdrawal the forfeit it names: none when the preview names none or cannot be read. */
  def prepare(n: Neighbours, playerId: String, tool: String, args: JsonNode, currency: Option[String]): (String, Option[Long]) =
    def money = Currency.unsafe(currency.getOrElse(throw IllegalStateException("the player's currency is not known")))
    tool match
      case "request_withdrawal" =>
        val a       = amount(args).getOrElse(0L)
        val named   = forfeit(n, playerId, money.code, a).filter(_ > 0)
        (Summaries.withdrawal(a, money, named), named)
      case other => (describe(n, playerId, other, args, money), None)

  /** The bonus funds the wallet's hold preview says a withdrawal of `amount` forfeits, when it can be read. */
  def forfeit(n: Neighbours, playerId: String, currency: String, amount: Long): Option[Long] =
    Some(n.wallet.holdPreview(playerId, currency, amount)).filter(_.ok).flatMap(_.json).flatMap(_.long("totalForfeit"))

  /**
   * Whether the withdrawal may confirm the forfeit (FR-013): only one its summary named, and only while the preview
   * still reads no larger; otherwise payouts is asked without it, and answers its confirm-forfeit refusal if one is due.
   */
  def confirmsForfeit(named: Option[Long], now: Option[Long]): Boolean =
    named.exists(f => now.exists(_ <= f))

  private def describe(n: Neighbours, playerId: String, tool: String, args: JsonNode, money: => Currency): String =
    tool match
      case "close_active_session" => Summaries.closeSession(args.text("game_session_id").getOrElse(""))
      case "set_deposit_limit" =>
        val (period, a) = (args.text("period").getOrElse(""), amount(args).getOrElse(0L))
        val atOnce = Some(n.rg.limits(playerId, money.code)).filter(_.ok).flatMap(_.json).map(Mapping.depositAppliesAtOnce(_, period, a))
        Summaries.depositLimit(period, a, money, atOnce)
      case "apply_cool_off"       => Summaries.coolOff(Instant.parse(args.text("cool_off_until").getOrElse("")))
      case "apply_self_exclusion" => Summaries.selfExclusion
      case other                  => other
