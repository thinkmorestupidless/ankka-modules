package io.eitheror.casino.players.api

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.Principal
import io.eitheror.casino.players.{Json, PlayersRuntime}
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.fraud.*
import io.eitheror.casino.players.player.{ClearDuplicate, PlayerEntity}

import java.time.Instant

import Answers.*

/**
 * The operator's fraud cases (FR-037), in the original's shapes (`FraudCaseRoutes`): the list by status,
 * a review, and a dismissal, which here also clears the flag on every player the case links (the
 * original left the flags set).
 */
final class FraudRoutes(ctx: Ctx):
  private def components = ctx.clients.componentClient
  private def now(): Instant = PlayersRuntime.now()

  /** `status` `OPEN`, `REVIEWED`, `DISMISSED` or `ALL` (none is all), as the original. */
  def cases(status: Option[String]): Reply =
    val wanted = status.map(_.trim.toLowerCase).filter(s => s.nonEmpty && s != "all")
    val rows   = FraudCasesView.withStatus(ctx.clients.viewClient, wanted)
    ok(Json.obj("cases" -> Json.arr(rows.map(caseOf)*)))

  def review(p: Principal, caseId: String): Reply =
    val a = components.forEventSourcedEntity(EntityId(caseId)).call(FraudCaseEntity.review).invoke(DecideCase(p.subject, now()))
    decided(caseId, a)

  /** `{"notes": "…"}`, as the original; kept `Personal` and answered as `reviewNotes`. */
  def dismiss(p: Principal, caseId: String, text: String): Reply =
    val notes = Json.parse(text).filter(_.isObject).flatMap(_.text("notes")).filter(_.nonEmpty).getOrElse("")
    val a     = components.forEventSourcedEntity(EntityId(caseId)).call(FraudCaseEntity.dismiss).invoke(DecideCase(p.subject, now(), notes))
    if a.refusal.isEmpty then FraudRoutes.clear(ctx, caseId, a.state.playerIds, p.subject)
    decided(caseId, a, withNotes = true)

  private def decided(caseId: String, a: CaseAnswer, withNotes: Boolean = false): Reply =
    a.refusal match
      case Some("case_not_found") => refusal(404, "case_not_found", "No such fraud case")
      case Some(code)             => refusal(409, code, code.replace('_', ' '))
      case None =>
        val o = Json.obj("caseId" -> caseId, "status" -> FraudCaseStatus.wire(a.state.status), "reviewedBy" -> a.state.reviewedBy.orNull,
          "reviewedAt" -> a.state.reviewedAt.map(_.toString).orNull)
        if withNotes then Json.set(o, "reviewNotes", notesOf(a.state))
        ok(o)

  private def caseOf(r: FraudCaseRow): ObjectNode =
    val s = components.forEventSourcedEntity(EntityId(r.caseId)).call(FraudCaseEntity.get).invoke()
    Json.obj("caseId" -> r.caseId, "playerIds" -> Json.arr(r.playerIds*), "brandId" -> ctx.slug, "signals" -> Json.arr(r.signals*),
      "status" -> FraudCaseStatus.wire(r.status), "detectedAt" -> Instant.ofEpochMilli(r.openedAt).toString,
      "reviewedBy" -> s.reviewedBy.orNull, "reviewedAt" -> s.reviewedAt.map(_.toString).orNull, "reviewNotes" -> notesOf(s))

  /** The notes, or `null` when there are none or a linked player's erasure blanked them. */
  private def notesOf(s: FraudCaseState): String = Option(s.reviewNotes.reveal).filter(_.nonEmpty).orNull

object FraudRoutes:
  /** The case's flag cleared on each player it links. */
  def clear(ctx: Ctx, caseId: String, players: Set[String], operatorId: String): Unit =
    val at = PlayersRuntime.now()
    players.toVector.sorted.foreach(p => ctx.clients.componentClient.forEventSourcedEntity(PlayerEntity.id(p)).call(PlayerEntity.clearDuplicate)
      .invoke(ClearDuplicate(caseId, Some(operatorId), at)): Unit)
