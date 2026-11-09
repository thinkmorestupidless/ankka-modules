package io.eitheror.casino.support.agent

import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.support.Json
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.clients.{Called, Neighbours}
import io.eitheror.casino.support.config.ConfigBody
import io.eitheror.casino.support.domain.Mapping

/**
 * The player's state and balance as the model is given them (FR-007): read from players and the wallet as
 * `support-agent`, mapped to the original's fields, the personal fields only as the brand names them (FR-018).
 */
object Context:
  /** What the model is told of a read that did not answer (FR-012): the turn goes on without it. */
  val Unreadable = "cannot be read right now"

  /** players' status as `get_player_state` answers it, with the profile's named fields; a refusal's code otherwise. */
  def playerState(n: Neighbours, playerId: String, body: ConfigBody): Either[String, ObjectNode] =
    n.players.status(playerId) match
      case c @ Called.Answered(_, _) if c.ok =>
        val status  = c.json.getOrElse(Json.obj())
        // Only the fields the brand names, asked for and kept: an answer naming more passes no more (FR-018, SC-005).
        val profile =
          if body.personalFields.isEmpty then None
          else Some(n.players.agentProfile(playerId, body.personalFields)).filter(_.ok).flatMap(_.json).collect {
            case o: ObjectNode => o.deepCopy().retain(body.personalFields.toSeq*)
          }
        Right(Mapping.playerState(status, profile))
      case c => Left(failure(c))

  /** The wallet's balance in the player's currency; a wallet not yet opened (404) is zeros, as the original. */
  def balance(n: Neighbours, playerId: String, currency: String): Either[String, ObjectNode] =
    n.wallet.balance(playerId, currency) match
      case c @ Called.Answered(_, _) if c.ok => Right(Mapping.walletBalance(c.json, currency))
      case Called.Answered(404, _)           => Right(Mapping.walletBalance(None, currency))
      case c                                 => Left(failure(c))

  /** The first turn's context: the state and the balance, or that each cannot be read right now. */
  def first(n: Neighbours, playerId: String, body: ConfigBody): Option[String] =
    val state   = playerState(n, playerId, body)
    val balance = state.toOption.flatMap(_.text("currency")).map(c => this.balance(n, playerId, c)).getOrElse(Left(Unreadable))
    Some(s"The player's state: ${state.fold(_ => Unreadable, Json.render)}\nThe player's balance: ${balance.fold(_ => Unreadable, Json.render)}")

  /** What a failed read is told to the model: the service's refusal by its code, or that it cannot be read now. */
  def failure(c: Called): String = c.refusal.getOrElse(Unreadable)
