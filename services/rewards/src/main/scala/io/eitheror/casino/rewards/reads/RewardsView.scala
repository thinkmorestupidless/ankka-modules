package io.eitheror.casino.rewards.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.{SqlFragment, ViewQueries}
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.rewards.RewardsCodecs
import io.eitheror.casino.rewards.reward.{Reward, RewardEvent, RewardStatus}
import io.eitheror.casino.rewards.reward.RewardEvent.*

import java.time.Instant

/** A free-rounds reward's part of its row: what is left to play, the game, and the provider's last word. */
final case class FreeRoundsRow(spinsRemaining: Int, spinCount: Int, gameId: String, provider: String, providerStatus: String = "",
    gameName: String = "")

/**
 * One reward's row (data-model.md `RewardsView`), from its events. `offerType` is the offer's type (the routes'
 * `type`); `openedAt` orders a player's rewards; `closedAt` is when it reached a terminal state. The player is named
 * by id only: no personal value is kept.
 */
final case class RewardRow(
    rewardId: String,
    playerId: String = "",
    offerId: String = "",
    offerName: String = "",
    offerType: String = "",
    payloadType: String = "",
    status: String = RewardStatus.Available.wire,
    faceValue: Long = 0L,
    wageringRequirement: Long = 0L,
    currency: String = "",
    source: String = "",
    freeRounds: Option[FreeRoundsRow] = None,
    openedAt: Instant = Instant.EPOCH,
    grantedAt: Option[Instant] = None,
    expiresAt: Option[Instant] = None,
    closedAt: Option[Instant] = None
)

object RewardRow:
  given Serializer[RewardRow] = RewardsCodecs.row[RewardRow]("reward-row")

  private def ended(r: RewardRow, status: RewardStatus, at: Instant) = r.copy(status = status.wire, closedAt = Some(at))
  private def rounds(r: RewardRow)(f: FreeRoundsRow => FreeRoundsRow) = r.copy(freeRounds = r.freeRounds.map(f))

  /** The row after `event`; none before `Targeted`. Each event sets what it says, so a redelivery changes nothing. */
  def applied(row: Option[RewardRow], event: RewardEvent): Option[RewardRow] = (row, event) match
    case (_, Targeted(id, o, at)) =>
      Some(row.getOrElse(RewardRow(id)).copy(rewardId = id, playerId = o.playerId, offerId = o.offerId, offerName = o.offerName,
        offerType = o.offerType, payloadType = o.payloadType.wire, faceValue = o.decision.amount,
        wageringRequirement = o.decision.wageringRequirement, currency = o.decision.currency.code, source = o.source.wire,
        freeRounds = o.freeRounds.map(t => FreeRoundsRow(t.spinCount, t.spinCount, t.gameId, t.provider, gameName = t.gameName.getOrElse(""))), openedAt = at))
    case (None, _) => None
    case (Some(r), e) =>
      Some(e match
        case Selected(_, expiresAt) => r.copy(status = RewardStatus.Pending.wire, expiresAt = Some(expiresAt))
        case Deselected(_)          => r.copy(status = RewardStatus.Available.wire, expiresAt = None)
        case Activated(award, expiresAt, _, at) =>
          r.copy(status = RewardStatus.Active.wire, grantedAt = Some(at), expiresAt = Some(expiresAt),
            wageringRequirement = award.fold(r.wageringRequirement)(_.wageringRequirement))
        case Converted(_, cap)              => r.copy(status = RewardStatus.ConvertedPendingCap.wire, expiresAt = Some(cap))
        case x: RewardEvent.Forfeited       => ended(r, RewardStatus.Forfeited, x.at)
        case x: RewardEvent.Closed          => ended(r, RewardStatus.Closed, x.at)
        case x: RewardEvent.Expired         => ended(r, RewardStatus.Expired, x.at)
        case x: RewardEvent.Cancelled       => ended(r, RewardStatus.Cancelled, x.at)
        case x: RewardEvent.Revoked         => ended(r, RewardStatus.Revoked, x.at)
        case ProviderStatusRecorded(p, _)   => rounds(r)(_.copy(providerStatus = p))
        case RoundCounted(_, _, left, _)    => rounds(r)(_.copy(spinsRemaining = left))
        case _: AwardRefused | _: Switched | _: LatePaid | _: FreeRoundsFinalised | _: Targeted => r)

/** Every reward's row (data-model.md `RewardsView`). The service serves one brand, so the view is the brand's. */
final class RewardsViewUpdater extends View[RewardEvent, RewardRow]:
  def onChange(event: RewardEvent): Effect =
    RewardRow.applied(rowState, event) match
      case Some(row) if !rowState.contains(row) => effects.updateRow(row)
      case _                                    => effects.ignore()

object RewardsView extends View.Companion[RewardsViewUpdater, RewardEvent, RewardRow](
      componentId = ComponentId("rewards-view"),
      source = ChangeSource.eventsOf(Reward),
      rowSerializer = RewardRow.given_Serializer_RewardRow
    ):
  def create(ctx: ViewComponentContext) = new RewardsViewUpdater

  val DefaultPageSize = 25
  val MaxPageSize     = 200
  /** The player's history: the newest 20 closed (contracts/routes.md). */
  val HistorySize = 20
  /** The provider's answers that make free rounds unplayable (research R8). */
  val FailedProviderStatuses: Vector[String] = Vector("rejected", "failed", "error")

  /** The routes' bound on a page (1 to 200, contracts/routes.md); outside it the endpoint answers 400 naming `pageSize`. */
  def validPageSize(n: Int): Boolean = n >= 1 && n <= MaxPageSize

  private def and(parts: Vector[SqlFragment]): SqlFragment =
    if parts.isEmpty then sql"1 = 1" else parts.reduce((a, b) => a ++ SqlFragment.raw(" AND ") ++ b)
  private def at(field: String)  = SqlFragment.raw(s"(payload::jsonb->>'$field')::timestamptz")
  private def player(id: String) = jsonText("playerId") ++ sql" = $id"
  private def isStatus(s: RewardStatus) = jsonText("status") ++ sql" = ${s.wire}"
  private val closed             = SqlFragment.raw("payload::jsonb->>'closedAt' IS NOT NULL")
  private val playable =
    SqlFragment.raw("payload::jsonb->'freeRounds' IS NOT NULL AND COALESCE(payload::jsonb->'freeRounds'->>'providerStatus', '') NOT IN (" +
      FailedProviderStatuses.map(s => s"'$s'").mkString(", ") + ")")
  private val newest = at("openedAt") ++ SqlFragment.raw(" DESC, payload::jsonb->>'rewardId' DESC")
  private val oldestGrant = at("grantedAt") ++ SqlFragment.raw(" ASC, payload::jsonb->>'rewardId' ASC")

  /** `%text%` for ILIKE, the pattern's own characters escaped. */
  private def containing(text: String): String =
    "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

  /**
   * The operator's list filters (contracts/routes.md, `GET /reward-grants`): `search` across the player id, the
   * reward id, the offer id and the offer name, any case; `status` one of the ten (`all`, or none, is every state);
   * the offer; the player.
   */
  final case class Filter(search: Option[String] = None, status: Option[String] = None, offerId: Option[String] = None,
      playerId: Option[String] = None):
    def condition: SqlFragment =
      val term = search.map(_.trim).filter(_.nonEmpty).map(containing)
      and(Vector(
        term.map(p => SqlFragment.raw("(") ++ jsonText("playerId") ++ sql" ILIKE $p OR " ++ jsonText("rewardId") ++ sql" ILIKE $p OR " ++
          jsonText("offerId") ++ sql" ILIKE $p OR " ++ jsonText("offerName") ++ sql" ILIKE $p)"),
        status.map(_.trim).filter(s => s.nonEmpty && !s.equalsIgnoreCase("all")).map(s => jsonText("status") ++ sql" = ${s.toUpperCase}"),
        offerId.map(o => jsonText("offerId") ++ sql" = $o"),
        playerId.map(player)
      ).flatten)

  /** One page of a filter, newest first, and how many rows the filter holds. */
  final case class Page(rows: Vector[RewardRow], total: Long, page: Int, pageSize: Int)

  def page(view: ViewQueries[RewardRow], filter: Filter, page: Int = 1, pageSize: Int = DefaultPageSize): Page =
    val p    = math.max(1, page)
    val size = math.min(MaxPageSize, math.max(1, pageSize))
    val upTo = view.ordered(filter.condition, newest, p * size)
    Page(upTo.drop((p - 1) * size).take(size), view.count(filter.condition), p, size)

  /** Every reward of the player, newest first (the operator's per-player list). */
  def byPlayer(view: ViewQueries[RewardRow], playerId: String, limit: Int = 1000): Vector[RewardRow] =
    view.ordered(player(playerId), newest, limit)

  /** The player's closed rewards, the newest 20 by when they closed (`GET /{playerId}/history`). */
  def history(view: ViewQueries[RewardRow], playerId: String): Vector[RewardRow] =
    view.ordered(and(Vector(player(playerId), closed)), at("closedAt") ++ SqlFragment.raw(" DESC"), HistorySize)

  /** The player's active free rounds the provider has not failed, oldest grant first (`GET /{playerId}/free-rounds`). */
  def liveFreeRounds(view: ViewQueries[RewardRow], playerId: String, currency: Option[String] = None): Vector[RewardRow] =
    view.ordered(and(Vector(Some(player(playerId)), Some(isStatus(RewardStatus.Active)), Some(playable),
      currency.map(c => jsonText("currency") ++ sql" = $c")).flatten), oldestGrant, 1000)

