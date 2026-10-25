package io.eitheror.casino.players.player

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.players.PlayersCodecs
import io.eitheror.casino.players.player.PlayerEvent.*


/**
 * A player as the operator lists one (FR-034): the status, tier and flags, the display name and the
 * primary address as `Personal` (blank once erased, as the entity is), and times in epoch milliseconds
 * to order by.
 */
final case class PlayerRow(
    playerId: String,
    status: String,
    vipTier: Option[String] = None,
    potVip: Boolean = false,
    fraudCases: Vector[String] = Vector.empty,
    displayName: Personal[String] = Personal(""),
    primaryEmail: Personal[String] = Personal(""),
    country: Option[String] = None,
    currency: String = "",
    registeredAt: Long = 0L,
    lastSignInAt: Option[Long] = None
):
  def flagged: Boolean = fraudCases.nonEmpty

object PlayerRow:
  /** The name the operator sees: the display name, else the given and family names. */
  def displayName(p: Profile): String =
    p.get("displayName").getOrElse(Vector(p.get("firstName"), p.get("lastName")).flatten.mkString(" "))

final class PlayersViewRows extends View[PlayerEvent, PlayerRow]:
  def onChange(event: PlayerEvent): Effect = (rowState, event) match
    case (None, e: Registered) =>
      effects.updateRow(PlayerRow(e.playerId, Status.Pending, displayName = Personal(PlayerRow.displayName(e.profile)),
        primaryEmail = e.address, country = e.profile.get("country"), currency = e.currency, registeredAt = e.at.toEpochMilli))
    case (Some(r), e) => PlayersView.fold(r, e).fold(effects.ignore())(effects.updateRow)
    case _            => effects.ignore()

object PlayersView extends View.Companion[PlayersViewRows, PlayerEvent, PlayerRow](
      componentId = ComponentId("players"),
      source = ChangeSource.eventsOf(PlayerEntity),
      rowSerializer = PlayersCodecs.serializer[PlayerRow]("player-row")
    ):
  def create(ctx: ViewComponentContext) = new PlayersViewRows

  /** A row after `e`, or none when `e` changes nothing the row holds. */
  def fold(r: PlayerRow, e: PlayerEvent): Option[PlayerRow] = e match
    case _: EmailVerified        => Some(r.copy(status = Status.Active))
    case e: SignedIn             => Some(r.copy(lastSignInAt = Some(e.at.toEpochMilli)))
    case e: ProfileEdited        => Some(r.copy(displayName = Personal(PlayerRow.displayName(e.profile)), country = e.profile.get("country")))
    case e: EmailPromoted        => Some(r.copy(primaryEmail = e.address))
    case e: EmailChanged         => Some(r.copy(primaryEmail = e.address))
    case e: Suspended            => Some(r.copy(status = Status.Suspended))
    case _: Reinstated           => Some(r.copy(status = Status.Active))
    case _: SelfExcluded         => Some(r.copy(status = Status.SelfExcluded))
    case _: ExclusionLifted      => Some(r.copy(status = Status.Active))
    case _: Closed               => Some(r.copy(status = Status.Closed))
    case _: Reopened             => Some(r.copy(status = Status.Active))
    case e: StatusImported       => Some(r.copy(status = e.status))
    case _: ErasureFiled         => Some(r.copy(status = Status.ErasurePending))
    case _: Erased               => Some(r.copy(status = Status.Erased, displayName = Personal(""), primaryEmail = Personal("")))
    case e: VipTierChanged       => Some(r.copy(vipTier = Some(e.tier)))
    case e: PotVipSet            => Some(r.copy(potVip = e.potVip))
    case e: DuplicateFlagged     => Some(r.copy(fraudCases = (r.fraudCases :+ e.caseId).distinct))
    case e: DuplicateCleared     => Some(r.copy(fraudCases = r.fraudCases.filterNot(_ == e.caseId)))
    case _                       => None

  private val status  = "payload::jsonb->>'status'"
  private val tier    = "COALESCE(payload::jsonb->>'vipTier', '')"
  private val flagged = "jsonb_array_length(COALESCE(payload::jsonb->'fraudCases', '[]'::jsonb)) > 0"
  private val at      = "COALESCE((payload::jsonb->>'registeredAt')::bigint, 0)"

  /** The operator's list: each filter empty for any; `flagged` is `''`, `true` or `false`; newest first. */
  val list: DeclaredQuery = query("list")(
    s"SELECT payload FROM $table WHERE (:status = '' OR $status = :status) AND (:tier = '' OR $tier = :tier) " +
      s"AND (:flagged = '' OR ($flagged) = CAST(:flagged AS boolean)) ORDER BY $at DESC, row_key ASC OFFSET CAST(:offset AS int)")

  /** By the text of the display name or the primary address, case-insensitive; an erased row has neither. */
  val search: DeclaredQuery = query("search")(
    s"SELECT payload FROM $table WHERE (LOWER(COALESCE(payload::jsonb->>'displayName', '')) LIKE :q " +
      s"OR LOWER(COALESCE(payload::jsonb->>'primaryEmail', '')) LIKE :q OR row_key = :id) ORDER BY $at DESC, row_key ASC")

  val byId: DeclaredQuery = query("one-player")(s"SELECT payload FROM $table WHERE row_key = :id")

  final case class Filter(status: Option[String] = None, tier: Option[String] = None, flagged: Option[Boolean] = None)

  def page(views: ViewClient, f: Filter, offset: Int, limit: Int): Vector[PlayerRow] =
    views.forView(PlayersView).ask(list, limit, "status" -> f.status.getOrElse(""), "tier" -> f.tier.getOrElse(""),
      "flagged" -> f.flagged.fold("")(_.toString), "offset" -> offset.max(0).toString)

  def find(views: ViewClient, text: String, limit: Int): Vector[PlayerRow] =
    val q = text.trim.toLowerCase
    if q.isEmpty then Vector.empty
    else views.forView(PlayersView).ask(search, limit, "q" -> s"%${q.replace("%", "\\%").replace("_", "\\_")}%", "id" -> text.trim)

  def one(views: ViewClient, playerId: String): Option[PlayerRow] =
    views.forView(PlayersView).ask(byId, 1, "id" -> playerId).headOption

