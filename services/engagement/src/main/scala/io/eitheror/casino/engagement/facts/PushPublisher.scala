package io.eitheror.casino.engagement.facts

import com.thinkmorestupidless.ankka.core.{ComponentId, Metadata, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.facts.{CasinoPush, CasinoPushFact, PresenceChanged, PushDelivered}
import io.eitheror.casino.engagement.EngagementRuntime
import io.eitheror.casino.engagement.channel.{Channel, ChannelEvent}
import io.eitheror.casino.engagement.channel.ChannelEvent.*
import io.eitheror.casino.engagement.timers.Sweeps

import scala.util.control.NonFatal

/**
 * The channel's facts on `casino.push` (contracts/facts.md): `presence_changed` when the player's first connection
 * opens and when the last closes or expires, `push_delivered` when every connection open at a created item's signal
 * has acked it or closed. Nothing for a lease, an ack or a signal; a read or a delete is never delivered (R11).
 */
object PushFacts:
  /** The facts an event makes, each with its `ce-id`, so a redelivered event is the same message. */
  def of(playerId: String, event: ChannelEvent): Vector[(String, CasinoPushFact)] = event match
    case Opened(_, _, 1, at) => Vector(s"presence/$playerId/online/$at" -> PresenceChanged(playerId, online = true, at, at))
    case Closed(_, _, 0, at) => Vector(s"presence/$playerId/offline/$at" -> PresenceChanged(playerId, online = false, at, at))
    case Expired(_, _, 0, at) => Vector(s"presence/$playerId/offline/$at" -> PresenceChanged(playerId, online = false, at, at))
    case Delivered(seq, correlationId, n, at) => Vector(s"push/$playerId/$seq" -> PushDelivered(playerId, correlationId, n, at))
    case _ => Vector.empty

/**
 * The channel's events into facts, and the sweep timer kept while the player has a connection: set on the first
 * open, removed when the count reaches zero (R10).
 */
final class PushPublisher extends Consumer[ChannelEvent, CasinoPushFact]:
  private val log = org.slf4j.LoggerFactory.getLogger("io.eitheror.casino.engagement.facts.PushPublisher")

  def onMessage(event: ChannelEvent): Effect =
    val player = messageContext.subject
    try
      event match
        case Opened(_, _, 1, _)                       => Sweeps.start(EngagementRuntime.timers(), player)
        case Closed(_, _, 0, _) | Expired(_, _, 0, _) => Sweeps.stop(EngagementRuntime.timers(), player)
        case _                                        => ()
    catch case NonFatal(e) => log.error(s"the sweep timer of a channel could not be changed: ${e.getMessage}")
    PushFacts.of(player, event) match
      case facts if facts.isEmpty => effects.ignore()
      case facts =>
        effects.produceAll(facts.map { (id, f) =>
          effects.message(f).withKey(f.key).withMetadata(Metadata.empty.set("ce-id", id).set(Metadata.CeSubject, f.key))
        })

object PushPublisher extends Consumer.Companion[PushPublisher, ChannelEvent, CasinoPushFact](
      componentId = ComponentId("push-publisher"),
      source = ChangeSource.eventsOf(Channel)
    ):
  def create(ctx: ConsumerContext) = new PushPublisher
  override val outputSerializer: Option[Serializer[CasinoPushFact]] = Some(CasinoPush.serializer)
  override val produceTo: Option[String]                            = Some(CasinoPush.name)
  override val produces: Option[Publication] = Some(Publication(CasinoPush.name, Some(CasinoPush.contract), None))
