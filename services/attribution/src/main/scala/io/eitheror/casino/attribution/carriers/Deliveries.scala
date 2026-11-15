package io.eitheror.casino.attribution.carriers

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.runtime.SqlFragment
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import io.eitheror.casino.attribution.AttributionCodecs

import java.time.Instant

/** One delivery as the operator lists it (`GET …/attribution-webhooks/undelivered`). */
final case class DeliveryRow(
    delivery: String,
    subscription: String,
    network: String,
    factId: String,
    kind: String,
    status: String,
    attempts: Int,
    lastStatus: Option[Int],
    lastError: Option[String],
    firstAttemptAt: Option[Instant] = None
)

object DeliveryRow:
  given Serializer[DeliveryRow] = AttributionCodecs.serializer[DeliveryRow]("delivery-row")

final class DeliveriesView extends View[DeliveryState, DeliveryRow]:
  def onChange(s: DeliveryState): Effect =
    effects.updateRow(DeliveryRow(updateContext.subject, s.subscription, s.network, s.fact.fold("")(_.factId),
      s.fact.fold("")(_.kind), s.status, s.attempts, s.lastStatus, s.lastError, s.firstAttemptAt))

object Deliveries extends View.Companion[DeliveriesView, DeliveryState, DeliveryRow](
      componentId = ComponentId("deliveries"),
      source = ChangeSource.stateOf(Delivery),
      rowSerializer = DeliveryRow.given_Serializer_DeliveryRow
    ):
  // 2: the row carries the delivery's first attempt, the list's order (spec 030 E2).
  override def version: Option[Int] = Some(2)

  private val oldest = "(payload::jsonb->>'firstAttemptAt')::timestamptz ASC NULLS FIRST, row_key"

  /** A network's undelivered facts, oldest first attempt first (a review queue, spec 030 FR-009). */
  val undelivered = query("undelivered")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'network' = :network AND payload::jsonb->>'status' = 'undelivered' ORDER BY $oldest")

  /** The same rows as `undelivered`, as a condition and an order, to count and page them. */
  def undeliveredOf(network: String): SqlFragment =
    jsonText("network") ++ sql" = $network" ++ SqlFragment.raw(" AND ") ++ jsonText("status") ++ sql" = ${DeliveryRules.Undelivered}"

  val Oldest: SqlFragment = SqlFragment.raw(oldest)

  def create(ctx: ViewComponentContext) = new DeliveriesView
