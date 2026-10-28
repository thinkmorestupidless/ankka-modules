package io.eitheror.casino.attribution.api

import com.thinkmorestupidless.ankka.core.{CommandError, EntityId}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.NoArgHandle
import io.eitheror.casino.attribution.api.Wire.{*, given}
import io.eitheror.casino.attribution.carriers.{Subscriptions as SubscriptionRows, *}
import io.eitheror.casino.attribution.networks.Network
import io.eitheror.casino.contract.access.Routes

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID

/**
 * The operator's webhooks (contracts/routes.md; research R15): subscriptions and the undelivered facts. A
 * subscription's secret goes to the store and is never read back. A delivery id is `<subscription>/<factId>`,
 * so the client sends it percent-encoded as one path segment.
 */
final class WebhooksEndpoint(api: Api) extends HttpEndpoint(s"/v1/casino/backoffice/${api.brand}/attribution-webhooks"):
  val acl: Acl = Acl.DenyAll

  withAcl(Routes.operator(api.brand, api.authenticate())) {
    get("") { () => list() }
    postBody("") { (body: String) => create(body) }
    get("/undelivered") { () => undelivered() }
    post("/undelivered/{delivery}/resend") { (delivery: String) => act(delivery, Delivery.resend) }
    post("/undelivered/{delivery}/discard") { (delivery: String) => act(delivery, Delivery.discard) }
    get("/{id}") { (id: String) => read(id) }
    putBody("/{id}") { (id: String, body: String) => update(id, body) }
    delete("/{id}") { (id: String) => remove(id) }
  }

  private def subscription(id: String) = api.components.forKeyValueEntity(EntityId(id))

  private def answerOf(s: Subscription) = SubscriptionAnswer(s.id, s.network, s.address, s.active, s.createdAt, s.updatedAt)

  private def list(): Respond[Bytes] =
    val rows = query.raw("network").map(_.trim).filter(_.nonEmpty) match
      case Some(n) => api.views.forView(SubscriptionRows).ask(SubscriptionRows.ofNetwork, "network" -> n)
      case None    => api.views.forView(SubscriptionRows).all(1000).filter(_.exists)
    ok(Wire.Subscriptions(rows.map(answerOf)))

  private def create(body: String): Respond[Bytes] =
    Wire.read[SubscriptionBody](body) match
      case Left(_) => invalid("body", "the body is network, address and secret")
      case Right(b) =>
        val network = b.network.map(_.trim).filter(_.nonEmpty)
        val address = b.address.map(_.trim).filter(_.nonEmpty)
        if network.isEmpty then invalid("network", "a subscription names its network")
        else if !address.exists(a => WebhookAddresses.valid(a, api.settings.webhookPlainHttp)) then invalid("address", "a webhook address is https")
        else if !b.secret.exists(_.nonEmpty) then invalid("secret", "a subscription has a secret to sign its posts with")
        else if !api.components.forKeyValueEntity(EntityId(network.get)).call(Network.get).invoke().live then
          invalid("network", s"no network ${network.get}")
        else
          val id = UUID.randomUUID().toString
          api.secrets.put(WebhookSubscription.secretName(id), b.secret.get)
          try
            val made = subscription(id).call(WebhookSubscription.subscribe)
              .invoke(CreateSubscription(id, network.get, address.get, principal.subject, api.now()))
            val s =
              if b.active.contains(false) then
                subscription(id).call(WebhookSubscription.update).invoke(UpdateSubscription(None, Some(false), api.now()))
              else made
            answer(201, answerOf(s))
          catch case e: CommandError => Wire.refused(e)

  private def read(id: String): Respond[Bytes] =
    val s = subscription(id).call(WebhookSubscription.get).invoke()
    if s.exists then ok(answerOf(s)) else refuse(404, Codes.NotFound, s"no subscription $id")

  /** The address or `active`; a new secret replaces the stored one. */
  private def update(id: String, body: String): Respond[Bytes] =
    Wire.read[SubscriptionBody](body) match
      case Left(_) => invalid("body", "the body is address, active or secret")
      case Right(b) if b.address.exists(a => !WebhookAddresses.valid(a.trim, api.settings.webhookPlainHttp)) => invalid("address", "a webhook address is https")
      case Right(b) =>
        try
          val s = subscription(id).call(WebhookSubscription.update).invoke(UpdateSubscription(b.address.map(_.trim), b.active, api.now()))
          b.secret.filter(_.nonEmpty).foreach(api.secrets.put(WebhookSubscription.secretName(id), _))
          ok(answerOf(s))
        catch case e: CommandError => Wire.refused(e)

  private def remove(id: String): Respond[Bytes] =
    try
      subscription(id).call(WebhookSubscription.delete).invoke(api.now()): Unit
      api.secrets.delete(WebhookSubscription.secretName(id))
      noContent
    catch case e: CommandError => Wire.refused(e)

  private def undelivered(): Respond[Bytes] =
    query.raw("network").map(_.trim).filter(_.nonEmpty) match
      case None => invalid("network", "name the network whose undelivered facts to list")
      case Some(n) =>
        val rows = api.views.forView(Deliveries).ask(Deliveries.undelivered, "network" -> n)
        ok(Undelivered(rows.map(r => UndeliveredAnswer(r.delivery, r.subscription, r.network, r.factId, r.kind, r.attempts,
          r.lastStatus, r.lastError))))

  /** Resends or discards an undelivered fact: `409` unless it is undelivered, `404` for no such delivery. */
  private def act(delivery: String, method: NoArgHandle[Delivery, DeliveryState]): Respond[Bytes] =
    val id       = URLDecoder.decode(delivery, UTF_8)
    val workflow = api.components.forWorkflow(EntityId(id))
    if workflow.call(Delivery.status).invoke().fact.isEmpty then refuse(404, Codes.NotFound, s"no delivery $id")
    else
      try
        val s = workflow.call(method).invoke()
        ok(UndeliveredAnswer(id, s.subscription, s.network, s.fact.fold("")(_.factId), s.fact.fold("")(_.kind), s.attempts,
          s.lastStatus, s.lastError))
      catch case e: CommandError => Wire.refused(e)
