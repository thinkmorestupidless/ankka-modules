package io.eitheror.casino.gameprovider.players

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.gameprovider.money.Amounts

import java.time.{Duration, Instant}
import scala.concurrent.duration.*

// ── LaunchWindow: 20 launches a minute per player (FR-007, research R9) ──────────────────────────

final case class LaunchWindowState(windowStart: Option[Instant] = None, count: Int = 0)

/** Admitted, or refused with the whole seconds until the window opens again. */
final case class LaunchAdmission(admitted: Boolean, retryAfterSeconds: Long)

object LaunchWindowCodecs:
  given Serializer[LaunchWindowState] = Codecs.serializer[LaunchWindowState]("launch-window-state")
  given Serializer[LaunchAdmission]   = Codecs.serializer[LaunchAdmission]("launch-admission")
  given Serializer[Instant]           = Codecs.serializer[Instant]("instant")

import LaunchWindowCodecs.given

/**
 * One player's launches in the current 60-second window, consistent across instances because one entity
 * decides (the original kept it in memory per pod). The state expires two minutes after its last update.
 */
final class LaunchWindow(context: KeyValueEntityContext) extends KeyValueEntity[LaunchWindowState]:
  def emptyState: LaunchWindowState = LaunchWindowState()

  def launch(at: Instant): Effect[LaunchAdmission] =
    val s       = currentState
    val current = s.windowStart.filter(start => Duration.between(start, at).compareTo(LaunchWindow.Window) < 0)
    current match
      case Some(start) if s.count >= LaunchWindow.Limit =>
        val left = LaunchWindow.Window.minus(Duration.between(start, at))
        effects.reply(LaunchAdmission(admitted = false, math.max(1L, math.ceil(left.toMillis / 1000.0).toLong)))
      case Some(start) =>
        effects.updateState(LaunchWindowState(Some(start), s.count + 1)).expireAfter(2.minutes).thenReply(_ => LaunchAdmission(true, 0))
      case None =>
        effects.updateState(LaunchWindowState(Some(at), 1)).expireAfter(2.minutes).thenReply(_ => LaunchAdmission(true, 0))

object LaunchWindow extends KeyValueEntity.Companion[LaunchWindow, LaunchWindowState](
      componentId = ComponentId("launch-window"),
      stateSerializer = LaunchWindowCodecs.given_Serializer_LaunchWindowState
    ):
  val Limit: Int        = 20
  val Window: Duration  = Duration.ofSeconds(60)
  def create(context: KeyValueEntityContext) = new LaunchWindow(context)
  val launch = command("launch")(_.launch)

// ── KnownPlayer: a player known to a provider (FR-010, FR-018, FR-025) ──────────────────────────

final case class KnownPlayerState(providers: Map[String, Instant] = Map.empty, currency: Option[String] = None):
  def knownTo(provider: String): Boolean = providers.contains(provider)

final case class MakeKnown(provider: String, currency: Option[String], at: Instant)

object KnownPlayerCodecs:
  given Serializer[KnownPlayerState] = Codecs.serializer[KnownPlayerState]("known-player-state")
  given Serializer[MakeKnown]        = Codecs.serializer[MakeKnown]("make-known")

import KnownPlayerCodecs.given

/** Holds the player id only by its entity id and the providers it is known to; no personal field (FR-028). */
final class KnownPlayer(context: KeyValueEntityContext) extends KeyValueEntity[KnownPlayerState]:
  def emptyState: KnownPlayerState = KnownPlayerState()

  def makeKnown(cmd: MakeKnown): Effect[KnownPlayerState] =
    val s = currentState
    if s.knownTo(cmd.provider) && cmd.currency.forall(c => s.currency.contains(c)) then effects.reply(s)
    else
      effects.updateState(s.copy(providers = s.providers.updatedWith(cmd.provider)(_.orElse(Some(cmd.at))),
        currency = cmd.currency.orElse(s.currency))).thenReplyState

  def get: ReadOnlyEffect[KnownPlayerState] = effects.reply(currentState)

object KnownPlayer extends KeyValueEntity.Companion[KnownPlayer, KnownPlayerState](
      componentId = ComponentId("known-player"),
      stateSerializer = KnownPlayerCodecs.given_Serializer_KnownPlayerState
    ):
  def create(context: KeyValueEntityContext) = new KnownPlayer(context)
  val makeKnown = command("make-known")(_.makeKnown)
  val get       = query("get")(_.get)

// ── BetLimits: the band ST8 is told (FR-018) ───────────────────────────────────────────────────

object BetLimits:
  /** From the daily deposit limit in minor units: at most 50 major units low, at most 200 medium, else high; medium when none. */
  def band(dailyDepositLimit: Option[Long], currency: Currency): String =
    dailyDepositLimit match
      case None => "medium"
      case Some(minor) =>
        val major = BigDecimal(minor) / BigDecimal(10).pow(Amounts.exponent(currency))
        if major <= 50 then "low" else if major <= 200 then "medium" else "high"
