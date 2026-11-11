package io.eitheror.casino.support.agent

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.agent.FunctionTool
import io.eitheror.casino.support.Json
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.clients.{Called, Neighbours}
import io.eitheror.casino.support.config.ConfigBody
import io.eitheror.casino.support.conversation.{EndReason, Outcome, ToolCall}
import io.eitheror.casino.support.domain.{Mapping, OwnIds, Redaction}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

/**
 * The tools one turn offers (contracts/tools.md), built in the handler for the bound player: every call names
 * that player, never one the model gives (FR-005), and goes to the owning service as `support-agent`. Each call is
 * recorded on the conversation with its redacted arguments and its outcome (FR-028). A read that does not answer is
 * told to the model as "cannot be read right now", and the turn goes on (FR-012). A write waits for the player's
 * approval (FR-009) and is keyed by it (FR-015); an applied break or exclusion ends the conversation (FR-017).
 */
final class Tools(n: Neighbours, trail: Trail, playerId: String, body: ConfigBody, now: () => Instant):

  /** The player's currency, from players' status (P2), read once per turn when a tool first needs it. */
  lazy val currency: Option[String] = Context.playerState(n, playerId, body).toOption.flatMap(_.text("currency"))

  /** The ids the turn may name as the player's (FR-021): every answer a tool gives is added. */
  val own: OwnIds = OwnIds(playerId, () => Tools.records(n, playerId, currency))

  private def outcomeOf(c: Called): Outcome = c match
    case Called.TimedOut    => Outcome.TimedOut
    case Called.Unavailable => Outcome.Unavailable
    case _                  => c.refusal.fold(Outcome.Ok)(Outcome.Refused(_))

  private def record(tool: String, args: JsonNode, outcome: Outcome, approval: Option[String] = None, detail: Option[String] = None): Unit =
    trail.called(ToolCall(UUID.randomUUID().toString, tool, Redaction.arguments(args), outcome, approval, detail, now()))

  /**
   * A read: `call` answers the neighbour's call (or none when the player's currency cannot be read), `map` the
   * model's view of a good answer. What does not answer is the unreadable reply, recorded with its outcome.
   */
  private def read(tool: String, args: JsonNode = Json.obj())(call: => Option[Called])(map: Called => JsonNode): String =
    call match
      case None =>
        record(tool, args, Outcome.Unavailable)
        Context.Unreadable
      case Some(c) =>
        val outcome = outcomeOf(c)
        record(tool, args, outcome)
        outcome match
          case Outcome.Ok =>
            val answer = Json.render(map(c))
            own.add(answer)
            answer
          case _          => Context.failure(c)

  private def json(c: Called): JsonNode = c.json.getOrElse(Json.obj())

  val reads: Vector[FunctionTool] = Vector(
    FunctionTool.named("get_player_state")
      .describedAs("Get the player's account status, KYC, email, and profile information.")
      .handle { () =>
        Context.playerState(n, playerId, body) match
          case Right(state) =>
            record("get_player_state", Json.obj(), Outcome.Ok)
            Json.render(state)
          case Left(code) =>
            record("get_player_state", Json.obj(), if code == Context.Unreadable then Outcome.Unavailable else Outcome.Refused(code))
            code
      },
    FunctionTool.named("get_wallet_balance")
      .describedAs("Get the player's current real and bonus wallet balances.")
      .handle { () =>
        currency match
          case None => read("get_wallet_balance")(None)(json)
          case Some(c) =>
            // A wallet not yet opened is zeros, as the original (contracts/neighbours.md).
            n.wallet.balance(playerId, c) match
              case Called.Answered(404, _) =>
                record("get_wallet_balance", Json.obj(), Outcome.Ok)
                Json.render(Mapping.walletBalance(None, c))
              case called => read("get_wallet_balance")(Some(called))(a => Mapping.walletBalance(a.json, c))
      },
    FunctionTool.named("get_active_bonuses")
      .describedAs("Get the player's active bonus campaigns including wagering progress.")
      .handle { () =>
        read("get_active_bonuses")(Some(n.rewards.grants(playerId)))(c => Json.obj("bonuses" -> Mapping.activeBonuses(json(c))))
      },
    FunctionTool.named("get_transaction_history")
      .describedAs("Get the player's recent transaction history (deposits, withdrawals, bets, wins).")
      .param[Option[Int]]("limit", "How many of the most recent transactions, 1 to 50; 10 when not given.")
      .handle { (limit: Option[Int]) =>
        val l = limit.getOrElse(Tools.DefaultLimit)
        if l < 1 || l > Tools.MaxLimit then throw IllegalArgumentException(s"limit must be between 1 and ${Tools.MaxLimit}")
        read("get_transaction_history", Json.obj("limit" -> l))(currency.map(c => n.wallet.transactions(playerId, c, l)))(c =>
          Json.obj("transactions" -> Mapping.transactions(json(c))))
      },
    FunctionTool.named("get_session_history")
      .describedAs("Get the player's game session history including active sessions.")
      .handle { () =>
        read("get_session_history")(Some(n.players.sessions(playerId)))(c => Json.obj("sessions" -> Mapping.sessions(json(c))))
      },
    FunctionTool.named("get_rg_status")
      .describedAs("Get the player's responsible gambling status: deposit limits, cool-off, and self-exclusion.")
      .handle { () =>
        read("get_rg_status")(currency.map(c => n.rg.limits(playerId, c)))(c => Mapping.rgStatus(json(c)))
      }
  )

  /**
   * A write, once approved: the arguments checked again (FR-011), the call keyed by the approval the conversation
   * names (FR-015), and its outcome recorded for `action_result`. A refusal or a silence is thrown, so the model is
   * told it as the tool's error and the turn goes on.
   */
  private def write(tool: String, args: JsonNode)(call: String => Called)(done: JsonNode => String): String =
    val key = trail.approved.getOrElse(throw IllegalStateException("this action has not been approved by the player"))
    Actions.check(tool, args, currency, now()).left.foreach(reason => throw IllegalArgumentException(reason))
    val c       = call(key)
    val outcome = outcomeOf(c)
    val detail = outcome match
      case Outcome.Ok         => done(json(c))
      case Outcome.Refused(_) => Tools.refusal(c)
      case _                  => Tools.NotApplied
    record(tool, args, outcome, Some(key), Some(detail))
    own.add(detail)
    if outcome == Outcome.Ok then detail else throw IllegalStateException(detail)

  private val within = body.approvalMinutes.toLong.minutes

  val writes: Vector[FunctionTool] = Vector(
    FunctionTool.named("request_withdrawal")
      .describedAs("Request a withdrawal from the player's account. Amount in minor currency units (pence/cents). Pauses for player confirmation.")
      .param[Long]("amount", "The amount in minor units of the player's currency (pence/cents).")
      .param[String]("currency", "The player's currency, as an ISO 4217 code.")
      .handle { (amount: Long, c: String) =>
        write("request_withdrawal", Json.obj("amount" -> amount, "currency" -> c))(key =>
          n.payouts.requestWithdrawal(playerId, amount, c, key,
            Actions.confirmsForfeit(trail.namedForfeit, Actions.forfeit(n, playerId, c, amount))))(a => s"${a.textOr("requestId", "")} submitted")
      }
      .requiresApproval(within),
    FunctionTool.named("close_active_session")
      .describedAs("Close the player's active game session. Pauses for player confirmation.")
      .param[String]("game_session_id", "The id of the game session to close, as get_session_history names it.")
      .handle { (id: String) =>
        write("close_active_session", Json.obj("game_session_id" -> id))(key => n.players.endSession(id, key))(_ => s"Session $id closed")
      }
      .requiresApproval(within),
    FunctionTool.named("set_deposit_limit")
      .describedAs("Set a deposit limit for the player. period must be 'daily', 'weekly', or 'monthly'. Amount in minor units. Pauses for player confirmation.")
      .param[Long]("amount", "The limit in minor units of the player's currency (pence/cents).")
      .param[String]("period", "'daily', 'weekly', or 'monthly'.")
      .handle { (amount: Long, period: String) =>
        write("set_deposit_limit", Json.obj("amount" -> amount, "period" -> period))(key =>
          n.rg.setLimit(playerId, s"DEPOSIT_${period.toUpperCase}", amount, currency.getOrElse(""), key))(a =>
          s"Limit set. Effective from: ${a.text("appliesAt").getOrElse(now().toString)}")
      }
      .requiresApproval(within),
    FunctionTool.named("apply_cool_off")
      .describedAs("Apply a cool-off period until the given ISO timestamp. Cannot be shortened once set. Pauses for player confirmation.")
      .param[String]("cool_off_until", "When the break ends, as an ISO 8601 instant, as 2026-12-01T00:00:00Z.")
      .handle { (until: String) =>
        var effective = until
        val detail = write("apply_cool_off", Json.obj("cool_off_until" -> until))(key => n.rg.coolOff(playerId, until, key)) { a =>
          effective = a.text("coolingOffUntil").getOrElse(until)
          s"Cool-off in effect until $effective"
        }
        // Applied: the conversation takes no turn until the break ends (FR-017).
        trail.ended(EndReason.OnBreak(Instant.parse(effective)))
        detail
      }
      .requiresApproval(within),
    FunctionTool.named("apply_self_exclusion")
      .describedAs("Permanently self-exclude the player. This cannot be reversed. Pauses for one confirmation only.")
      .handle { () =>
        val detail = write("apply_self_exclusion", Json.obj())(key => n.rg.exclude(playerId, key))(_ => s"Self-exclusion applied at ${now()}")
        trail.ended(EndReason.SelfExcluded)
        detail
      }
      .requiresApproval(within)
  )

  def all: Vector[FunctionTool] = reads ++ writes

object Tools:
  val DefaultLimit = 10
  val MaxLimit     = 50

  /** The answers of the player's own records that carry ids: their game sessions, rewards and transactions. */
  def records(n: Neighbours, playerId: String, currency: Option[String]): Vector[String] =
    (Vector(n.players.sessions(playerId), n.rewards.grants(playerId)) ++
      currency.map(c => n.wallet.transactions(playerId, c, MaxLimit))).collect { case c @ Called.Answered(_, text) if c.ok => text }

  /** What a write that did not answer is told: it may not have been applied, and the player may ask again. */
  val NotApplied = "the service did not answer, so the action may not have been applied; the player may ask again"

  /** A service's refusal as `action_result.detail`: its code and its message (contracts/neighbours.md). */
  def refusal(c: Called): String =
    val message = c.json.flatMap(_.text("message"))
    (c.refusal.toVector ++ message.toVector).mkString(": ")
