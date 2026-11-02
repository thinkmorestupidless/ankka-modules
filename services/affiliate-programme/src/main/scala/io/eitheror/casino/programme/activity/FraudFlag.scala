package io.eitheror.casino.programme.activity

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID

/**
 * A player whose bonus costs or chargebacks crossed the brand's threshold (FR-032), for a manager to
 * review. Id `<brand>/<flagId>`, the flag id a UUID of the player, brand and reason as the original's,
 * so a flag is raised once for each and a route names it with one path segment.
 */
final case class FlagState(
    flagId: String = "",
    brand: String = "",
    pseudonym: String = "",
    affiliate: String = "",
    reason: String = "",
    description: String = "",
    period: String = "",
    raisedAt: Option[Instant] = None,
    dismissedBy: Option[String] = None,
    dismissedAt: Option[Instant] = None,
    dismissalReason: Option[String] = None
):
  def raised: Boolean    = flagId.nonEmpty
  def dismissed: Boolean = dismissedAt.isDefined

final case class RaiseFlag(brand: String, pseudonym: String, affiliate: String, reason: String, description: String, period: String,
    at: Instant)
final case class DismissFlag(by: String, reason: String, at: Instant)

object FlagCodecs:
  given Serializer[FlagState]   = ProgrammeCodecs.serializer[FlagState]("fraud-flag")
  given Serializer[RaiseFlag]   = ProgrammeCodecs.serializer[RaiseFlag]("raise-flag")
  given Serializer[DismissFlag] = ProgrammeCodecs.serializer[DismissFlag]("dismiss-flag")

import FlagCodecs.given

final class FraudFlag(context: KeyValueEntityContext) extends KeyValueEntity[FlagState]:
  def emptyState: FlagState = FlagState()

  /** Once: a flag already raised, dismissed or not, is not raised again. */
  def raise(c: RaiseFlag): Effect[FlagState] =
    if currentState.raised then effects.reply(currentState)
    else
      val id = context.entityId.toString.split('/').last
      effects.updateState(FlagState(id, c.brand, c.pseudonym, c.affiliate, c.reason, c.description, c.period, Some(c.at))).thenReplyState

  def dismiss(c: DismissFlag): Effect[FlagState] =
    if !currentState.raised then effects.error("not_found", ErrorCode.NotFound)
    else if currentState.dismissed then effects.reply(currentState)
    else effects.updateState(currentState.copy(dismissedBy = Some(c.by), dismissedAt = Some(c.at), dismissalReason = Some(c.reason))).thenReplyState

  def get: ReadOnlyEffect[FlagState] = effects.reply(currentState)

object FraudFlag extends KeyValueEntity.Companion[FraudFlag, FlagState](
      componentId = ComponentId("fraud-flag"),
      stateSerializer = FlagCodecs.given_Serializer_FlagState
    ):
  val BonusCost  = "bonus-cost"
  val Chargeback = "chargeback"

  def flagId(brand: String, pseudonym: String, reason: String): String =
    UUID.nameUUIDFromBytes(s"aff.fraud:$brand:$pseudonym:$reason".getBytes(UTF_8)).toString

  def entityId(brand: String, flag: String): EntityId = EntityId(s"$brand/$flag")

  def create(context: KeyValueEntityContext) = new FraudFlag(context)

  val raise   = command("raise")(_.raise)
  val dismiss = command("dismiss")(_.dismiss)
  val get     = query("get")(_.get)

/** A row per flag: a brand's flags for its managers (O48). */
final class FraudFlagsView extends View[FlagState, FlagState]:
  def onChange(s: FlagState): Effect = effects.updateRow(s)

object FraudFlags extends View.Companion[FraudFlagsView, FlagState, FlagState](
      componentId = ComponentId("fraud-flags"),
      source = ChangeSource.stateOf(FraudFlag),
      rowSerializer = FlagCodecs.given_Serializer_FlagState
    ):
  val ofBrand = query("of-brand")(s"SELECT payload FROM $table WHERE payload::jsonb->>'brand' = :brand ORDER BY payload::jsonb->>'raisedAt', row_key")

  def create(ctx: ViewComponentContext) = new FraudFlagsView
