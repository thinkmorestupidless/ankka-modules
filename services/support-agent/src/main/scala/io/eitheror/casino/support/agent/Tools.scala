package io.eitheror.casino.support.agent

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.agent.FunctionTool
import io.eitheror.casino.support.Json
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.clients.{Called, Neighbours}
import io.eitheror.casino.support.config.ConfigBody
import io.eitheror.casino.support.conversation.{Outcome, ToolCall}
import io.eitheror.casino.support.domain.{Mapping, Redaction}

import java.time.Instant
import java.util.UUID

/**
 * The tools one turn offers (contracts/tools.md), built in the handler for the bound player: every call names
 * that player, never one the model gives (FR-005), and goes to the owning service as `support-agent`. Each call is
 * recorded on the conversation with its redacted arguments and its outcome (FR-028). A read that does not answer is
 * told to the model as "cannot be read right now", and the turn goes on (FR-012). A write waits for the player's
 * approval (FR-009) and is keyed by it (FR-015); the writes are added by user stories 2 and 3.
 */
final class Tools(n: Neighbours, trail: Trail, playerId: String, body: ConfigBody, now: () => Instant):

  /** The player's currency, from players' status (P2), read once per turn when a tool first needs it. */
  lazy val currency: Option[String] = Context.playerState(n, playerId, body).toOption.flatMap(_.text("currency"))

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
          case Outcome.Ok => Json.render(map(c))
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

  val writes: Vector[FunctionTool] = Vector.empty

  def all: Vector[FunctionTool] = reads ++ writes

object Tools:
  val DefaultLimit = 10
  val MaxLimit     = 50
