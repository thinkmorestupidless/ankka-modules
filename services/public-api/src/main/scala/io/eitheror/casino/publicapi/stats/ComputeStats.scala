package io.eitheror.casino.publicapi.stats

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.publicapi.records.{Players, Rounds, Sessions, Transactions}
import io.eitheror.casino.publicapi.wire.{Enums, Page}

import java.time.{Duration, Instant, ZoneOffset}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * One run (data-model.md *The run*, research R8): the slot that is not current marked `building` under a new snapshot
 * id; the 30 days' rows read a page at a time; the fold; every row of the slot written, then the slot's older rows
 * emptied; the slot `ready` and current. A failure anywhere leaves the current slot as it was, and the next hour starts
 * again (FR-032).
 */
final class StatsRunner(components: ComponentClient, views: ViewClient, brand: String, now: () => Instant):
  private val log   = org.slf4j.LoggerFactory.getLogger("public-api")
  private val Batch = 1000

  private def pointer = components.forKeyValueEntity(EntityId(StatsPointer.Id))

  private def all[R](read: Page => Vector[R]): Vector[R] =
    Iterator.iterate(0)(_ + Batch).map(o => read(Page(Batch, o))).takeWhile(_.nonEmpty).flatten.toVector

  def run(): PointerState =
    val at       = now()
    val since    = at.minus(StatsRun.Longest).toString
    val before   = pointer.call(StatsPointer.get).invoke()
    val slot     = before.next
    val snapshot = UUID.randomUUID().toString
    pointer.call(StatsPointer.building).invoke(Building(slot, snapshot)): Unit
    def keyed[R](q: com.thinkmorestupidless.ankka.runtime.ViewQueries[R], query: DeclaredQuery)(id: R => String): Vector[R] =
      Iterator.unfold(Option("")) {
        case None => None
        case Some(after) =>
          val rows = q.ask(query, Batch, "since" -> since, "after" -> after, "limit" -> Batch.toString)
          Option.when(rows.nonEmpty)(rows -> Option.when(rows.size == Batch)(id(rows.last)))
      }.flatten.toVector
    val rounds   = keyed(views.forView(Rounds), Rounds.since)(_.id)
    val sessions = keyed(views.forView(Sessions), Sessions.since)(_.id)
    val money    = keyed(views.forView(Transactions), Transactions.since)(_.id)
    val figures  = StatsRun.run(at, rounds, sessions, money)
    figures.games.foreach { (game, windows) =>
      components.forKeyValueEntity(EntityId(GameFigures.key(slot, game))).call(GameFigures.put)
        .invoke(GameFiguresState(slot, snapshot, game, windows)): Unit
    }
    // Players the brand's records serve, with their status as the contract names it at the run (FR-030).
    val statuses = all(p => views.forView(Players).ask(Players.listed, p.limit, "limit" -> p.limit.toString, "offset" -> p.offset.toString))
      .flatMap(pl => Enums.playerStatus(pl.status).map(pl.id -> _)).toMap
    val ranked  = figures.players.filter((pl, _) => statuses.contains(pl))
    val ranks   = StatsRun.rankPlayers(ranked)
    ranked.foreach { (pl, windows) =>
      components.forKeyValueEntity(EntityId(PlayerFigures.key(slot, pl))).call(PlayerFigures.put)
        .invoke(PlayerFiguresState(slot, snapshot, pl, statuses(pl), figures.lastActive.get(pl), windows, ranks.getOrElse(pl, Map.empty))): Unit
    }
    // `ready` means whole: the views the reads use hold every row of the snapshot before it is made current.
    def held[R](q: com.thinkmorestupidless.ankka.runtime.ViewQueries[R], query: DeclaredQuery): Int =
      all(p => q.ask(query, p.limit, "snapshot" -> snapshot, "limit" -> p.limit.toString, "offset" -> p.offset.toString)).size
    StatsRunner.until(s"the snapshot's ${figures.games.size} game and ${ranked.size} player rows in the views", StatsRunner.Settle)(
      held(views.forView(GameFiguresView), GameFiguresView.ofSnapshot) == figures.games.size &&
        held(views.forView(PlayerFiguresView), PlayerFiguresView.ofSnapshot) == ranked.size)
    // The slot's rows of its previous run, now that the views hold this run's: each entity keeps a row rewritten since.
    def stale[R](q: com.thinkmorestupidless.ankka.runtime.ViewQueries[R], query: DeclaredQuery): Vector[R] =
      all(p => q.ask(query, p.limit, "slot" -> slot, "snapshot" -> snapshot, "limit" -> p.limit.toString, "offset" -> p.offset.toString))
    stale(views.forView(GameFiguresView), GameFiguresView.staleInSlot).foreach(r =>
      components.forKeyValueEntity(EntityId(GameFigures.key(slot, r.gameId))).call(GameFigures.remove).invoke(Gone(snapshot)): Unit)
    stale(views.forView(PlayerFiguresView), PlayerFiguresView.staleInSlot).foreach(r =>
      components.forKeyValueEntity(EntityId(PlayerFigures.key(slot, r.playerId))).call(PlayerFigures.remove).invoke(Gone(snapshot)): Unit)
    val after = pointer.call(StatsPointer.publish).invoke(Publish(slot, snapshot, at))
    log.info("public_api_stats_computed brand={} snapshot={} games={} players={} rounds={}", brand, snapshot, figures.games.size,
      ranked.size, rounds.size)
    after

  /** FR-033's alert line, logged at WARN by every run while the current figures are more than three hours old. */
  def warnIfStale(p: PointerState): Unit =
    if StatsRunner.stale(p, now()) then log.warn("public_api_stats_stale brand={}", brand)

object StatsRunner:
  val StaleAfter: Duration = Duration.ofHours(3)
  val Settle: Duration     = Duration.ofMinutes(2)

  /** Polls `done` until it holds, or fails the run naming `what` after `within`. */
  def until(what: String, within: Duration)(done: => Boolean): Unit =
    val deadline = System.nanoTime() + within.toNanos
    while !done do
      if System.nanoTime() > deadline then throw IllegalStateException(s"not settled within $within: $what")
      Thread.sleep(100)


  def stale(p: PointerState, at: Instant): Boolean =
    p.currentSlot.flatMap(_._2.computedAt).forall(c => c.plus(StaleAfter).isBefore(at))


/** What the timed action is built without, set at start (as reporting's `ReportingRuntime`). */
object StatsRuntime:
  val views: AtomicReference[Option[ViewClient]] = AtomicReference(None)
  val brand: AtomicReference[String]             = AtomicReference("")

final class ComputeStats(context: TimedActionContext) extends TimedAction:
  def run: Effect =
    StatsRuntime.views.get.foreach { views =>
      val runner = StatsRunner(context.componentClient, views, StatsRuntime.brand.get, () => context.clock.instant())
      val p = try runner.run()
        catch case scala.util.control.NonFatal(e) =>
          org.slf4j.LoggerFactory.getLogger("public-api").warn("public_api_stats_failed brand={} {}", StatsRuntime.brand.get, e.toString)
          context.componentClient.forKeyValueEntity(EntityId(StatsPointer.Id)).call(StatsPointer.get).invoke()
      runner.warnIfStale(p)
    }
    effects.done()

object ComputeStats extends TimedAction.Companion[ComputeStats](ComponentId("compute-stats")):
  val TimerName = "compute-stats"

  /** Minute 5 of every hour (data-model.md *Timers*); setting the timer again under its name replaces it. */
  def schedule(timers: TimerScheduler, at: Instant): Unit =
    val hour  = at.atZone(ZoneOffset.UTC).withMinute(5).withSecond(0).withNano(0)
    val first = if hour.toInstant.isAfter(at) then hour.toInstant else hour.plusHours(1).toInstant
    timers.createRecurringTimer(TimerName, Duration.between(at, first).toMillis.millis, 1.hour, computeStats.deferred)

  def create(context: TimedActionContext) = new ComputeStats(context)
  val computeStats = handler("compute-stats")(_.run)
