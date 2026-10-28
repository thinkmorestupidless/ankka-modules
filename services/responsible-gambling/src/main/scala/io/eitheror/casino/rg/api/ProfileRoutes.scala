package io.eitheror.casino.rg.api

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.rg.Json
import io.eitheror.casino.rg.Json.*
import io.eitheror.casino.rg.profile.*
import io.eitheror.casino.rg.usage.{DayAt, SessionDayEntity}

import java.util.Locale
import scala.util.Try

import Answers.*

/**
 * The changes a player and an operator both make (FR-001 to FR-020), each one command on the profile,
 * answered with the status the route names. Who asks is the actor; the routes differ only in that.
 */
final class ProfileRoutes(ctx: Ctx):

  private def body(text: String)(f: JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)

  private def statusOr(asked: Option[String])(a: ProfileAnswer): Reply =
    a.refusal.fold(ok(Render.status(ctx, a.state, asked)))(refused)

  /**
   * The currency of a money limit (FR-006): the one named, an ISO 4217 code; or, with none named, the one
   * currency the player's limits already use. Otherwise `invalid_currency`.
   */
  private def currencyOf(playerId: String, limitType: String, named: Option[String]): Either[Reply, Option[String]] =
    LimitType.parse(limitType) match
      case Some(t) if t.money =>
        named.map(_.trim.toUpperCase(Locale.ROOT)).filter(_.nonEmpty) match
          case Some(c) if c.matches("[A-Z]{3}") && Try(java.util.Currency.getInstance(c)).isSuccess => Right(Some(c))
          case Some(_) => Left(refused(Refusal("invalid_currency")))
          case None =>
            Render.currencies(ctx.state(playerId), None) match
              case Vector(only) => Right(Some(only))
              case _            => Left(refused(Refusal("invalid_currency")))
      case _ => Right(None)

  def status(playerId: String, asked: Option[String]): Reply =
    Try(ctx.state(playerId)).fold(_ => refusal(503, "service_unavailable", "The profile cannot be read"),
      s => ok(Render.status(ctx, s.copy(playerId = playerId), asked)))

  def history(playerId: String): Reply =
    Try(ctx.state(playerId)).fold(_ => refusal(503, "service_unavailable", "The history cannot be read"), s => ok(Render.history(s)))

  def setLimit(playerId: String, limitType: String, text: String, actor: Actor): Reply = body(text) { b =>
    currencyOf(playerId, limitType, b.text("currency")) match
      case Left(r) => r
      case Right(currency) =>
        val now = ctx.now()
        val minimum = if limitType.equalsIgnoreCase(LimitType.SessionMinutes.name) then ctx.brandSettings.minSessionMinutes else 0
        statusOr(currency)(ctx.profile(playerId).call(ProfileEntity.setLimit)
          .invoke(SetLimit(playerId, limitType, currency, b.text("value"), actor, minimum, now, now.plus(ctx.settings.loosenDelay))))
  }

  def removeLimit(playerId: String, limitType: String, currency: Option[String], actor: Actor): Reply =
    currencyOf(playerId, limitType, currency) match
      case Left(r) => r
      case Right(c) =>
        val now = ctx.now()
        statusOr(c)(ctx.profile(playerId).call(ProfileEntity.removeLimit)
          .invoke(RemoveLimit(playerId, limitType, c, actor, now, now.plus(ctx.settings.loosenDelay))))

  def setBreak(playerId: String, text: String, actor: Actor): Reply = body(text) { b =>
    if b.text("until").isEmpty then refusal(400, "until_required", "until is required")
    else
      instant(b, "until") match
        case None => refusal(400, "invalid_until", "until is not an instant")
        case Some(until) =>
          statusOr(None)(ctx.profile(playerId).call(ProfileEntity.setBreak)
            .invoke(SetBreak(playerId, until, actor, ctx.brandSettings.maxBreakDays, ctx.now())))
  }

  def liftBreak(playerId: String, text: String, operatorId: String): Reply = body(text) { b =>
    statusOr(None)(ctx.profile(playerId).call(ProfileEntity.liftBreak).invoke(LiftBreak(operatorId, b.textOr("reason", ""), ctx.now())))
  }

  def exclude(playerId: String, period: String, actor: Actor): Reply =
    val a = ctx.profile(playerId).call(ProfileEntity.exclude).invoke(Exclude(playerId, period, actor, ctx.now()))
    a.refusal.fold(ok(Json.obj("status" -> "self_excluded")))(refused)

  def requestReturn(playerId: String, actor: Actor): Reply =
    val now = ctx.now()
    val a   = ctx.profile(playerId).call(ProfileEntity.requestReturn).invoke(RequestReturn(actor, now, now.plus(ctx.settings.returnDelay)))
    a.refusal.fold(json(202, Json.obj("reinstatesAt" -> a.state.returnRequest.map(_.reinstatesAt.toString))))(refused)

  def setRealityCheck(playerId: String, text: String): Reply = body(text) { b =>
    b.long("intervalMinutes") match
      case None if b.text("intervalMinutes").isEmpty => refusal(400, "interval_required", "intervalMinutes is required")
      case None    => refused(Refusal("invalid_interval"))
      case Some(i) => statusOr(None)(ctx.profile(playerId).call(ProfileEntity.setRealityCheck).invoke(SetRealityCheck(Some(i.toInt), ctx.now())))
  }

  def removeRealityCheck(playerId: String): Reply =
    statusOr(None)(ctx.profile(playerId).call(ProfileEntity.setRealityCheck).invoke(SetRealityCheck(None, ctx.now())))

  /** The session must be one of the player's game sessions of today or yesterday (FR-024). */
  def acknowledge(playerId: String, text: String): Reply = body(text) { b =>
    val sessionId = b.textOr("sessionId", "")
    val now       = ctx.now()
    val own = sessionId.nonEmpty && Seq(now, now.minus(java.time.Duration.ofDays(1))).exists { day =>
      Try(ctx.clients.componentClient.forKeyValueEntity(com.thinkmorestupidless.ankka.core.EntityId(SessionDayEntity.id(playerId, day)))
        .call(SessionDayEntity.get).invoke(DayAt(now)).sessions.contains(sessionId)).getOrElse(false)
    }
    val a = ctx.profile(playerId).call(ProfileEntity.acknowledge).invoke(Acknowledge(sessionId, own, now))
    a.refusal.fold(ok(Json.obj("acknowledged" -> true)))(refused)
  }
