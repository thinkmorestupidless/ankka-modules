package io.eitheror.casino.engagement.channel

import com.github.plokhotnyuk.jsoniter_scala.macros.named
import com.thinkmorestupidless.ankka.core.{ComponentId, DataSubject, Personal, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.engagement.EngagementCodecs
import io.eitheror.casino.engagement.profile.ProfileState

import java.time.{Duration, Instant}

/** One open agent stream: the instance serving it, and its lease. */
final case class AgentConnection(instance: String, openedAt: Instant, leaseAt: Instant)

/**
 * A player came online (`player_logged_in`): the agent frame's fields. The email prefix is personal (research R15),
 * sealed under `player/<id>`; empty when the profile has no email, as the original broadcasts when its lookup fails.
 */
final case class PlayerOnline(playerId: String, emailPrefix: Personal[String], sessionId: String, occurredAt: Instant)

object PlayerOnline:
  def subject(playerId: String): DataSubject = ProfileState.subject(playerId)

  /** The local part of `email`, before the first `@`; the whole string when it has none (the original's `emailLocalPart`). */
  def emailPrefix(email: String): String = email.split("@", 2).headOption.getOrElse(email)

  def of(playerId: String, email: Option[String], sessionId: String, occurredAt: Instant): PlayerOnline =
    PlayerOnline(playerId, Personal(subject(playerId), email.fold("")(emailPrefix)), sessionId, occurredAt)

/** A frame in the ring, numbered, with the connections open when it was raised: only those are sent it. */
final case class AgentFrame(seq: Long, online: PlayerOnline, openAt: Set[String])

/** A brand's agent channel (data-model.md `AgentChannel`): the open connections, the last 32 frames, the last number. */
final case class AgentChannelState(
    connections: Map[String, AgentConnection] = Map.empty,
    ring: Vector[AgentFrame] = Vector.empty,
    seq: Long = 0
)

/** Each event is stored under its wire name. `connections` is the count after it. */
sealed trait AgentChannelEvent

object AgentChannelEvent:
  @named("opened") final case class Opened(connectionId: String, instance: String, connections: Int, at: Instant)
      extends AgentChannelEvent
  @named("leased") final case class Leased(connectionId: String, at: Instant) extends AgentChannelEvent
  @named("signalled") final case class Signalled(seq: Long, online: PlayerOnline, openAt: Set[String])
      extends AgentChannelEvent
  @named("closed") final case class Closed(connectionId: String, reason: String, connections: Int, at: Instant)
      extends AgentChannelEvent
  @named("expired") final case class Expired(connectionId: String, leaseAt: Instant, connections: Int, at: Instant)
      extends AgentChannelEvent

/** The commands' payloads. Every time is the caller's: the entity reads no clock. */
object AgentChannelCommand:
  final case class Open(connectionId: String, instance: String, now: Instant)
  final case class Heartbeat(connectionId: String, now: Instant)
  final case class Poll(connectionId: String, cursor: Long)
  final case class Close(connectionId: String, reason: String, now: Instant)
  final case class Sweep(now: Instant)

/**
 * What a command answers: whether the connection it names is open (for `signal` and `sweep`, whether any is), the
 * count of open connections, and the last frame's number, which `open` answers as the new stream's cursor.
 */
final case class AgentAnswer(open: Boolean, connections: Int, seq: Long)

/** A frame as a poll answers it. */
final case class QueuedFrame(seq: Long, online: PlayerOnline)

/**
 * A poll's answer: the connection's frames after the cursor, at most 16, the oldest dropped beyond that (`dropped`
 * of them). `seq` is the next cursor. `open` false ends the stream: the connection was closed or expired.
 */
final case class AgentPolled(open: Boolean, frames: Vector[QueuedFrame], dropped: Int, seq: Long)

/**
 * The brand's agent stream (event sourced, id = slug; research R10's shape for the backoffice): a stream opens a
 * connection, polls it for the players who came online since its cursor, refreshes its lease and closes it. A frame
 * is kept only while a connection is open, and is sent only to the connections open when it was raised. No
 * delivered fact and no presence fact.
 */
final class AgentChannel(@scala.annotation.unused context: EventSourcedEntityContext)
    extends EventSourcedEntity[AgentChannelState, AgentChannelEvent]:
  import AgentChannelCommand.*
  import AgentChannelEvent as E

  def emptyState: AgentChannelState = AgentChannelState()

  def applyEvent(event: AgentChannelEvent): AgentChannelState = AgentChannel.applied(currentState, event)

  private def answer(s: AgentChannelState, open: Boolean) = AgentAnswer(open, s.connections.size, s.seq)

  def open(c: Open): Effect[AgentAnswer] =
    val s = currentState
    if s.connections.contains(c.connectionId) then effects.reply(answer(s, open = true))
    else
      val expired = AgentChannel.expiries(s, c.now)
      val opened  = E.Opened(c.connectionId, c.instance, s.connections.size - expired.size + 1, c.now)
      effects.persistAll(expired :+ opened).thenReply(answer(_, open = true))

  def heartbeat(c: Heartbeat): Effect[AgentAnswer] =
    if currentState.connections.contains(c.connectionId) then
      effects.persist(E.Leased(c.connectionId, c.now)).thenReply(answer(_, open = true))
    else effects.reply(answer(currentState, open = false))

  /** A repeat of a frame still in the ring writes nothing: the consumer delivers at least once. */
  def signal(o: PlayerOnline): Effect[AgentAnswer] =
    val s = currentState
    val repeat = s.ring.exists(f =>
      f.online.playerId == o.playerId && f.online.sessionId == o.sessionId && f.online.occurredAt == o.occurredAt)
    if s.connections.isEmpty || repeat then effects.reply(answer(s, open = s.connections.nonEmpty))
    else effects.persist(E.Signalled(s.seq + 1, o, s.connections.keySet)).thenReply(answer(_, open = true))

  def close(c: Close): Effect[AgentAnswer] =
    val s = currentState
    if !s.connections.contains(c.connectionId) then effects.reply(answer(s, open = false))
    else effects.persist(E.Closed(c.connectionId, c.reason, s.connections.size - 1, c.now)).thenReply(answer(_, open = false))

  def sweep(c: Sweep): Effect[AgentAnswer] =
    val expired = AgentChannel.expiries(currentState, c.now)
    if expired.isEmpty then effects.reply(answer(currentState, open = currentState.connections.nonEmpty))
    else effects.persistAll(expired).thenReply(n => answer(n, open = n.connections.nonEmpty))

  def poll(c: Poll): ReadOnlyEffect[AgentPolled] =
    val s = currentState
    if !s.connections.contains(c.connectionId) then effects.reply(AgentPolled(open = false, Vector.empty, 0, s.seq))
    else
      val queued = s.ring.filter(f => f.seq > c.cursor && f.openAt.contains(c.connectionId))
      val kept   = queued.takeRight(AgentChannel.Queue)
      effects.reply(AgentPolled(open = true, kept.map(f => QueuedFrame(f.seq, f.online)), queued.size - kept.size,
        math.max(s.seq, c.cursor)))

  def get: ReadOnlyEffect[AgentChannelState] = effects.reply(currentState)

object AgentChannel
    extends EventSourcedEntity.Companion[AgentChannel, AgentChannelState, AgentChannelEvent](
      componentId = ComponentId("agent-channel"),
      stateSerializer = EngagementCodecs.serializer[AgentChannelState]("agent-channel-state"),
      eventSerializer = EngagementCodecs.serializer[AgentChannelEvent]("agent-channel-event")
    ):
  import AgentChannelCommand.*
  import AgentChannelEvent as E

  /** The frames kept for the brand. */
  val RingSize = 32

  /** A connection's queue: beyond it the oldest frames are dropped, as the original's 16-frame queue. */
  val Queue = 16

  /** A lease not refreshed for longer than this is a dead instance's. */
  val LeaseExpiry: Duration = Duration.ofSeconds(90)

  given Serializer[Open]              = EngagementCodecs.serializer[Open]("agent-channel-open")
  given Serializer[Heartbeat]         = EngagementCodecs.serializer[Heartbeat]("agent-channel-heartbeat")
  given Serializer[Poll]              = EngagementCodecs.serializer[Poll]("agent-channel-poll")
  given Serializer[Close]             = EngagementCodecs.serializer[Close]("agent-channel-close")
  given Serializer[Sweep]             = EngagementCodecs.serializer[Sweep]("agent-channel-sweep")
  given Serializer[PlayerOnline]      = EngagementCodecs.serializer[PlayerOnline]("agent-channel-signal")
  given Serializer[AgentAnswer]       = EngagementCodecs.serializer[AgentAnswer]("agent-channel-answer")
  given Serializer[AgentPolled]       = EngagementCodecs.serializer[AgentPolled]("agent-channel-polled")
  given Serializer[AgentChannelState] = EngagementCodecs.serializer[AgentChannelState]("agent-channel-reply")

  def create(context: EventSourcedEntityContext) = new AgentChannel(context)

  val open      = command("open")(_.open)
  val heartbeat = command("heartbeat")(_.heartbeat)
  val signal    = command("signal")(_.signal)
  val close     = command("close")(_.close)
  val sweep     = command("sweep")(_.sweep)
  val poll      = query("poll")(_.poll)
  val get       = query("get")(_.get)

  /** An `Expired` for each lease older than `LeaseExpiry` at `now`, by id, each with the count after it. */
  private[channel] def expiries(s: AgentChannelState, now: Instant): Vector[AgentChannelEvent] =
    val stale = s.connections.collect { case (id, c) if c.leaseAt.plus(LeaseExpiry).isBefore(now) => id -> c.leaseAt }
      .toVector.sortBy(_._1)
    stale.zipWithIndex.map { case ((id, leaseAt), i) => E.Expired(id, leaseAt, s.connections.size - i - 1, now) }

  /** The only place the channel's state changes: pure, as replay needs. */
  def applied(s: AgentChannelState, event: AgentChannelEvent): AgentChannelState = event match
    case E.Opened(id, instance, _, at) => s.copy(connections = s.connections.updated(id, AgentConnection(instance, at, at)))
    case E.Leased(id, at) =>
      s.copy(connections = s.connections.get(id).fold(s.connections)(c => s.connections.updated(id, c.copy(leaseAt = at))))
    case E.Signalled(seq, online, openAt) =>
      s.copy(ring = (s.ring :+ AgentFrame(seq, online, openAt)).takeRight(RingSize), seq = seq)
    case E.Closed(id, _, _, _)  => s.copy(connections = s.connections - id)
    case E.Expired(id, _, _, _) => s.copy(connections = s.connections - id)
