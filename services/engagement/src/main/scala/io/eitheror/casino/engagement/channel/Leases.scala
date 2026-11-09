package io.eitheror.casino.engagement.channel

import java.time.{Duration, Instant}

/** The channel's pure rules (research R10): which leases a sweep expires, what a poll reads, when a signal is delivered. */
object Leases:

  /** A lease not refreshed for longer than this is a dead instance's: 90 s, so a sweep every 30 s ends it within 120 s. */
  val Expiry: Duration = Duration.ofSeconds(90)

  /** The connections whose lease is older than `after` at `now`, by id. */
  def expired(connections: Map[String, Connection], now: Instant, after: Duration = Expiry): Vector[String] =
    connections.collect { case (id, c) if c.leaseAt.plus(after).isBefore(now) => id }.toVector.sorted

  /** The ring's signals after `cursor`, and whether signals after it have already left the ring. */
  def after(ring: Vector[Signal], seq: Long, cursor: Long): (Vector[Signal], Boolean) =
    val first  = ring.headOption.fold(seq + 1)(_.seq)
    val missed = cursor < first - 1 && cursor < seq
    (ring.filter(_.seq > cursor), missed)

  /** Every connection open at the signal has acked it or closed. */
  def settled(s: Signal): Boolean = s.openAt.subsetOf(s.acked ++ s.closed)

  /** The signals to write `Delivered` for now: from `created`, settled, and not yet delivered. */
  def deliverable(ring: Vector[Signal]): Vector[Signal] =
    ring.filter(s => s.origin == Origin.Created && !s.delivered && settled(s))
