package io.eitheror.casino.publicapi.api

import com.fasterxml.jackson.databind.ObjectMapper
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Acl, Bytes, EndpointClients, HttpEndpoint, Respond}
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.publicapi.records.*
import io.eitheror.casino.publicapi.stats.{StatsPointer, StatsRunner}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.util.Try

/**
 * `GET /public-api/{brand}/status` for the brand's operators (contracts/routes.md): when the statistics were last
 * computed, whether they are stale (older than three hours, FR-033), the current snapshot, and for each record view the
 * time of the latest record it holds. ankka reports no view's applied offset, so a view's `lastAppliedAt` is that
 * record's own time (research, Found during implementation).
 */
final class StatusEndpoint(slug: String, clients: EndpointClients, authenticate: () => Acl, now: () => Instant)
    extends HttpEndpoint("/public-api"):
  val acl: Acl = Routes.operator(slug, authenticate())

  get("/{brand}/status") { (brand: String) =>
    Routes.brandSegment(slug, brand).fold(p => throw p, _ => Respond(Bytes("application/json", StatusEndpoint.mapper.writeValueAsString(status()).getBytes(UTF_8)), 200))
  }

  private def status() =
    val mapper  = StatusEndpoint.mapper
    val pointer = clients.componentClient.forKeyValueEntity(EntityId(StatsPointer.Id)).call(StatsPointer.get).invoke()
    val out     = mapper.createObjectNode()
    pointer.currentSlot match
      case Some((_, slot)) =>
        slot.computedAt.foreach(c => out.put("statsComputedAt", c.toString))
        out.put("statsStale", StatsRunner.stale(pointer, now()))
        out.set("snapshot", mapper.createObjectNode().put("id", slot.snapshotId.getOrElse("")).put("computedAt", slot.computedAt.fold("")(_.toString)))
      case None => out.put("statsStale", true)
    val views = mapper.createObjectNode()
    def latest[R](name: String, rows: => Vector[R])(time: R => Option[Instant]): Unit =
      val v = mapper.createObjectNode()
      Try(rows).toOption.flatMap(_.headOption).flatMap(time).foreach(t => v.put("lastAppliedAt", t.toString))
      views.set(name, v): Unit
    val q = clients.viewClient
    latest("players", q.forView(Players).ask(Players.latest, 1))(_.registeredAt)
    latest("sessions", q.forView(Sessions).ask(Sessions.latest, 1))(_.startedAt)
    latest("rounds", q.forView(Rounds).ask(Rounds.latest, 1))(_.startedAt)
    latest("bets", q.forView(Bets).ask(Bets.latest, 1))(_.placedAt)
    latest("transactions", q.forView(Transactions).ask(Transactions.latest, 1))(_.createdAt)
    latest("bonusAwards", q.forView(BonusAwards).ask(BonusAwards.latest, 1))(_.grantedAt)
    out.set("views", views)
    out

object StatusEndpoint:
  private val mapper = ObjectMapper()
