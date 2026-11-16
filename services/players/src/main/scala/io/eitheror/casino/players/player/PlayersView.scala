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
    lastSignInAt: Option[Long] = None,
    kycVerified: Boolean = false,
    birthday: Personal[String] = Personal(""),
    openSessions: Vector[String] = Vector.empty
):
  def flagged: Boolean = fraudCases.nonEmpty
  def online: Boolean  = openSessions.nonEmpty

object PlayerRow:
  /** The name the operator sees: the display name, else the given and family names. */
  def displayName(p: Profile): String =
    p.get("displayName").getOrElse(Vector(p.get("firstName"), p.get("lastName")).flatten.mkString(" "))

  /**
   * The month and day of a date of birth, `MM-DD`, the birthday filter compares (spec 030 P2); blank when
   * the profile holds no date, or one not `yyyy-MM-dd`.
   */
  def birthday(dateOfBirth: Option[String]): Personal[String] =
    Personal(dateOfBirth.map(_.trim).filter(_.matches("""\d{4}-\d{2}-\d{2}""")).map(_.drop(5)).getOrElse(""))

final class PlayersViewRows extends View[PlayerEvent, PlayerRow]:
  def onChange(event: PlayerEvent): Effect = (rowState, event) match
    case (None, e: Registered) =>
      effects.updateRow(PlayerRow(e.playerId, Status.Pending, displayName = Personal(PlayerRow.displayName(e.profile)),
        primaryEmail = e.address, country = e.profile.get("country"), currency = e.currency, registeredAt = e.at.toEpochMilli,
        birthday = PlayerRow.birthday(e.profile.get("dateOfBirth"))))
    case (Some(r), e) => PlayersView.fold(r, e).fold(effects.ignore())(effects.updateRow)
    case _            => effects.ignore()

object PlayersView extends View.Companion[PlayersViewRows, PlayerEvent, PlayerRow](
      componentId = ComponentId("players"),
      source = ChangeSource.eventsOf(PlayerEntity),
      rowSerializer = PlayersCodecs.serializer[PlayerRow]("player-row")
    ):
  def create(ctx: ViewComponentContext) = new PlayersViewRows

  // 2 (spec 030 P2): the identity status, the birthday and the open sessions the filters read, from every event again
  override def version: Option[Int] = Some(2)

  /** A row after `e`, or none when `e` changes nothing the row holds. */
  def fold(r: PlayerRow, e: PlayerEvent): Option[PlayerRow] = e match
    case _: EmailVerified        => Some(r.copy(status = Status.Active))
    case e: SignedIn             => Some(r.copy(lastSignInAt = Some(e.at.toEpochMilli)))
    case e: ProfileEdited        => Some(r.copy(displayName = Personal(PlayerRow.displayName(e.profile)), country = e.profile.get("country"),
      birthday = PlayerRow.birthday(e.profile.get("dateOfBirth"))))
    case e: DateOfBirthCorrected => Some(r.copy(birthday = PlayerRow.birthday(Some(e.value.reveal))))
    case _: KycVerified          => Some(r.copy(kycVerified = true))
    case _: KycWithdrawn         => Some(r.copy(kycVerified = false))
    case e: SessionOpened        => Some(r.copy(openSessions = (r.openSessions :+ e.sessionId).distinct))
    case e: SessionEnded         => Some(r.copy(openSessions = r.openSessions.filterNot(_ == e.sessionId)))
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
    case _: Erased               => Some(r.copy(status = Status.Erased, displayName = Personal(""), primaryEmail = Personal(""),
      birthday = Personal(""), openSessions = Vector.empty))
    case e: VipTierChanged       => Some(r.copy(vipTier = Some(e.tier)))
    case e: PotVipSet            => Some(r.copy(potVip = e.potVip))
    case e: DuplicateFlagged     => Some(r.copy(fraudCases = (r.fraudCases :+ e.caseId).distinct))
    case e: DuplicateCleared     => Some(r.copy(fraudCases = r.fraudCases.filterNot(_ == e.caseId)))
    case _                       => None

  private val flagged = "jsonb_array_length(COALESCE(payload::jsonb->'fraudCases', '[]'::jsonb)) > 0"
  private val at      = "COALESCE((payload::jsonb->>'registeredAt')::bigint, 0)"

  /** By the text of the display name or the primary address, case-insensitive; an erased row has neither. */
  val search: DeclaredQuery = query("search")(
    s"SELECT payload FROM $table WHERE (LOWER(COALESCE(payload::jsonb->>'displayName', '')) LIKE :q " +
      s"OR LOWER(COALESCE(payload::jsonb->>'primaryEmail', '')) LIKE :q OR row_key = :id) ORDER BY $at DESC, row_key ASC")

  val byId: DeclaredQuery = query("one-player")(s"SELECT payload FROM $table WHERE row_key = :id")

  /**
   * The operator list's filters (spec 030 P2), each `None` for any: they combine with AND. `search` is the
   * text of the display name or the primary address, or the player's id; `country` and `currency` compare
   * case-insensitively; `identity` is `verified` or `unverified`; `birthdayWithin` is the days from
   * `today` (0 is today alone) a birthday falls in, in UTC, a 29 February birthday falling on 28 February
   * outside a leap year.
   */
  final case class Filter(
      status: Option[String] = None,
      tier: Option[String] = None,
      flagged: Option[Boolean] = None,
      search: Option[String] = None,
      vipOnly: Boolean = false,
      country: Option[String] = None,
      currency: Option[String] = None,
      potVip: Option[Boolean] = None,
      online: Option[Boolean] = None,
      identity: Option[String] = None,
      birthdayWithin: Option[Int] = None,
      today: java.time.LocalDate = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
  ):
    import com.thinkmorestupidless.ankka.runtime.SqlFragment
    import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql

    private def text(field: String): SqlFragment   = SqlFragment.raw(s"COALESCE(payload::jsonb->>'$field', '')")
    private def flag(field: String): SqlFragment   = SqlFragment.raw(s"COALESCE(payload::jsonb->>'$field', 'false')")
    private def upper(field: String): SqlFragment  = SqlFragment.raw(s"UPPER(COALESCE(payload::jsonb->>'$field', ''))")
    private val onlineTerm: SqlFragment = SqlFragment.raw("jsonb_array_length(COALESCE(payload::jsonb->'openSessions', '[]'::jsonb)) > 0")
    private val flaggedTerm: SqlFragment = SqlFragment.raw(PlayersView.flagged)

    /** The `MM-DD` days of the window, a 29 February birthday matched on 28 February of a common year. */
    def birthdays: Vector[String] =
      birthdayWithin.toVector.flatMap { n =>
        (0 to n).map(today.plusDays(_)).flatMap { d =>
          val md = f"${d.getMonthValue}%02d-${d.getDayOfMonth}%02d"
          if md == "02-28" && !d.isLeapYear then Vector(md, "02-29") else Vector(md)
        }
      }.distinct

    /** The condition, every filter's term joined with AND; `SqlFragment.raw("TRUE")` when none is set. */
    def condition: SqlFragment =
      val terms = Vector.newBuilder[SqlFragment]
      status.foreach(v => terms += text("status") ++ sql" = $v")
      tier.foreach(v => terms += text("vipTier") ++ sql" = $v")
      if vipOnly then terms += text("vipTier") ++ SqlFragment.raw(" <> ''")
      flagged.foreach(v => terms += (if v then flaggedTerm else SqlFragment.raw("NOT (") ++ flaggedTerm ++ SqlFragment.raw(")")))
      search.map(_.trim).filter(_.nonEmpty).foreach { q =>
        val like = s"%${q.toLowerCase.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
        terms += SqlFragment.raw("(LOWER(COALESCE(payload::jsonb->>'displayName', '')) LIKE ") ++ sql"$like" ++
          SqlFragment.raw(" OR LOWER(COALESCE(payload::jsonb->>'primaryEmail', '')) LIKE ") ++ sql"$like" ++
          SqlFragment.raw(" OR row_key = ") ++ sql"$q" ++ SqlFragment.raw(")")
      }
      country.foreach(v => terms += upper("country") ++ sql" = ${v.toUpperCase(java.util.Locale.ROOT)}")
      currency.foreach(v => terms += upper("currency") ++ sql" = ${v.toUpperCase(java.util.Locale.ROOT)}")
      potVip.foreach(v => terms += flag("potVip") ++ sql" = ${v.toString}")
      identity.foreach(v => terms += flag("kycVerified") ++ sql" = ${(v == Identity.Verified).toString}")
      online.foreach(v => terms += (if v then onlineTerm else SqlFragment.raw("NOT (") ++ onlineTerm ++ SqlFragment.raw(")")))
      if birthdayWithin.nonEmpty then terms += text("birthday") ++ sql" = ANY(string_to_array(${birthdays.mkString(",")}, ','))"
      val all = terms.result()
      if all.isEmpty then SqlFragment.raw("TRUE")
      else all.tail.foldLeft(SqlFragment.raw("(") ++ all.head)((acc, t) => acc ++ SqlFragment.raw(") AND (") ++ t) ++ SqlFragment.raw(")")

  object Identity:
    val Verified   = "verified"
    val Unverified = "unverified"
    val all: Vector[String] = Vector(Verified, Unverified)

  /** Newest registered first, the key breaking a tie, so a page never repeats or skips a row. */
  private def newestFrom(offset: Int) =
    com.thinkmorestupidless.ankka.runtime.SqlFragment.raw(s"$at DESC, row_key ASC OFFSET ${offset.max(0)}")

  /** One page of the filtered players, newest first, from `offset` (spec 030 P2: a view query, no row cap). */
  def page(views: ViewClient, f: Filter, offset: Int, limit: Int): Vector[PlayerRow] =
    // the view client puts its LIMIT after the order, and Postgres takes OFFSET and LIMIT in either order
    views.forView(PlayersView).ordered(f.condition, newestFrom(offset), limit)

  /** How many players the filters match. */
  def total(views: ViewClient, f: Filter): Long = views.forView(PlayersView).count(f.condition)

  def totalAsync(views: ViewClient, f: Filter): scala.concurrent.Future[Long] = views.forView(PlayersView).countAsync(f.condition)

  /**
   * The values of a text field the filters' players hold, each once (`country`, `currency`, `status`): one row
   * per value, from a `DISTINCT ON` over the matching rows.
   */
  def valuesOf(views: ViewClient, f: Filter, field: String): Vector[String] =
    import com.thinkmorestupidless.ankka.runtime.SqlFragment
    val term = s"COALESCE(payload::jsonb->>'$field', '')"
    val rows = views.forView(PlayersView).where(
      SqlFragment.raw(s"row_key IN (SELECT DISTINCT ON ($term) row_key FROM $table WHERE ") ++ f.condition ++
        SqlFragment.raw(s" ORDER BY $term, row_key)"), 1000)
    rows.map(r => field match
      case "country"  => r.country.getOrElse("")
      case "currency" => r.currency
      case _          => r.status
    ).filter(_.nonEmpty).distinct.sorted

  def find(views: ViewClient, text: String, limit: Int): Vector[PlayerRow] =
    val q = text.trim.toLowerCase
    if q.isEmpty then Vector.empty
    else views.forView(PlayersView).ask(search, limit, "q" -> s"%${q.replace("%", "\\%").replace("_", "\\_")}%", "id" -> text.trim)

  def one(views: ViewClient, playerId: String): Option[PlayerRow] =
    views.forView(PlayersView).ask(byId, 1, "id" -> playerId).headOption

