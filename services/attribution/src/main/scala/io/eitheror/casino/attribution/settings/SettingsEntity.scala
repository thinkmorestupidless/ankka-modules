package io.eitheror.casino.attribution.settings

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.AttributionCodecs

import java.time.{Instant, LocalDate}

/** A brand signing key's public half (research R14); the private half is the store's `signing-key/<kid>`. */
final case class SigningKey(kid: String, publicKey: String, madeAt: Instant, retiredAt: Option[Instant] = None):
  def live: Boolean = retiredAt.isEmpty

final case class TransitionEnd(day: String, endedBy: String)

/**
 * The brand's protocol settings and dates (data-model.md *Settings*): the two windows, the protocol's
 * first day, the transition's end, the legacy tags presented per day (kept 90 days, SC-008), and the
 * brand's signing keys.
 */
final case class BrandSettings(
    attributionWindowDays: Int = 30,
    bonusCodeWindowDays: Int = 30,
    firstDay: Option[String] = None,
    transitionEnd: Option[TransitionEnd] = None,
    lastLegacyDay: Option[String] = None,
    legacyDaily: Map[String, Long] = Map.empty,
    signingKeys: Vector[SigningKey] = Vector.empty,
    updatedBy: Option[String] = None,
    updatedAt: Option[Instant] = None
):
  def transitionOver: Boolean = transitionEnd.isDefined
  def liveKeys: Vector[SigningKey] = signingKeys.filter(_.live)
  /** The key pages and webhooks are signed under: the newest live one. */
  def signingKey: Option[SigningKey] = liveKeys.sortBy(_.madeAt).lastOption

  /** Legacy tags presented in the 30 days up to `today`, for the key document's transition. */
  def legacyLast30Days(today: LocalDate): Long =
    legacyDaily.collect { case (d, n) if !LocalDate.parse(d).isBefore(today.minusDays(29)) && !LocalDate.parse(d).isAfter(today) => n }.sum

final case class SetWindows(attributionDays: Int, bonusCodeDays: Int, by: String, at: Instant)
final case class EndTransition(day: String, endedBy: String)
final case class AddSigningKey(kid: String, publicKey: String, at: Instant)
final case class RetireSigningKey(kid: String, at: Instant)

object SettingsCodecs:
  given Serializer[BrandSettings]    = AttributionCodecs.serializer[BrandSettings]("brand-settings")
  given Serializer[SetWindows]       = AttributionCodecs.serializer[SetWindows]("set-windows")
  given Serializer[EndTransition]    = AttributionCodecs.serializer[EndTransition]("end-transition")
  given Serializer[AddSigningKey]    = AttributionCodecs.serializer[AddSigningKey]("add-signing-key")
  given Serializer[RetireSigningKey] = AttributionCodecs.serializer[RetireSigningKey]("retire-signing-key")

import SettingsCodecs.given

final class SettingsEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[BrandSettings]:
  def emptyState: BrandSettings = BrandSettings()

  /** The two windows, each 1 to 365 days (FR-007, FR-008). */
  def setWindows(cmd: SetWindows): Effect[BrandSettings] =
    if cmd.attributionDays < 1 || cmd.attributionDays > 365 then
      effects.error("attributionWindowDays: a window is 1 to 365 days", ErrorCode.BadRequest)
    else if cmd.bonusCodeDays < 1 || cmd.bonusCodeDays > 365 then
      effects.error("bonusCodeWindowDays: a window is 1 to 365 days", ErrorCode.BadRequest)
    else
      effects.updateState(currentState.copy(attributionWindowDays = cmd.attributionDays, bonusCodeWindowDays = cmd.bonusCodeDays,
        updatedBy = Some(cmd.by), updatedAt = Some(cmd.at))).thenReplyState

  /** The brand's first day on the protocol, set once at the first start (FR-004). */
  def start(day: String): Effect[BrandSettings] =
    if currentState.firstDay.isDefined then effects.reply(currentState)
    else effects.updateState(currentState.copy(firstDay = Some(day))).thenReplyState

  /** A legacy tag presented on `day`: counted, and the last legacy day moved (research R16). */
  def legacySeen(day: String): Effect[BrandSettings] =
    val s = currentState
    effects.updateState(s.copy(
      legacyDaily = s.legacyDaily.updated(day, s.legacyDaily.getOrElse(day, 0L) + 1),
      lastLegacyDay = Some(s.lastLegacyDay.filter(_ > day).getOrElse(day)))).thenReplyState

  /** The transition's end, once. */
  def endTransition(cmd: EndTransition): Effect[BrandSettings] =
    if currentState.transitionEnd.isDefined then effects.reply(currentState)
    else effects.updateState(currentState.copy(transitionEnd = Some(TransitionEnd(cmd.day, cmd.endedBy)))).thenReplyState

  /** Drops the daily legacy counts older than 90 days before `today`. */
  def pruneLegacy(today: String): Effect[BrandSettings] =
    val cutoff = LocalDate.parse(today).minusDays(90).toString
    val kept   = currentState.legacyDaily.filter((d, _) => d >= cutoff)
    if kept.size == currentState.legacyDaily.size then effects.reply(currentState)
    else effects.updateState(currentState.copy(legacyDaily = kept)).thenReplyState

  def addSigningKey(cmd: AddSigningKey): Effect[BrandSettings] =
    if currentState.signingKeys.exists(_.kid == cmd.kid) then effects.error(s"a signing key ${cmd.kid} exists", ErrorCode.Conflict)
    else effects.updateState(currentState.copy(signingKeys = currentState.signingKeys :+ SigningKey(cmd.kid, cmd.publicKey, cmd.at))).thenReplyState

  /** Retires a key; the last live key cannot be retired, or nothing could sign a page. */
  def retireSigningKey(cmd: RetireSigningKey): Effect[BrandSettings] =
    val s = currentState
    s.signingKeys.find(_.kid == cmd.kid) match
      case None                    => effects.error(s"no signing key ${cmd.kid}", ErrorCode.NotFound)
      case Some(k) if !k.live      => effects.reply(s)
      case Some(_) if s.liveKeys.size == 1 => effects.error("the last live signing key cannot be retired", ErrorCode.Conflict)
      case Some(_) =>
        effects.updateState(s.copy(signingKeys = s.signingKeys.map(k => if k.kid == cmd.kid then k.copy(retiredAt = Some(cmd.at)) else k)))
          .thenReplyState

  def get: ReadOnlyEffect[BrandSettings] = effects.reply(currentState)

object SettingsEntity extends KeyValueEntity.Companion[SettingsEntity, BrandSettings](
      componentId = ComponentId("settings"),
      stateSerializer = SettingsCodecs.given_Serializer_BrandSettings
    ):
  import com.thinkmorestupidless.ankka.core.Serializers.given

  /** The one id: the brand's settings are the project's. */
  val Id = "brand"

  def create(context: KeyValueEntityContext) = new SettingsEntity(context)

  val setWindows       = command("set-windows")(_.setWindows)
  val start            = command("start")(_.start)
  val legacySeen       = command("legacy-seen")(_.legacySeen)
  val endTransition    = command("end-transition")(_.endTransition)
  val pruneLegacy      = command("prune-legacy")(_.pruneLegacy)
  val addSigningKey    = command("add-signing-key")(_.addSigningKey)
  val retireSigningKey = command("retire-signing-key")(_.retireSigningKey)
  val get              = query("get")(_.get)
