package io.eitheror.casino.attribution.carriers

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.attribution.AttributionCodecs
import io.eitheror.casino.attribution.AttributionCodecs.given_Serializer_Instant

import java.time.Instant

/**
 * A network's webhook (data-model.md *WebhookSubscription*), id a UUID. The signing secret is the store's
 * `webhook/<id>/secret`; this holds only its name.
 */
final case class Subscription(
    id: String = "",
    network: String = "",
    address: String = "",
    active: Boolean = false,
    secretName: String = "",
    createdBy: Option[String] = None,
    createdAt: Option[Instant] = None,
    updatedAt: Option[Instant] = None,
    deleted: Option[Instant] = None
):
  def exists: Boolean = network.nonEmpty && deleted.isEmpty

final case class CreateSubscription(id: String, network: String, address: String, by: String, at: Instant)
final case class UpdateSubscription(address: Option[String], active: Option[Boolean], at: Instant)

object WebhookSubscriptionCodecs:
  given Serializer[Subscription]       = AttributionCodecs.serializer[Subscription]("webhook-subscription")
  given Serializer[CreateSubscription] = AttributionCodecs.serializer[CreateSubscription]("create-subscription")
  given Serializer[UpdateSubscription] = AttributionCodecs.serializer[UpdateSubscription]("update-subscription")

import WebhookSubscriptionCodecs.given

object WebhookAddresses:
  /** A webhook goes to an https address with a host; http too where the settings admit it (a local run). */
  def valid(address: String, plainHttp: Boolean = io.eitheror.casino.attribution.Wiring.settings.get().webhookPlainHttp): Boolean =
    scala.util.Try(java.net.URI(address)).toOption.exists(u =>
      (u.getScheme == "https" || (plainHttp && u.getScheme == "http")) && Option(u.getHost).exists(_.nonEmpty))

final class WebhookSubscription(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[Subscription]:
  def emptyState: Subscription = Subscription()

  def create(cmd: CreateSubscription): Effect[Subscription] =
    if currentState.exists then effects.error(s"subscription ${cmd.id} exists", ErrorCode.Conflict)
    else if !WebhookAddresses.valid(cmd.address) then effects.error("address: a webhook address is https", ErrorCode.BadRequest)
    else
      effects.updateState(Subscription(cmd.id, cmd.network, cmd.address, active = true, WebhookSubscription.secretName(cmd.id),
        Some(cmd.by), Some(cmd.at), Some(cmd.at))).thenReplyState

  def update(cmd: UpdateSubscription): Effect[Subscription] =
    if !currentState.exists then effects.error("no such subscription", ErrorCode.NotFound)
    else if cmd.address.exists(a => !WebhookAddresses.valid(a)) then effects.error("address: a webhook address is https", ErrorCode.BadRequest)
    else
      val s = currentState
      effects.updateState(s.copy(address = cmd.address.getOrElse(s.address), active = cmd.active.getOrElse(s.active),
        updatedAt = Some(cmd.at))).thenReplyState

  /** Deleted; the endpoint deletes the secret beside it. */
  def delete(at: Instant): Effect[Subscription] =
    if !currentState.exists then effects.error("no such subscription", ErrorCode.NotFound)
    else effects.updateState(currentState.copy(active = false, deleted = Some(at), updatedAt = Some(at))).thenReplyState

  def get: ReadOnlyEffect[Subscription] = effects.reply(currentState)

object WebhookSubscription extends KeyValueEntity.Companion[WebhookSubscription, Subscription](
      componentId = ComponentId("webhook-subscription"),
      stateSerializer = WebhookSubscriptionCodecs.given_Serializer_Subscription
    ):
  def secretName(id: String): String = s"webhook/$id/secret"

  def create(context: KeyValueEntityContext) = new WebhookSubscription(context)

  val subscribe = command("create")(_.create)
  val update = command("update")(_.update)
  val delete = command("delete")(_.delete)
  val get    = query("get")(_.get)

/** The subscriptions, for the fanout and the operator: `activeFor(network)`. */
final class SubscriptionsView extends View[Subscription, Subscription]:
  def onChange(s: Subscription): Effect = effects.updateRow(s)

object Subscriptions extends View.Companion[SubscriptionsView, Subscription, Subscription](
      componentId = ComponentId("subscriptions"),
      source = ChangeSource.stateOf(WebhookSubscription),
      rowSerializer = WebhookSubscriptionCodecs.given_Serializer_Subscription
    ):
  val activeFor = query("active-for")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'network' = :network AND (payload::jsonb->>'active')::boolean = true AND payload::jsonb->'deleted' IS NULL ORDER BY row_key")

  /** Every subscription of a network, for the operator's listing. */
  val ofNetwork = query("of-network")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'network' = :network AND payload::jsonb->'deleted' IS NULL ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new SubscriptionsView
