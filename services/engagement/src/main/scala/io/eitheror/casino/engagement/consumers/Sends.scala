package io.eitheror.casino.engagement.consumers

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.contract.facts.{Fact, Topic}
import io.eitheror.casino.engagement.config.Seed
import io.eitheror.casino.engagement.events.{Classes, Event, Events, SendClass}
import io.eitheror.casino.engagement.profile.{Applied, ProfileState}
import io.eitheror.casino.engagement.send.{DataField, Kind, Send, SendRequest}

import java.security.MessageDigest
import java.time.Instant

/**
 * A fact to the sends it claims (research R4; contracts/facts.md): one per event `Events` gives, keyed by the event's
 * key, and, for a fact of the identify-only families that changed the profile, one identify keyed `identify/<fact id>`.
 */
object Sends:
  /**
   * A fact's id: the facts carry none and the consumer's context names no message id, so it is the SHA-256 of the
   * topic and the fact as the contract writes it (research, *Found during implementation*). A redelivery is the same
   * value and so the same id; two facts differ at least in their time.
   */
  def factId[F <: Fact](topic: Topic[F], fact: F): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(topic.name.getBytes("UTF-8"))
    digest.update(topic.serializer.toBytes(fact))
    digest.digest().take(16).map(b => f"${b & 0xff}%02x").mkString

  /** Every family of every topic this service reads has its time as `occurredAt`. */
  def occurredAt(fact: Fact): Instant = fact match
    case p: Product =>
      p.productElementNames.zip(p.productIterator).collectFirst { case ("occurredAt", at: Instant) => at }
        .getOrElse(throw IllegalArgumentException(s"${p.productPrefix} has no occurredAt"))
    case other => throw IllegalArgumentException(s"${other.getClass.getSimpleName} has no occurredAt")

  private val keyOf: Map[String, String] = Seed.events.map((k, e) => e -> k).toMap

  def ofEvent(playerId: String, factId: String, at: Instant, e: Event): SendRequest =
    SendRequest(e.key, playerId, e.name, Some(e.name), Classes.of(e.name).fold(SendClass.Service.wire)(_.wire), SendRequest.Crm,
      factId, at, transactional = keyOf.get(e.name),
      data = e.data.map((k, v) => DataField(k, ProfileState.personal(playerId, v))))

  def identify(playerId: String, factId: String, at: Instant, key: String = ""): SendRequest =
    SendRequest(if key.nonEmpty then key else s"${Classes.Identify}/$factId", playerId, Classes.Identify, None,
      SendClass.Identify.wire, SendRequest.Crm, factId, at, calls = Vector(Kind.Identify))

  /** The sends of `fact`, given what applying it to the profile told (pure). */
  def of(playerId: String, factId: String, fact: Fact, applied: Applied): Vector[SendRequest] =
    val at  = occurredAt(fact)
    val ctx = Events.Context(applied.registeredAt, applied.depositCountBefore, applied.depositCountAfter,
      id => applied.payment.filter(_.paymentId == id))
    val events = Events.of(factId, fact, ctx).map(ofEvent(playerId, factId, at, _))
    if events.isEmpty && applied.identified then Vector(identify(playerId, factId, at)) else events

  /** Each claimed; a send already claimed answers its state and calls nothing. */
  def start(client: ComponentClient, requests: Vector[SendRequest]): Unit =
    requests.foreach(r => client.forWorkflow(EntityId(r.key)).call(Send.start).invoke(r): Unit)
