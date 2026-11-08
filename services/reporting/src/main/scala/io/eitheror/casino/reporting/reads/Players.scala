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
  /** The players registered from `from` (inclusive) to `to` (exclusive), both instants. */
  val registeredBetween = query("registered-between")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND (payload::jsonb->>'registeredAt')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->>'registeredAt')::timestamptz < (:to)::timestamptz ORDER BY payload::jsonb->>'registeredAt', row_key")

  /** Every player of the generation, a page at a time from `offset` (FR-014's geography, the email search). */
  val every = query("every")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation ORDER BY row_key OFFSET (:offset)::int")

  /** The players `ids` names, comma separated. */
  val byIds = query("by-ids")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'player' = ANY(string_to_array(:ids, ',')) ORDER BY row_key")

  /** The generation's test accounts, which the dashboard leaves out when the brand excludes them (FR-024). */
  val testAccounts = query("test-accounts")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'testAccount' = 'true' ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new PlayerRows

  private val Page = 10_000

  def registered(views: ViewClient, generation: Int, from: Instant, to: Instant): Vector[PlayerRow] =
    views.forView(Players).ask(registeredBetween, 1_000_000, "generation" -> generation.toString, "from" -> from.toString, "to" -> to.toString)

  /** Every player of the generation, read a page at a time. */
  def all(views: ViewClient, generation: Int): Vector[PlayerRow] =
    @scala.annotation.tailrec
    def from(offset: Int, acc: Vector[PlayerRow]): Vector[PlayerRow] =
      val page = views.forView(Players).ask(every, Page, "generation" -> generation.toString, "offset" -> offset.toString)
      if page.size < Page then acc ++ page else from(offset + page.size, acc ++ page)
    from(0, Vector.empty)

  def of(views: ViewClient, generation: Int, ids: Iterable[String]): Map[String, PlayerRow] =
    if ids.isEmpty then Map.empty
    else views.forView(Players).ask(byIds, 100_000, "generation" -> generation.toString, "ids" -> ids.mkString(","))
      .map(r => r.player -> r).toMap

  def tests(views: ViewClient, generation: Int): Set[String] =
    views.forView(Players).ask(testAccounts, 1_000_000, "generation" -> generation.toString).map(_.player).toSet
