package io.eitheror.casino.attribution.carriers

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.AttributionCodecs

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
    lastError: Option[String]
)

object DeliveryRow:
  given Serializer[DeliveryRow] = AttributionCodecs.serializer[DeliveryRow]("delivery-row")

final class DeliveriesView extends View[DeliveryState, DeliveryRow]:
  def onChange(s: DeliveryState): Effect =
    effects.updateRow(DeliveryRow(updateContext.subject, s.subscription, s.network, s.fact.fold("")(_.factId),
      s.fact.fold("")(_.kind), s.status, s.attempts, s.lastStatus, s.lastError))

object Deliveries extends View.Companion[DeliveriesView, DeliveryState, DeliveryRow](
      componentId = ComponentId("deliveries"),
      source = ChangeSource.stateOf(Delivery),
      rowSerializer = DeliveryRow.given_Serializer_DeliveryRow
    ):
  /** A network's undelivered facts, oldest id first. */
  val undelivered = query("undelivered")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'network' = :network AND payload::jsonb->>'status' = 'undelivered' ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new DeliveriesView
