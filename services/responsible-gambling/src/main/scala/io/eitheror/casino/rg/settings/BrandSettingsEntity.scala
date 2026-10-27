package io.eitheror.casino.rg.settings

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.rg.RgCodecs

/** The brand's own bounds (R13): the least session limit a player may set (FR-029) and the longest break (FR-014). */
final case class BrandSettings(minSessionMinutes: Int = 60, maxBreakDays: Int = 42)

/** A change: each field optional, the other kept. */
final case class ChangeSettings(minSessionMinutes: Option[Int] = None, maxBreakDays: Option[Int] = None)

/** The settings as they then stand, or `invalid_setting` naming the field. */
final case class SettingsAnswer(settings: BrandSettings, refusal: Option[String] = None, field: Option[String] = None)

/** One per brand, keyed by the slug. A change binds the next player change and alters no limit already set. */
final class BrandSettingsEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[BrandSettings]:
  def emptyState: BrandSettings = BrandSettings()

  def change(c: ChangeSettings): Effect[SettingsAnswer] =
    def refuse(field: String) = effects.reply(SettingsAnswer(currentState, Some("invalid_setting"), Some(field)))
    if c.minSessionMinutes.exists(m => m < 1 || m > 1440) then refuse("minSessionMinutes")
    else if c.maxBreakDays.exists(d => d < 1 || d > 366) then refuse("maxBreakDays")
    else
      val next = BrandSettings(c.minSessionMinutes.getOrElse(currentState.minSessionMinutes), c.maxBreakDays.getOrElse(currentState.maxBreakDays))
      effects.updateState(next).thenReply(SettingsAnswer(_))

  def get: ReadOnlyEffect[BrandSettings] = effects.reply(currentState)

object BrandSettingsEntity
    extends KeyValueEntity.Companion[BrandSettingsEntity, BrandSettings](
      componentId = ComponentId("rg-brand-settings"),
      stateSerializer = RgCodecs.serializer[BrandSettings]("brand-settings")
    ):
  given Serializer[BrandSettings]  = RgCodecs.serializer[BrandSettings]("brand-settings-reply")
  given Serializer[ChangeSettings] = RgCodecs.serializer[ChangeSettings]("change-settings")
  given Serializer[SettingsAnswer] = RgCodecs.serializer[SettingsAnswer]("settings-answer")

  def create(context: KeyValueEntityContext) = new BrandSettingsEntity(context)

  val change = command("change")(_.change)
  val get    = query("get")(_.get)
