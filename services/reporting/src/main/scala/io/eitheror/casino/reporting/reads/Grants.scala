package io.eitheror.casino.reporting.reads

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.ReportingCodecs
import io.eitheror.casino.reporting.intake.{Generation, Grant, GrantEvent, GrantState, Grants as GrantFold}

import java.time.Instant

/**
 * A grant as the bonus report reads it (data-model.md `Grants`; FR-009): its player, offer with the name and type
 * its grant carried (no topic lists offers), currency (none for free rounds until they win), amount, when it was
 * granted and its status, as `Grant` folds them.
 */
final case class GrantRow(generation: Int, bonusId: String = "", player: String = "", offerId: String = "", name: String = "",
    offerType: String = "", currency: Option[String] = None, amount: Long = 0, grantedAt: Option[Instant] = None,
    status: String = "active"):
  def state: GrantState = GrantState(bonusId, player, offerId, name, offerType, currency, amount, grantedAt, status)

  /** Converted whether its cap is pending or closed, as the original counts (`BonusCompleted` is either). */
  def converted: Boolean = status == "converted"

object GrantRow:
  given Serializer[GrantRow] = ReportingCodecs.row[GrantRow]("grant-row")

  def of(generation: Int, before: GrantRow, e: GrantEvent): GrantRow =
    val s = GrantFold.apply(before.state, e)
    GrantRow(generation, s.bonusId, s.player, s.offerId, s.name, s.offerType, s.currency, s.amount, s.grantedAt, s.status)

final class GrantRows extends View[GrantEvent, GrantRow]:
  def onChange(event: GrantEvent): Effect =
    Generation.of(updateContext.subject).fold(effects.ignore()) { g =>
      effects.updateRow(GrantRow.of(g, rowState.getOrElse(GrantRow(g)), event))
    }

object Grants extends View.Companion[GrantRows, GrantEvent, GrantRow](
      componentId = ComponentId("grants"),
      source = ChangeSource.eventsOf(Grant),
      rowSerializer = GrantRow.given_Serializer_GrantRow
    ):
  /** The grants granted from `from` (inclusive) to `to` (exclusive), both instants. */
  val grantedBetween = query("granted-between")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND (payload::jsonb->>'grantedAt')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->>'grantedAt')::timestamptz < (:to)::timestamptz ORDER BY payload::jsonb->>'grantedAt', row_key")

  /** The same, under one offer. */
  val byOffer = query("by-offer")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'generation' = :generation AND payload::jsonb->>'offerId' = :offer AND (payload::jsonb->>'grantedAt')::timestamptz >= (:from)::timestamptz AND (payload::jsonb->>'grantedAt')::timestamptz < (:to)::timestamptz ORDER BY payload::jsonb->>'grantedAt', row_key")

  def create(ctx: ViewComponentContext) = new GrantRows

  def granted(views: ViewClient, generation: Int, from: Instant, to: Instant, offer: Option[String]): Vector[GrantRow] =
    val common = Seq("generation" -> generation.toString, "from" -> from.toString, "to" -> to.toString)
    offer match
      case None    => views.forView(Grants).ask(grantedBetween, 10_000_000, common*)
      case Some(o) => views.forView(Grants).ask(byOffer, 10_000_000, (common :+ ("offer" -> o))*)
