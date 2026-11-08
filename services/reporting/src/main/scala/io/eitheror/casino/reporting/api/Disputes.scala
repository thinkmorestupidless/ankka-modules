package io.eitheror.casino.reporting.api

import com.thinkmorestupidless.ankka.core.{EntityId, Personal}
import io.eitheror.casino.reporting.days.Days
import io.eitheror.casino.reporting.documents.{Document, DocumentState, JobRequest, Keeping}
import io.eitheror.casino.reporting.intake.{PlayerLog, PlayerLogState, Personals, Session, SessionState, Sessions as SessionKinds}
import io.eitheror.casino.reporting.reads.{RoundRow, Rounds, Sessions}
import io.eitheror.casino.reporting.render.{DisputeGame, DisputeMeta, DisputeReport, DisputeRound, DisputeSession, DisputeTotals}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalDate}
import java.time.temporal.ChronoUnit
import java.util.UUID

import Wire.*

/**
 * `GET /players/{playerId}/dispute-report` (FR-016, FR-017, FR-022; spec 117): one login session (`sessionId`) or a range
 * of days in the brand's zone (`fromDate`, `toDate`, at most 365 days apart), as HTML, CSV or PDF, refused in the
 * original's texts and order. Its login sessions and game sessions come from `casino.sessions`, its rounds from
 * `casino.rounds` (a game's name and type from its rounds, a round's status its settlement's outcome), and a round the
 * wallet settled that the provider's record lacks from the wallet's facts, placed by its time and marked `unmatched`. A
 * range over the brand's `syncDays`, or more rounds than its `syncRows`, is a job.
 */
object Disputes:
  val NotBoth   = "provide either sessionId or fromDate+toDate, not both"
  val OneOf     = "provide either sessionId or both fromDate and toDate"
  val OverAYear = "date range must not exceed 365 days"
  val Backwards = "fromDate must be before toDate"
  val BadDate   = "invalid date format; use ISO 8601 (YYYY-MM-DD)"
  val PlayerNotFound  = "player_not_found"
  val SessionNotFound = "session_not_found"

  /** How a round the wallet settled and the provider's record lacks reads (FR-017). */
  val Unmatched = "unmatched"

  /** The game a round the provider's record lacks is shown under when no game session of its login session spans it. */
  val UnknownGame = "unknown"

  /** What the report covers: one login session, or the login sessions started in a range of reporting days. */
  enum Mode:
    case OneSession(id: String)
    case Range(from: LocalDate, to: LocalDate)

  /** The original's checks in its order (`DashboardRoutes.scala` `dispute-report`): the pair, then the range. */
  def mode(sessionId: Option[String], fromDate: Option[String], toDate: Option[String]): Either[String, Mode] =
    def present(v: Option[String]) = v.map(_.trim).filter(_.nonEmpty)
    (present(sessionId), present(fromDate), present(toDate)) match
      case (Some(_), Some(_), _) | (Some(_), _, Some(_)) => Left(NotBoth)
      case (Some(s), None, None)                         => Right(Mode.OneSession(s))
      case (None, Some(f), Some(t)) =>
        scala.util.Try((LocalDate.parse(f), LocalDate.parse(t))).toOption match
          case None => Left(BadDate)
          case Some((from, to)) =>
            val days = ChronoUnit.DAYS.between(from, to)
            if days > 365 then Left(OverAYear) else if days < 0 then Left(Backwards) else Right(Mode.Range(from, to))
      case _ => Left(OneOf)

  /** A job's request and a record's parameters: the mode, the player, the format, and the meta the request fixed. */
  private final case class Ask(player: String, mode: Mode, format: String, currency: String, periodFrom: String, periodTo: String,
      generatedAt: Instant, reference: String):
    def parameters: Map[String, String] = (mode match
      case Mode.OneSession(id)   => Map("sessionId" -> id)
      case Mode.Range(from, to)  => Map("fromDate" -> from.toString, "toDate" -> to.toString)
    ) ++ Map("playerId" -> player, "format" -> format, "currency" -> currency, "periodFrom" -> periodFrom, "periodTo" -> periodTo,
      "generatedAt" -> generatedAt.toString)

  private object Ask:
    def of(req: JobRequest): Ask =
      val p = req.parameters
      val mode = p.get("sessionId").fold(Mode.Range(LocalDate.parse(p("fromDate")), LocalDate.parse(p("toDate"))))(Mode.OneSession(_))
      Ask(p("playerId"), mode, p("format"), p("currency"), p("periodFrom"), p("periodTo"), Instant.parse(p("generatedAt")), req.reference)

  private def playerLog(r: Reads, player: String): PlayerLogState =
    r.components.forEventSourcedEntity(EntityId(PlayerLog.id(r.generation, player))).call(PlayerLog.get).invoke()

  private def session(r: Reads, id: String): SessionState =
    r.components.forKeyValueEntity(EntityId(Session.id(r.generation, id))).call(Session.get).invoke()

  private def shown(p: Option[Personal[String]]): Option[String] = Option(Personals.shown(p)).filter(_.nonEmpty)

  // ── the route ─────────────────────────────────────────────────────────────────────────────────

  def report(ctx: Ctx, player: String, sessionId: Option[String], fromDate: Option[String], toDate: Option[String],
      format: Option[String], operator: String): Answer =
    val r = ctx.reads
    mode(sessionId, fromDate, toDate) match
      case Left(text) => refuse(400, Refusal(text))
      case Right(m) =>
        val log = playerLog(r, player)
        if log.player.isEmpty then refuse(404, Refusal(PlayerNotFound))
        else
          // A session asked for must be a login session of this player: another's is not found, as no session.
          val one = m match
            case Mode.OneSession(id) => Some(Some(session(r, id)).filter(s => s.kind == SessionKinds.Player && s.player == player))
            case _                   => None
          if one.contains(None) then refuse(404, Refusal(SessionNotFound))
          else
            val at = ctx.now()
            val (periodFrom, periodTo, from, to) = m match
              case Mode.Range(f, t) =>
                (r.startOf(f).toString, r.startOf(t.plusDays(1)).minusSeconds(1).toString, f.toString, t.toString)
              case Mode.OneSession(_) =>
                val s = one.flatten.get
                (s.startedAt.getOrElse(at).toString, s.endedAt.getOrElse(at).toString,
                  Days.dayOf(s.startedAt.getOrElse(at), r.settings.reportingZone), Days.dayOf(s.endedAt.getOrElse(at), r.settings.reportingZone))
            val fmt = DisputeReport.formatOf(format)
            val ask = Ask(player, m, fmt, log.currency.getOrElse("EUR"), periodFrom, periodTo, at, UUID.randomUUID().toString)
            val name = Document.subjectObjectName(player, Document.DisputeReport, ask.reference, fmt)
            val file = DisputeReport.fileName(ask.reference, fmt)
            val record = DocumentState(reference = ask.reference, kind = Document.DisputeReport, format = fmt,
              parameters = ask.parameters - "generatedAt", from = Some(from), to = Some(to), askedBy = operator, generatedAt = Some(at),
              objectName = Some(name), fileName = Some(file), contentType = Some(DisputeReport.contentType(fmt)), subject = Some(player))
            val settings = ctx.reporting
            lazy val job = Jobs.started(ctx, record, JobRequest(ask.reference, Document.DisputeReport, "dispute", fmt, from, to,
              ask.parameters, operator, Some(player), name, file, DisputeReport.contentType(fmt)))
            val long = m match
              case Mode.Range(f, t) => ChronoUnit.DAYS.between(f, t) + 1 > settings.syncDays
              case _                => false
            if long then job
            else
              val sessions = logins(r, ask).map(section(r, ask, _))
              val rounds   = sessions.map(_.rounds.size.toLong).sum
              if rounds > settings.syncRows then job
              else
                val meta = metaOf(r, ask, log)
                val body = DisputeReport.bytes(fmt, DisputeReport.html(meta, sessions), DisputeReport.csv(meta, sessions))
                Keeping.keep(ctx.components, ctx.clients.storage.default, record.copy(readyAt = Some(at), rows = rounds), body, Vector.empty,
                  settings.lifetimeOf(Document.DisputeReport))
                Keeping.served(ctx.components, ask.reference, operator, at)
                attachment(DisputeReport.contentType(fmt), file, body)

  private def metaOf(r: Reads, ask: Ask, log: PlayerLogState): DisputeMeta =
    DisputeMeta(ask.reference, ask.generatedAt, Personals.shown(log.email), r.settings.brand, ask.periodFrom, ask.periodTo, ask.currency)

  // ── what it holds ─────────────────────────────────────────────────────────────────────────────

  /** The login sessions reported, by start: the one asked for, or those of the player started in the range. */
  private def logins(r: Reads, ask: Ask): Vector[SessionState] = ask.mode match
    case Mode.OneSession(id) => Vector(session(r, id))
    case Mode.Range(from, to) =>
      Sessions.ofPlayer(r.views, r.generation, ask.player, r.startOf(from), r.startOf(to.plusDays(1)))
        .filter(_.kind == SessionKinds.Player).map(row => session(r, row.sessionId))

  /** A round as the report lists it: the provider's figures and outcome, or the wallet's, unmatched, when it has none. */
  private def roundOf(row: RoundRow): Option[(Instant, Long, Long, String)] =
    row.provider.map(p => (p.at, p.stake, p.win, p.outcome))
      .orElse(row.wallet.flatMap(w => w.at.map(at => (at, w.stake - w.rollback, w.win, Unmatched))))

  private def within(at: Instant, start: Option[Instant], end: Option[Instant]): Boolean =
    start.forall(!at.isBefore(_)) && end.forall(!at.isAfter(_))

  /**
   * A login session's section: its game sessions with their rounds, by time and numbered from 1, the game's name and
   * type from its rounds (else its id, and no type, as the original); and the rounds of the session's span that the
   * provider's record lacks, each under the game session spanning it, else under an unknown game.
   */
  private def section(r: Reads, ask: Ask, s: SessionState): DisputeSession =
    val games  = Sessions.gamesOf(r.views, r.generation, s.sessionId)
    val wallet = s.startedAt.fold(Vector.empty[RoundRow])(start =>
      Rounds.walletOnly(r.views, r.generation, ask.player, start, s.endedAt.map(_.plusMillis(1)).getOrElse(ask.generatedAt.plusMillis(1))))
    def home(row: RoundRow): Option[String] =
      row.wallet.flatMap(_.at).flatMap(at => games.filter(g => within(at, g.startedAt, g.endedAt)).sortBy(_.startedAt).lastOption)
        .map(_.sessionId)
    val placed = wallet.groupBy(home)
    def numbered(rows: Vector[RoundRow]): Vector[DisputeRound] =
      rows.flatMap(roundOf).sortBy(_._1).zipWithIndex.map { case ((at, stake, win, status), i) => DisputeRound(i + 1, at, stake, win, status) }
    val sections = games.map { g =>
      val provided = Rounds.ofGameSession(r.views, r.generation, g.sessionId).filter(_.player == ask.player)
      val name = provided.flatMap(_.provider).flatMap(_.gameName).headOption.orElse(g.gameId).getOrElse("")
      val tpe  = provided.flatMap(_.provider).flatMap(_.gameType).headOption.getOrElse("")
      DisputeGame(name, tpe, g.startedAt, g.endedAt, numbered(provided ++ placed.getOrElse(Some(g.sessionId), Vector.empty)))
    }
    val stray = numbered(placed.getOrElse(None, Vector.empty))
    val unknown = Option.when(stray.nonEmpty)(DisputeGame(UnknownGame, "", stray.headOption.map(_.at), stray.lastOption.map(_.at), stray))
    DisputeSession(s.sessionId, s.startedAt, s.endedAt, s.closeReason, shown(s.networkAddress), shown(s.userAgent), sections ++ unknown)

  // ── the job's pages ───────────────────────────────────────────────────────────────────────────

  /**
   * A job's page `n` (research R9): login sessions from the one the last page stopped at, to as many rounds as a page
   * holds (one session at least), the head on the first page. The running totals and the next session are carried, so
   * the tail is written from them; the email is read again for the head and is never in the job's state.
   */
  def jobPage(r: Reads, req: JobRequest, n: Int, carried: Map[String, Long]): Jobs.Rendered =
    val ask      = Ask.of(req)
    val all      = logins(r, ask)
    val next     = carried.getOrElse("next", 0L).toInt
    val taken    = Vector.newBuilder[DisputeSession]
    var rounds   = 0L
    var i        = next
    while i < all.size && (i == next || rounds < Jobs.PageRows) do
      val s = section(r, ask, all(i))
      taken += s
      rounds += s.rounds.size
      i += 1
    val sections = taken.result()
    val head = if n == 0 then
      val meta = metaOf(r, ask, playerLog(r, ask.player))
      if ask.format == "csv" then DisputeReport.csvHead(meta) else DisputeReport.htmlHead(meta)
    else ""
    val body = sections.map(s => if ask.format == "csv" then DisputeReport.csvSession(s, ask.currency) else DisputeReport.htmlSession(s, ask.currency))
    val t = sections.foldLeft(totals(carried))(_ + _)
    val done = next + sections.size
    Jobs.Rendered(head + body.mkString, Vector.empty, t.rounds - totals(carried).rounds, all.size.toLong,
      carried ++ Map("next" -> done.toLong, "done" -> done.toLong, "stake" -> t.stake, "win" -> t.win, "rounds" -> t.rounds),
      last = done >= all.size)

  private def totals(carried: Map[String, Long]): DisputeTotals =
    DisputeTotals(carried.getOrElse("stake", 0L), carried.getOrElse("win", 0L), carried.getOrElse("rounds", 0L))

  /** The pages joined with the tail from the carried totals, and as PDF when that was asked. */
  def jobFinish(r: Reads, req: JobRequest, pages: String, carried: Map[String, Long]): Array[Byte] =
    val ask  = Ask.of(req)
    val meta = DisputeMeta(ask.reference, ask.generatedAt, "", r.settings.brand, ask.periodFrom, ask.periodTo, ask.currency)
    ask.format match
      case "csv" => (pages + DisputeReport.csvTail(meta, totals(carried))).getBytes(UTF_8)
      case "pdf" => DisputeReport.pdf(pages + DisputeReport.htmlTail(meta, totals(carried)))
      case _     => (pages + DisputeReport.htmlTail(meta, totals(carried))).getBytes(UTF_8)
