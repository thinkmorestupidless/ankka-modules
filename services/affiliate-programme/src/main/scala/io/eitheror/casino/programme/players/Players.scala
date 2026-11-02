package io.eitheror.casino.programme.players

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.time.Instant

/** A player as the portal and the operator list it: the pseudonym, never a player id (FR-038). */
final case class PlayerRow(
    brand: String = "",
    pseudonym: String = "",
    affiliate: Option[String] = None,
    link: Option[String] = None,
    campaign: Option[String] = None,
    subIds: Vector[String] = Vector.empty,
    country: Option[String] = None,
    boundAt: Option[Instant] = None,
    firstDepositAt: Option[Instant] = None,
    deposits: Map[String, DepositTotals] = Map.empty,
    withdrawals: Map[String, Long] = Map.empty,
    fraud: Fraud = Fraud(),
    closed: Option[String] = None,
    /** Facts that named another affiliate or link than the binding (edge cases): the binding won; a manager sees them. */
    disagreements: Vector[Disagreement] = Vector.empty
)

object PlayerRow:
  given Serializer[PlayerRow] = ProgrammeCodecs.serializer[PlayerRow]("player-row")

  /** The row from the player's state after the event: the view keeps no fact ids, only what is listed. */
  def of(s: PlayerState): PlayerRow =
    PlayerRow(s.brand, s.pseudonym, s.binding.map(_.affiliate), s.binding.map(_.link), s.binding.flatMap(_.campaign),
      s.binding.fold(Vector.empty)(_.subIds), s.country, s.binding.map(_.boundAt), s.guard.map(_.at), s.deposits, s.withdrawals,
      s.fraud, s.closed.map(_.category), s.disagreements)

  def next(row: PlayerRow, e: PlayerAttribution.Applied): PlayerRow =
    val prior = PlayerState(row.brand, row.pseudonym,
      binding = row.affiliate.map(a => Binding(a, row.link.getOrElse(""), row.campaign, row.subIds, None, row.boundAt.getOrElse(Instant.EPOCH))),
      country = row.country, guard = row.firstDepositAt.map(Guard("", _)), deposits = row.deposits, withdrawals = row.withdrawals,
      fraud = row.fraud, closed = row.closed.map(ClosedAs(_, Instant.EPOCH)), disagreements = row.disagreements)
    of(PlayerAttribution.next(prior, e))

/** One row per player at a brand (data-model.md *Views*). */
final class PlayersView extends View[PlayerAttribution.Event, PlayerRow]:
  def onChange(event: PlayerAttribution.Event): Effect =
    val row  = rowState.getOrElse(PlayerRow())
    val next = PlayerRow.next(row, event)
    if rowState.isDefined && next == row then effects.ignore() else effects.updateRow(next)

object Players extends View.Companion[PlayersView, PlayerAttribution.Event, PlayerRow](
      componentId = ComponentId("players"),
      source = ChangeSource.eventsOf(PlayerAttribution),
      rowSerializer = PlayerRow.given_Serializer_PlayerRow
    ):
  val byAffiliate = query("by-affiliate")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'affiliate' = :affiliate AND payload::jsonb->>'brand' = :brand ORDER BY row_key"
  )
  /** The brand's players with a fact that disagreed with their binding. */
  val disagreeing = query("disagreeing")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand AND payload::jsonb->'disagreements' IS NOT NULL ORDER BY row_key"
  )
  val byLink = query("by-link")(s"SELECT payload FROM $table WHERE payload::jsonb->>'link' = :link ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new PlayersView
