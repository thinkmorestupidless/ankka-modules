package io.eitheror.casino.reporting.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Personal, Serializer}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.intake.{Generation, PlayerLog, PlayerLogEvent, PlayerLogState, PlayerLogs, Personals}
import io.eitheror.casino.reporting.render.Money

import java.time.Instant

/**
 * A player as the player report, the dashboard's geography and the email search read them (data-model.md
 * `Players`): registration, country, currency, the email as `Personal` under `player/<id>` (FR-025), status, KYC
 * status, last login and the test flag. `statusAt` and `kycAt` are kept so a late status fact changes nothing, as
 * in `PlayerLog`.
 */
final case class PlayerRow(
    generation: Int,
    player: String = "",
    registeredAt: Option[Instant] = None,
    country: Option[String] = None,
    currency: Option[String] = None,
    email: Option[Personal[String]] = None,
    status: String = "active",
    statusAt: Option[Instant] = None,
    kycStatus: String = "unverified",
    kycAt: Option[Instant] = None,
    lastLoginAt: Option[Instant] = None,
    testAccount: Boolean = false
):
  def state: PlayerLogState =
    PlayerLogState(player, registeredAt, country, currency, email, status, statusAt, kycStatus, kycAt, lastLoginAt, testAccount)

  /** The email as a report shows it: the value, `[erased]` after erasure, empty with none. */
  def shownEmail: String = Personals.shown(email)

  /** The email cut to `n` characters and `…`, as the original's tables cut it; `[erased]` is never cut. */
  def truncatedEmail(n: Int): String = email match
    case Some(Personal.Present(_, v)) if v != null => Money.truncated(v, n)
    case _                                         => shownEmail

object PlayerRow:
  given Serializer[PlayerRow] = ReportingCodecs.row[PlayerRow]("player-row")

  /** The row after an event, as `PlayerLogs.apply` leaves the entity; the entity's open submissions are not kept. */
  def of(generation: Int, before: PlayerRow, e: PlayerLogEvent): PlayerRow =
    val s = PlayerLogs.apply(before.state, e)
    PlayerRow(generation, s.player, s.registeredAt, s.country, s.currency, s.email, s.status, s.statusAt, s.kycStatus, s.kycAt,
      s.lastLoginAt, s.testAccount)

final class PlayerRows extends View[PlayerLogEvent, PlayerRow]:
  def onChange(event: PlayerLogEvent): Effect =
    Generation.of(updateContext.subject).fold(effects.ignore()) { g =>
      effects.updateRow(PlayerRow.of(g, rowState.getOrElse(PlayerRow(g)), event))
    }

object Players extends View.Companion[PlayerRows, PlayerLogEvent, PlayerRow](
      componentId = ComponentId("players"),
      source = ChangeSource.eventsOf(PlayerLog),
      rowSerializer = PlayerRow.given_Serializer_PlayerRow
    ):
  /**
   * The players registered from `from` (inclusive) to `to` (exclusive), both instants, in registration order: a page after
   * the registration `after_at` and the row `after` (`-infinity` and `""` for the first).
   */
  val registeredBetween = query("registered-between")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND (payload::jsonb->>'registeredAt')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->>'registeredAt')::timestamptz < (:to)::timestamptz AND ((payload::jsonb->>'registeredAt')::timestamptz, row_key) > ((:after_at)::timestamptz, :after) ORDER BY (payload::jsonb->>'registeredAt')::timestamptz, row_key")

  /** Every player of the generation, a page at a time after the row `after` (FR-014's geography, the email search). */
  val every = query("every")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND row_key > :after ORDER BY row_key")

  /** The players `ids` names, comma separated, a page at a time after the row `after`. */
  val byIds = query("by-ids")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'player' = ANY(string_to_array(:ids, ',')) AND row_key > :after ORDER BY row_key")

  /** The generation's test accounts, which the dashboard leaves out when the brand excludes them (FR-024), by page. */
  val testAccounts = query("test-accounts")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'testAccount' = 'true' AND row_key > :after ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new PlayerRows

  /** A row's key: its `PlayerLog`'s id. */
  def key(r: PlayerRow): String = Generation.id(r.generation, r.player)

  /** Every player registered in the period, in registration order, a page at a time. */
  def registered(views: ViewClient, generation: Int, from: Instant, to: Instant, size: Int = Paging.Page): Vector[PlayerRow] =
    val q = views.forView(Players)
    Paging.all[PlayerRow](
      after => {
        val (at, row) = if after == Paging.First then ("-infinity", "") else (after.takeWhile(_ != '|'), after.dropWhile(_ != '|').drop(1))
        q.ask(registeredBetween, size, "generation" -> generation.toString, "from" -> from.toString, "to" -> to.toString,
          "after_at" -> at, "after" -> row)
      },
      r => s"${r.registeredAt.fold("-infinity")(_.toString)}|${key(r)}", size)

  /** Every player of the generation, read a page at a time. */
  def all(views: ViewClient, generation: Int): Vector[PlayerRow] =
    Paging.keyed(views.forView(Players), every, key, "generation" -> generation.toString)

  def of(views: ViewClient, generation: Int, ids: Iterable[String]): Map[String, PlayerRow] =
    if ids.isEmpty then Map.empty
    else Paging.keyed(views.forView(Players), byIds, key, "generation" -> generation.toString, "ids" -> ids.mkString(","))
      .map(r => r.player -> r).toMap

  def tests(views: ViewClient, generation: Int): Set[String] =
    Paging.keyed(views.forView(Players), testAccounts, key, "generation" -> generation.toString).map(_.player).toSet
