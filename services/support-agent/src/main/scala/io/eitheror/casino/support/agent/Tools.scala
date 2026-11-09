package io.eitheror.casino.support.agent

import com.thinkmorestupidless.ankka.agent.FunctionTool
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.support.Json.*
import io.eitheror.casino.support.clients.Neighbours
import io.eitheror.casino.support.config.ConfigBody

/**
 * The tools one turn offers (contracts/tools.md), built in the handler for the bound player: every call names
 * that player, never one the model gives (FR-005), and goes to the owning service as `support-agent`. A write
 * waits for the player's approval (FR-009) and is keyed by it (FR-015). The reads and the writes are added by
 * user stories 1 to 3.
 */
final class Tools(n: Neighbours, components: ComponentClient, session: String, playerId: String, body: ConfigBody):
  /** The player's currency, from players' status (P2), read once per turn when a tool first needs it. */
  lazy val currency: Option[String] = Context.playerState(n, playerId, body).toOption.flatMap(_.text("currency"))

  val reads: Vector[FunctionTool]  = Vector.empty
  val writes: Vector[FunctionTool] = Vector.empty

  def all: Vector[FunctionTool] = reads ++ writes
