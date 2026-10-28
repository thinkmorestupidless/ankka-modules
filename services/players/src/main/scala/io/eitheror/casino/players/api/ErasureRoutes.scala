package io.eitheror.casino.players.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.Principal
import io.eitheror.casino.players.{Json, PlayersRuntime}
import io.eitheror.casino.players.erasure.*
import io.eitheror.casino.players.player.PlayerEntity

import java.util.UUID

import Answers.*

/**
 * Erasure and the export (FR-038 to FR-040): filed and started here, carried by [[ErasureWorkflow]] and
 * [[ExportWorkflow]]. The player files their own; an operator files for them and reads the certificates.
 */
final class ErasureRoutes(ctx: Ctx):
  private def components = ctx.clients.componentClient

  def file(playerId: String): Reply =
    val f = ErasureWorkflow.file(components, playerId)
    f.refusal match
      case Some("player_not_found") => refusal(404, "player_not_found", "No such player")
      case Some(code)               => refusal(409, code, "The account must be closed before it is erased")
      case None => json(202, Json.obj("correlationId" -> f.flow.correlationId, "holdUntil" -> f.flow.holdUntil.map(_.toString).orNull))

  def fileMine(p: Principal, playerId: String): Reply = PlayerCaller.matching(p, playerId)(_ => file(playerId))

  /** The erasure's state and each service's certificate (the operator's read). */
  def state(playerId: String): Reply =
    val s = ErasureWorkflow.stateOf(components, playerId)
    if !s.filed then refusal(404, "erasure_not_found", "No erasure is filed for this player")
    else
      ok(Json.obj("correlationId" -> s.correlationId, "holdUntil" -> s.holdUntil.map(_.toString).orNull, "status" -> s.status,
        "heldFor" -> s.heldFor.orNull, "failedAt" -> s.failedAt.orNull,
        "certificates" -> Json.arr(s.certificates.map(c => Json.obj("service" -> c.service, "appliedAt" -> c.appliedAt.toString,
          "certificate" -> Json.parse(c.certificate).orNull))*)))

  /** Starts the export; `statusUrl` is where the caller reads it, as the original answered. */
  def startExport(playerId: String, by: String, statusUrl: String => String = id => s"/v1/casino/backoffice/${ctx.slug}/exports/$id"): Reply =
    val exists = components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.get).invoke().exists
    if !exists then refusal(404, "player_not_found", "No such player")
    else
      val id = UUID.randomUUID().toString
      components.forWorkflow(EntityId(id)).call(ExportWorkflow.start).invoke(StartExport(playerId, by, PlayersRuntime.now())): Unit
      json(202, Json.obj("exportId" -> id, "statusUrl" -> statusUrl(id)))

  def startMine(p: Principal, playerId: String): Reply =
    PlayerCaller.matching(p, playerId)(_ => startExport(playerId, playerId, id => s"/v1/casino/players/${ctx.slug}/$playerId/exports/$id"))

  /** The export once assembled; `409` before, `410` after its retention, `404` for another player's. */
  def read(playerId: Option[String], exportId: String): Reply =
    val s = components.forWorkflow(EntityId(exportId)).call(ExportWorkflow.status).invoke()
    if !s.started || playerId.exists(_ != s.playerId) then refusal(404, "export_not_found", "No such export")
    else if !s.ready then refusal(409, "export_not_ready", "The export is still being assembled")
    else if s.expiresAt.exists(e => !PlayersRuntime.now().isBefore(e)) then refusal(410, "export_expired", "The export has expired")
    else
      val body = components.forKeyValueEntity(EntityId(exportId)).call(ExportBody.get).invoke().body.reveal
      Json.parse(body).filter(_.isObject).fold(refusal(410, "export_expired", "The export has expired"))(ok)

  def readMine(p: Principal, playerId: String, exportId: String): Reply =
    PlayerCaller.matching(p, playerId)(_ => read(Some(playerId), exportId))

  /** The brand's own part of an erasure another filer applies (contracts/routes.md's symmetric route). */
  def eraseOwn(playerId: String): Reply =
    val at = PlayersRuntime.now()
    ErasureWorkflow.eraseOwn(components, ErasureFlow(playerId = playerId), at)
    ok(Json.obj("service" -> ErasureWorkflow.Own, "playerId" -> playerId, "appliedAt" -> at.toString,
      "certificate" -> Json.obj("subject" -> s"player/$playerId", "confirmed" -> true)))

  def ownPart(playerId: String): Reply =
    val p = components.forEventSourcedEntity(PlayerEntity.id(playerId)).call(PlayerEntity.get).invoke()
    if !p.exists then refusal(404, "player_not_found", "No such player")
    else ok(Json.obj("service" -> ErasureWorkflow.Own, "playerId" -> playerId, "part" -> ExportWorkflow.own(components, p)))
