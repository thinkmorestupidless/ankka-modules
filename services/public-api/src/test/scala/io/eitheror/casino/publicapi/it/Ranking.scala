package io.eitheror.casino.publicapi.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.publicapi.records.Players
import io.eitheror.casino.publicapi.stats.PointerState

import java.time.Instant
import java.time.Duration as JDuration
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Players with known figures on `SharedKit.ranked`, and reading their rankings (US6). */
object Ranking:
  lazy val kit: Kit = SharedKit.ranked
  lazy val key: MadeKey = Keys.noFields(kit)
  lazy val all: MadeKey = Keys.allFields(kit)

  def ago(h: Long): Instant = kit.now.minus(JDuration.ofHours(h))

  /** A registered player, its status changed when not active; waits for the Players view to hold it so. */
  def player(status: String = "active", at: Instant = ago(24 * 60)): String =
    val pl = FactSet.id()
    kit.publish(f.CasinoPlayers, FactSet.registered(pl, at))
    status match
      case "closed"        => kit.publish(f.CasinoPlayers, f.PlayerClosed(pl, "active", "closed", "operator", None, None, at.plusSeconds(1)))
      case "self_excluded" => kit.publish(f.CasinoPlayers, f.PlayerSelfExcluded(pl, "active", "self_excluded", "player", None, None, at, None, at.plusSeconds(1)))
      case "suspended"     => kit.publish(f.CasinoPlayers, f.PlayerSuspended(pl, "active", "suspended", "operator", None, None, at.plusSeconds(1)))
      case _               => ()
    pl

  def registered(players: Map[String, String]): Unit =
    kit.eventually("the players in the view", 60.seconds)(Option.when(players.forall((pl, st) => kit.views.forView(Players).get(pl).exists(_.status == st)))(()))

  /** A round of `game` with stake `ggr` and no win, so the player's GGR in `currency` rises by `ggr`; its key. */
  def play(pl: String, ggr: Long, currency: String, hoursAgo: Long = 2, game: String = "g-rank"): String =
    Games.round(kit, game, pl, ago(hoursAgo), ggr, 0, currency)

  /** `n` active players, the i-th (from 1) with GGR `base + i` in `currency` over the last day; ids by descending GGR. */
  def many(n: Int, currency: String, base: Long = 1000): Vector[String] =
    val ps = Vector.fill(n)(player())
    registered(ps.map(_ -> "active").toMap)
    val keys = ps.zipWithIndex.map((pl, i) => play(pl, base + i + 1, currency))
    Games.seen(kit, keys)
    ps.reverse

  def compute(): PointerState = Games.compute(kit)
  def snapshot(p: PointerState): String = p.currentSlot.flatMap(_._2.snapshotId).get

  def get(path: String, k: MadeKey = key): Reply = kit.v1(path, Some(k.secret))
  def rows(r: Reply): Vector[JsonNode] = r.json.path("data").elements().asScala.toVector
  def ids(r: Reply): Vector[String]    = rows(r).map(_.path("playerId").asText())
  def ggr(currency: String, offset: Int = 0, limit: Int = 100, snapshot: Option[String] = None, more: String = "", k: MadeKey = key): Reply =
    get(s"/player-stats?period=1d&sort=ggr&currency=$currency&limit=$limit&offset=$offset" + snapshot.fold("")(s => s"&snapshot=$s") + more, k)
