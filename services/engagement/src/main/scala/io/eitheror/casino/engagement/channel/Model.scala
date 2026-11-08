package io.eitheror.casino.engagement.channel

import com.github.plokhotnyuk.jsoniter_scala.macros.named

import java.time.Instant

/** One open stream of the player's: the instance serving it, its lease, and the last signal it acked. */
final case class Connection(instance: String, openedAt: Instant, leaseAt: Instant, cursor: Long = 0)

/**
 * A signal in the ring (research R10): `origin` is the message fact it came from (`Origin`), `openAt` the connections
 * open when it was raised, `acked` those that have acked it and `closed` those that closed before acking.
 * `delivered` once its `Delivered` is written.
 */
final case class Signal(
    seq: Long,
    kind: String,
    correlationId: String,
    origin: String,
    at: Instant,
    openAt: Set[String],
    acked: Set[String] = Set.empty,
    closed: Set[String] = Set.empty,
    delivered: Boolean = false
)

/** The message facts a signal is raised for; only `created` is delivered (R11). */
object Origin:
  val Created = "created"
  val Read    = "read"
  val Deleted = "deleted"

/**
 * A player's push channel (data-model.md `Channel`): the open connections, the last 32 signals, the last signal's
 * number, and since when the player has been online. No personal value.
 */
final case class ChannelState(
    connections: Map[String, Connection] = Map.empty,
    ring: Vector[Signal] = Vector.empty,
    seq: Long = 0,
    onlineSince: Option[Instant] = None
):
  def count: Int = connections.size

object ChannelState:
  val RingSize = 32

/**
 * Each event is stored under its wire name. `connections` on `Opened`, `Closed` and `Expired` is the count after it, so
 * `PushPublisher` reads 0→1 and 1→0 from the event alone.
 */
sealed trait ChannelEvent

object ChannelEvent:
  @named("opened") final case class Opened(connectionId: String, instance: String, connections: Int, at: Instant)
      extends ChannelEvent
  @named("leased") final case class Leased(connectionId: String, at: Instant) extends ChannelEvent
  @named("acked") final case class Acked(connectionId: String, seq: Long, at: Instant) extends ChannelEvent
  @named("signalled") final case class Signalled(seq: Long, kind: String, correlationId: String, origin: String,
      openAt: Set[String], at: Instant) extends ChannelEvent
  @named("delivered") final case class Delivered(seq: Long, correlationId: String, connections: Int, at: Instant)
      extends ChannelEvent
  @named("closed") final case class Closed(connectionId: String, reason: String, connections: Int, at: Instant)
      extends ChannelEvent
  @named("expired") final case class Expired(connectionId: String, leaseAt: Instant, connections: Int, at: Instant)
      extends ChannelEvent

/** The commands' payloads (data-model.md's table). Every time is the caller's: the entity reads no clock. */
object ChannelCommand:
  final case class Open(connectionId: String, instance: String, now: Instant)
  final case class Heartbeat(connectionId: String, now: Instant)
  final case class Poll(connectionId: String, cursor: Long)
  final case class Ack(connectionId: String, seq: Long, now: Instant)
  final case class Raise(kind: String, correlationId: String, origin: String, now: Instant)
  final case class Close(connectionId: String, reason: String, now: Instant)
  final case class Sweep(now: Instant)

/**
 * What a command answers: whether the connection it names is open (for `signal` and `sweep`, whether any is), the
 * count of open connections, and the last signal's number. A stream whose connection is not open ends.
 */
final case class ChannelAnswer(open: Boolean, connections: Int, seq: Long)

/**
 * A poll's answer: the ring's signals after the cursor, in order. `missed` when the cursor has fallen out of the ring:
 * the signals before the ring's first are gone, and the page refetches on any signal (R10). `open` false ends the
 * stream: the connection was closed or expired.
 */
final case class Polled(open: Boolean, signals: Vector[Signal], missed: Boolean, seq: Long)
