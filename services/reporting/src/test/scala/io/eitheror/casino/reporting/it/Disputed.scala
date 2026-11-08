package io.eitheror.casino.reporting.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.reporting.documents.{Document, DocumentState, Documents}
import io.eitheror.casino.reporting.intake.{PlayerLog, Session}
import io.eitheror.casino.reporting.reads.{Rounds, Sessions}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Instant, LocalDate}
import java.util.UUID
import scala.concurrent.duration.*

/**
 * A player's play for the dispute report (US5), published as `players`, the wallet and the game provider would: a
 * registration with an email, login sessions with their address and device, game sessions under them, and rounds the
 * provider settled with the wallet's legs for each, or a round the wallet alone settled. What each round is, and how
 * the report must show it, is kept here, so a suite holds the report to the facts.
 */
final class Disputed(val kit: Kit, val day: LocalDate):
  val tag: String     = UUID.randomUUID().toString.take(8)
  val player: String  = s"dp-$tag"
  val email: String   = s"disputant.$tag@example.org"
  val facts           = FactSet(kit.zone)
  import Disputed.*

  private var logins0 = Vector.empty[Login]
  private var games0  = Vector.empty[Game]
  private var rounds0 = Vector.empty[Round]
  def logins: Vector[Login] = logins0
  def games: Vector[Game]   = games0
  def rounds: Vector[Round] = rounds0

  facts.register(player, facts.at(day.minusDays(1), "09:00"), email = Some(email))

  def at(d: LocalDate, time: String): Instant = facts.at(d, time)

  def login(from: Instant, to: Option[Instant], reason: String = "logout"): Login =
    val l = Login(s"ls-$tag-${logins0.size + 1}", s"198.51.${logins0.size + 1}.${tag.take(2).hashCode.abs % 250}", s"Agent/$tag-${logins0.size + 1}",
      from, to, reason)
    logins0 :+= l
    facts.login(player, l.id, from, to, l.address, l.agent, reason)
    l

  def game(l: Login, from: Instant, to: Option[Instant]): Game =
    val n = games0.size + 1
    val g = Game(s"gs-$tag-$n", l.id, s"game-$tag-$n", s"Book of $tag $n", from, to)
    games0 :+= g
    facts.gameSession(player, g.id, g.game, l.id, from, to)
    g

  /** A round the provider settled under `g` and the wallet moved: matched, its status the settlement's outcome. */
  def round(g: Game, at: Instant, stake: Long, win: Long): Round =
    val id = s"rd-$tag-${rounds0.size + 1}"
    facts.bet(player, "EUR", id, stake, stake, at)
    if win > 0 then facts.win(player, "EUR", id, win, win, at.plusSeconds(1))
    facts.settled(player, "EUR", id, "pragmatic", g.game, g.name, g.id, stake, win, at)
    val r = Round(Some(g.id), id, at, stake, win, if win > 0 then "win" else "loss")
    rounds0 :+= r
    r

  /** A round the wallet settled that the provider's record lacks (FR-017): unmatched, under `g` when it spans it. */
  def walletOnly(g: Option[Game], at: Instant, stake: Long, win: Long): Round =
    val id = s"rw-$tag-${rounds0.size + 1}"
    facts.bet(player, "EUR", id, stake, stake, at)
    if win > 0 then facts.win(player, "EUR", id, win, win, at.plusSeconds(1))
    val r = Round(g.map(_.id), id, at, stake, win, "unmatched")
    rounds0 :+= r
    r

  /** The usual play: one login session of two game sessions, three rounds and two, every round matched. */
  def usual(): Disputed =
    val l  = login(at(day, "10:00"), Some(at(day, "12:00")), "player_logout")
    val g1 = game(l, at(day, "10:05"), Some(at(day, "10:30")))
    round(g1, at(day, "10:06"), 5_00, 0)
    round(g1, at(day, "10:07"), 2_00, 7_50)
    round(g1, at(day, "10:08"), 1_00, 1_00)
    val g2 = game(l, at(day, "10:40"), Some(at(day, "11:00")))
    round(g2, at(day, "10:41"), 3_00, 0)
    round(g2, at(day, "10:42"), 4_00, 12_00)
    this

  /** Published, and waited for: the player, every login session, and every round in its final state in `Rounds`. */
  def publish(): Disputed =
    facts.publish(kit)
    val g = kit.settings.generation
    kit.eventually(s"$player registered")(Option.when(
      kit.components.forEventSourcedEntity(EntityId(PlayerLog.id(g, player))).call(PlayerLog.get).invoke().player == player)(()))
    for l <- logins0 do
      kit.eventually(s"${l.id} recorded")(Option.when {
        val s = kit.components.forKeyValueEntity(EntityId(Session.id(g, l.id))).call(Session.get).invoke()
        s.startedAt.contains(l.started) && s.endedAt == l.ended
      }(()))
      // The report finds sessions through the `Sessions` view, which follows the entities.
      kit.eventually(s"${l.id} in the view")(Option.when(
        Sessions.ofPlayer(kit.views, g, player, l.started, l.started.plusMillis(1)).exists(r => r.sessionId == l.id && r.endedAt == l.ended) &&
          games0.filter(_.login == l.id).forall(gm => Sessions.gamesOf(kit.views, g, l.id).exists(r => r.sessionId == gm.id && r.endedAt == gm.ended)))(()))
    for gm <- games0 do
      val want = rounds0.filter(r => r.game.contains(gm.id) && r.status != "unmatched").size
      kit.eventually(s"${gm.id}'s rounds matched")(Option.when(
        Rounds.ofGameSession(kit.views, g, gm.id).count(_.status == "matched") == want)(()))
    for r <- rounds0.filter(_.status == "unmatched") do
      kit.eventually(s"${r.round} the wallet's only")(Option.when(
        Rounds.walletOnly(kit.views, g, player, r.at.minusSeconds(1), r.at.plusSeconds(1)).exists(_.roundId == r.round))(()))
    this

  def money(v: Long): String = java.math.BigDecimal.valueOf(v, 2).toPlainString

  /** The report as asked, by an operator of the brand. */
  def report(query: String, as: Seq[(String, String)] = kit.asOperator): Reply =
    kit.get(s"/players/$player/dispute-report?$query", as)

  def bySession(l: Login = logins0.head, format: String = ""): Reply =
    report(s"sessionId=${l.id}" + (if format.isEmpty then "" else s"&format=$format"))

  def byRange(from: LocalDate, to: LocalDate, format: String = ""): Reply =
    report(s"fromDate=$from&toDate=$to" + (if format.isEmpty then "" else s"&format=$format"))

object Disputed:
  /** A round as the report must list it: its game session, time, stake, win and status. */
  final case class Round(game: Option[String], round: String, at: Instant, stake: Long, win: Long, status: String):
    def net: Long = win - stake

  final case class Login(id: String, address: String, agent: String, started: Instant, ended: Option[Instant], reason: String)
  final case class Game(id: String, login: String, game: String, name: String, started: Instant, ended: Option[Instant])

  /** The reference in an attachment's file name `dispute-report-<reference>.<ext>`. */
  def reference(r: Reply): String =
    val name = r.header("Content-Disposition").getOrElse(throw AssertionError(s"no attachment: ${r.status} ${r.text}"))
    "dispute-report-([0-9a-f-]{36})\\.".r.findFirstMatchIn(name).map(_.group(1)).getOrElse(throw AssertionError(name))

  def document(kit: Kit, reference: String): DocumentState =
    kit.components.forEventSourcedEntity(EntityId(reference)).call(Document.get).invoke()

  /** A job's document once ready: polled through `GET /documents/{reference}` until it is served. */
  def served(kit: Kit, reference: String, as: Seq[(String, String)]): Reply =
    kit.eventually(s"document $reference served", 120.seconds)(Some(kit.get(s"/documents/$reference", as)).filter(_.status != 202))

  /**
   * Waits until the `Documents` view holds the record ready: the sweep reads the view, so a test that moves the clock
   * past a lifetime waits for it first, or the one run the move causes may find nothing.
   */
  def inView(kit: Kit, reference: String): Unit =
    kit.eventually(s"$reference in the view")(Documents.keptNow(kit.views).find(d => d.reference == reference && d.status == Document.Ready)): Unit

  def text(kit: Kit, name: String): Option[String] =
    kit.testKit.storage.default.getBytes(name).map((_, b) => String(b, UTF_8))
