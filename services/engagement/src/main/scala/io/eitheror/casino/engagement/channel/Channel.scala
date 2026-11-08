package io.eitheror.casino.engagement.channel

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.engagement.EngagementCodecs
import io.eitheror.casino.engagement.channel.ChannelCommand.*
import io.eitheror.casino.engagement.channel.ChannelEvent.*

import java.time.Instant

/**
 * A player's push channel (event sourced, id = player; data-model.md `Channel`, research R10). The streams open,
 * lease, poll and ack through it; the message consumers raise signals in it; the sweep timer expires the leases of
 * a dead instance. A signal of `created` is `Delivered` once, when every connection open at it has acked it or
 * closed. A repeat of anything writes nothing, so every caller may retry.
 */
final class Channel extends EventSourcedEntity[ChannelState, ChannelEvent]:
  def emptyState: ChannelState = ChannelState()

  def applyEvent(event: ChannelEvent): ChannelState = Channel.applied(currentState, event)

  private def answer(id: String)(s: ChannelState) = ChannelAnswer(s.connections.contains(id), s.count, s.seq)
  private def any(s: ChannelState)                = ChannelAnswer(s.count > 0, s.count, s.seq)

  /** `events`, then a `Delivered` for each signal they settle. */
  private def write(events: Seq[ChannelEvent], now: Instant)(reply: ChannelState => ChannelAnswer): Effect[ChannelAnswer] =
    effects.persistAll(Channel.settling(currentState, events, now)).thenReply(reply)

  def open(c: Open): Effect[ChannelAnswer] =
    if currentState.connections.contains(c.connectionId) then effects.reply(answer(c.connectionId)(currentState))
    else write(Seq(Opened(c.connectionId, c.instance, currentState.count + 1, c.now)), c.now)(answer(c.connectionId))

  /** An unknown connection answers not open, so its stream ends. */
  def heartbeat(c: Heartbeat): Effect[ChannelAnswer] =
    if !currentState.connections.contains(c.connectionId) then effects.reply(answer(c.connectionId)(currentState))
    else write(Seq(Leased(c.connectionId, c.now)), c.now)(answer(c.connectionId))

  /** Acked up to `seq`; an ack at or below the connection's cursor writes nothing. */
  def acked(c: Ack): Effect[ChannelAnswer] =
    currentState.connections.get(c.connectionId) match
      case None => effects.reply(answer(c.connectionId)(currentState))
      case Some(conn) =>
        val upTo = math.min(c.seq, currentState.seq)
        if upTo <= conn.cursor then effects.reply(answer(c.connectionId)(currentState))
        else write(Seq(Acked(c.connectionId, upTo, c.now)), c.now)(answer(c.connectionId))

  /** Appended only while a connection is open; the same message's signal again writes nothing. */
  def signal(c: Raise): Effect[ChannelAnswer] =
    val s = currentState
    if s.count == 0 || s.ring.exists(r => r.correlationId == c.correlationId && r.origin == c.origin) then effects.reply(any(s))
    else write(Seq(Signalled(s.seq + 1, c.kind, c.correlationId, c.origin, s.connections.keySet, c.now)), c.now)(any)

  def close(c: Close): Effect[ChannelAnswer] =
    if !currentState.connections.contains(c.connectionId) then effects.reply(answer(c.connectionId)(currentState))
    else write(Seq(Closed(c.connectionId, c.reason, currentState.count - 1, c.now)), c.now)(answer(c.connectionId))

  /** An `Expired` for each lease older than 90 s (FR-023); the answer's count is what the timer is kept for. */
  def sweep(c: Sweep): Effect[ChannelAnswer] =
    val s       = currentState
    val expired = Leases.expired(s.connections, c.now)
    if expired.isEmpty then effects.reply(any(s))
    else
      val events = expired.zipWithIndex.map((id, i) => Expired(id, s.connections(id).leaseAt, s.count - i - 1, c.now))
      write(events, c.now)(any)

  def poll(c: Poll): ReadOnlyEffect[Polled] =
    val s = currentState
    if !s.connections.contains(c.connectionId) then effects.reply(Polled(open = false, Vector.empty, missed = false, s.seq))
    else
      val (signals, missed) = Leases.after(s.ring, s.seq, c.cursor)
      effects.reply(Polled(open = true, signals, missed, s.seq))

  def get: ReadOnlyEffect[ChannelState] = effects.reply(currentState)

object Channel
    extends EventSourcedEntity.Companion[Channel, ChannelState, ChannelEvent](
      componentId = ComponentId("channel"),
      stateSerializer = EngagementCodecs.serializer[ChannelState]("channel-state"),
      eventSerializer = EngagementCodecs.serializer[ChannelEvent]("channel-event")
    ):
  override def snapshotEvery: Option[Int] = Some(100)

  given Serializer[ChannelState]  = EngagementCodecs.serializer[ChannelState]("channel-reply")
  given Serializer[ChannelAnswer] = EngagementCodecs.serializer[ChannelAnswer]("channel-answer")
  given Serializer[Polled]        = EngagementCodecs.serializer[Polled]("channel-polled")
  given Serializer[Open]          = EngagementCodecs.serializer[Open]("channel-open")
  given Serializer[Heartbeat]     = EngagementCodecs.serializer[Heartbeat]("channel-heartbeat")
  given Serializer[Poll]          = EngagementCodecs.serializer[Poll]("channel-poll")
  given Serializer[Ack]           = EngagementCodecs.serializer[Ack]("channel-ack")
  given Serializer[Raise]         = EngagementCodecs.serializer[Raise]("channel-signal")
  given Serializer[Close]         = EngagementCodecs.serializer[Close]("channel-close")
  given Serializer[Sweep]         = EngagementCodecs.serializer[Sweep]("channel-sweep")

  def create(context: EventSourcedEntityContext) = new Channel

  val open      = command("open")(_.open)
  val heartbeat = command("heartbeat")(_.heartbeat)
  val acked     = command("acked")(_.acked)
  val signal    = command("signal")(_.signal)
  val close     = command("close")(_.close)
  val sweep     = command("sweep")(_.sweep)
  val poll      = query("poll")(_.poll)
  val get       = query("get")(_.get)

  /** `events`, followed by a `Delivered` for each `created` signal they leave settled. */
  private[channel] def settling(s: ChannelState, events: Seq[ChannelEvent], now: Instant): Seq[ChannelEvent] =
    val after = events.foldLeft(s)(applied)
    events ++ Leases.deliverable(after.ring).map(r => Delivered(r.seq, r.correlationId, r.openAt.size, now))

  private def gone(s: ChannelState, id: String): ChannelState =
    val connections = s.connections - id
    s.copy(
      connections = connections,
      ring = s.ring.map(r => if r.openAt(id) && !r.acked(id) then r.copy(closed = r.closed + id) else r),
      onlineSince = if connections.isEmpty then None else s.onlineSince)

  /** The only place a channel's state changes: pure, as replay needs. */
  def applied(s: ChannelState, event: ChannelEvent): ChannelState = event match
    case Opened(id, instance, _, at) =>
      s.copy(connections = s.connections.updated(id, Connection(instance, at, at, s.seq)), onlineSince = s.onlineSince.orElse(Some(at)))
    case Leased(id, at) => s.copy(connections = s.connections.updatedWith(id)(_.map(_.copy(leaseAt = at))))
    case Acked(id, seq, _) =>
      s.copy(
        connections = s.connections.updatedWith(id)(_.map(_.copy(cursor = seq))),
        ring = s.ring.map(r => if r.seq <= seq && r.openAt(id) then r.copy(acked = r.acked + id) else r))
    case Signalled(seq, kind, correlationId, origin, openAt, at) =>
      s.copy(ring = (s.ring :+ Signal(seq, kind, correlationId, origin, at, openAt)).takeRight(ChannelState.RingSize), seq = seq)
    case Delivered(seq, _, _, _) => s.copy(ring = s.ring.map(r => if r.seq == seq then r.copy(delivered = true) else r))
    case Closed(id, _, _, _)     => gone(s, id)
    case Expired(id, _, _, _)    => gone(s, id)
