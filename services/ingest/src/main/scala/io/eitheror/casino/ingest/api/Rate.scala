package io.eitheror.casino.ingest.api

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.ingest.Json
import io.eitheror.casino.ingest.api.Answers.Reply
import io.eitheror.casino.ingest.rate.RateWindow

/** An operator reads and sets one sub-brand's rate (FR-015): the raise holds until changed. */
final class Rate(ctx: Ctx, subBrand: String):
  private def window = ctx.components.forKeyValueEntity(EntityId(subBrand))

  def read(): Reply =
    val s = window.call(RateWindow.read).invoke()
    Answers.ok(Json.obj("perMinute" -> s.limit.getOrElse(ctx.settings.rateLimit), "default" -> ctx.settings.rateLimit))

  def set(raw: String): Reply =
    Json.parseObject(raw).flatMap(b => Option(b.get("perMinute"))).filter(n => n.isIntegralNumber && n.canConvertToInt)
      .map(_.intValue).filter(_ > 0) match
      case None => Answers.refusal(400, "INVALID_REQUEST", field = Some("perMinute"))
      case Some(n) =>
        window.call(RateWindow.setLimit).invoke(n): Unit
        Answers.noContent
