package io.eitheror.casino.rg.settings

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.rg.RgCodecs

import java.time.Instant

/** The brand's own bounds (R13): the least session limit a player may set (FR-029) and the longest break (FR-014). */
final case class BrandSettings(minSessionMinutes: Int = 60, maxBreakDays: Int = 42)

/** A change by an operator: each field optional, the other kept. */
final case class ChangeSettings(minSessionMinutes: Option[Int] = None, maxBreakDays: Option[Int] = None,
    operatorId: String = "", at: Instant = Instant.EPOCH)

/** The settings as they then stand, or `invalid_setting` naming the field. */
final case class SettingsAnswer(settings: BrandSettings, refusal: Option[String] = None, field: Option[String] = None)

/** What an operator changed, so every change is audited with its before and after (FR-028). */
final case class SettingsChanged(before: BrandSettings, after: BrandSettings, operatorId: String, at: Instant)

/**
 * One per brand, keyed by the slug. A change binds the next player change and alters no limit already set.
 * Event sourced, not key value, so the audit sees every change and not only the latest value.
 */
final class BrandSettingsEntity(@scala.annotation.unused context: EventSourcedEntityContext)
    extends EventSourcedEntity[BrandSettings, SettingsChanged]:
  def emptyState: BrandSettings = BrandSettings()

  def applyEvent(event: SettingsChanged): BrandSettings = event.after

  def change(c: ChangeSettings): Effect[SettingsAnswer] =
    def refuse(field: String) = effects.reply(SettingsAnswer(currentState, Some("invalid_setting"), Some(field)))
    if c.minSessionMinutes.exists(m => m < 1 || m > 1440) then refuse("minSessionMinutes")
    else if c.maxBreakDays.exists(d => d < 1 || d > 366) then refuse("maxBreakDays")
    else
      val next = BrandSettings(c.minSessionMinutes.getOrElse(currentState.minSessionMinutes), c.maxBreakDays.getOrElse(currentState.maxBreakDays))
      if next == currentState then effects.reply(SettingsAnswer(currentState))
      else effects.persist(SettingsChanged(currentState, next, c.operatorId, c.at)).thenReply(SettingsAnswer(_))

  def get: ReadOnlyEffect[BrandSettings] = effects.reply(currentState)

object BrandSettingsEntity
    extends EventSourcedEntity.Companion[BrandSettingsEntity, BrandSettings, SettingsChanged](
      componentId = ComponentId("rg-brand-settings"),
      stateSerializer = RgCodecs.serializer[BrandSettings]("brand-settings"),
      eventSerializer = RgCodecs.serializer[SettingsChanged]("brand-settings-changed")
    ):
  given Serializer[BrandSettings]  = RgCodecs.serializer[BrandSettings]("brand-settings-reply")
  given Serializer[ChangeSettings] = RgCodecs.serializer[ChangeSettings]("change-settings")
  given Serializer[SettingsAnswer] = RgCodecs.serializer[SettingsAnswer]("settings-answer")

  def create(context: EventSourcedEntityContext) = new BrandSettingsEntity(context)

  val change = command("change")(_.change)
  val get    = query("get")(_.get)
