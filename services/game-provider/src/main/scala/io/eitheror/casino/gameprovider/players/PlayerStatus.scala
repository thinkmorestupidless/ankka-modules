package io.eitheror.casino.gameprovider.players

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.*

import java.time.Instant

/** One player's account status as `casino.players` has told this service (data-model.md). */
final case class PlayerStatusRow(playerId: String, accountStatus: String, since: Instant):
  /** Suspended, self-excluded, closed or blocked: a debit and a balance are answered `player_locked` (FR-012, FR-017). */
  def locked: Boolean = PlayerStatusRow.Locked(accountStatus)

object PlayerStatusRow:
  val Locked: Set[String] = Set("suspended", "self_excluded", "closed", "blocked")

/**
 * The account status of every player of the brand, folded from the players topic (research R8), so no
 * money callback asks `players` over HTTP. It reads the topic from its start (ankka 024's
 * `StartFrom.Earliest`, in 0.11.0), so a new deployment sees every status already published. `blocked`
 * folds from a `player_blocked` family spec 008 is asked to add (spec Assumptions); until it exists no
 * row is `blocked`.
 */
final class PlayerStatusView extends View[CasinoPlayersFact, PlayerStatusRow]:

  def onChange(fact: CasinoPlayersFact): Effect =
    def set(status: String) = effects.updateRow(PlayerStatusRow(fact.playerId, status, fact.occurredAt))
    fact match
      case _: PlayerRegistered   => if rowState.isEmpty then set("active") else effects.ignore()
      case _: PlayerSuspended    => set("suspended")
      case _: PlayerReinstated   => set("active")
      case _: PlayerSelfExcluded => set("self_excluded")
      case _: PlayerClosed       => set("closed")
      case _                     => effects.ignore()

object PlayerStatus extends View.Companion[PlayerStatusView, CasinoPlayersFact, PlayerStatusRow](
      componentId = ComponentId("player-status"),
      // Naming the topic's contract: ankka refuses to start a component reading a topic whose project declares
      // a contract the component does not name (T082).
      source = ChangeSource.fromTopic(CasinoPlayers.name, CasinoPlayers.serializer, StartFrom.Earliest,
        TopicOptions(Some(CasinoPlayers.contract), None, false)),
      rowSerializer = Codecs.serializer[PlayerStatusRow]("player-status-row")
    ):
  def create(ctx: ViewComponentContext) = new PlayerStatusView
