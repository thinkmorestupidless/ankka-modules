package io.eitheror.casino.engagement.channel

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.engagement.channel.ChannelCommand.*
import io.eitheror.casino.engagement.channel.ChannelEvent.*

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** data-model.md's `Channel` command table, row by row, with presence crossing zero, the sweep, the ring and `Delivered` (R10, R11). */
class ChannelSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-07T12:00:00Z")
  private type Kit = EventSourcedTestKit[Channel, ChannelState, ChannelEvent]

  private def fresh: Kit = EventSourcedTestKit.of(Channel, "p-1")
  private def secs(n: Long) = at.plusSeconds(n)
  private def opened(ids: String*): Kit =
    val kit = fresh
    ids.foreach(id => kit.call(Channel.open)(Open(id, "i-1", at)): Unit)
    kit
  private def created(kit: Kit, message: String, now: Instant = at) =
    kit.call(Channel.signal)(Raise(Frames.InboxChanged, message, Origin.Created, now))
  private def delivered(kit: Kit) = kit.allEvents.collect { case d: Delivered => d }

  test("open on 0→1 is Opened with a count of one, setting onlineSince; a second connection counts two; a repeat writes nothing"):
    val kit = fresh
    val r   = kit.call(Channel.open)(Open("c-1", "i-1", at))
    assertEquals(r.events.toVector, Vector(Opened("c-1", "i-1", 1, at)))
    assertEquals(r.replyValue, ChannelAnswer(open = true, connections = 1, seq = 0))
    assertEquals(kit.currentState.onlineSince, Some(at))
    assertEquals(kit.call(Channel.open)(Open("c-2", "i-2", secs(5))).events.toVector, Vector(Opened("c-2", "i-2", 2, secs(5))))
    assertEquals(kit.currentState.onlineSince, Some(at))
    assertEquals(kit.call(Channel.open)(Open("c-1", "i-1", secs(9))).events.toVector, Vector.empty)
    assertEquals(kit.currentState.connections("c-1"), Connection("i-1", at, at, 0))

  test("a connection opens at the channel's last signal"):
    val kit = opened("c-1")
    created(kit, "m-1"): Unit
    kit.call(Channel.open)(Open("c-2", "i-1", secs(1))): Unit
    assertEquals(kit.currentState.connections("c-2").cursor, 1L)

  test("heartbeat is Leased, refreshing the lease; an unknown connection answers not open and writes nothing"):
    val kit = opened("c-1")
    val r   = kit.call(Channel.heartbeat)(Heartbeat("c-1", secs(30)))
    assertEquals(r.events.toVector, Vector(Leased("c-1", secs(30))))
    assertEquals(kit.currentState.connections("c-1").leaseAt, secs(30))
    val unknown = kit.call(Channel.heartbeat)(Heartbeat("c-9", secs(30)))
    assertEquals((unknown.events.toVector, unknown.replyValue.open), (Vector.empty, false))

  test("signal is Signalled with the open set, only while a connection is open; the same message again writes nothing"):
    val none = fresh
    val r0   = created(none, "m-1")
    assertEquals((r0.events.toVector, r0.replyValue), (Vector.empty, ChannelAnswer(open = false, connections = 0, seq = 0)))
    val kit = opened("c-1", "c-2")
    val r   = created(kit, "m-1", secs(1))
    assertEquals(r.events.toVector, Vector(Signalled(1, Frames.InboxChanged, "m-1", Origin.Created, Set("c-1", "c-2"), secs(1))))
    assertEquals(r.replyValue, ChannelAnswer(open = true, connections = 2, seq = 1))
    assertEquals(created(kit, "m-1", secs(2)).events.toVector, Vector.empty)
    val read = kit.call(Channel.signal)(Raise(Frames.InboxChanged, "m-1", Origin.Read, secs(3)))
    assertEquals(read.events.map(_.getClass.getSimpleName).toVector, Vector("Signalled"))

  test("poll answers the signals after the cursor, writing nothing; an unknown connection answers not open"):
    val kit = opened("c-1")
    created(kit, "m-1"): Unit
    created(kit, "m-2"): Unit
    val p = kit.call(Channel.poll)(Poll("c-1", 1))
    assertEquals((p.events.toVector, p.replyValue.open, p.replyValue.missed, p.replyValue.signals.map(_.correlationId)),
      (Vector.empty, true, false, Vector("m-2")))
    assertEquals(kit.call(Channel.poll)(Poll("c-1", 2)).replyValue.signals, Vector.empty)
    assertEquals(kit.call(Channel.poll)(Poll("c-9", 0)).replyValue, Polled(open = false, Vector.empty, missed = false, 2))

  test("the ring holds the last 32; a cursor fallen out of it answers the ring and missed"):
    val kit = opened("c-1")
    (1 to 40).foreach(i => created(kit, s"m-$i"): Unit)
    assertEquals(kit.currentState.ring.map(_.seq), (9L to 40L).toVector)
    val behind = kit.call(Channel.poll)(Poll("c-1", 3)).replyValue
    assertEquals((behind.missed, behind.signals.map(_.seq)), (true, (9L to 40L).toVector))
    val edge = kit.call(Channel.poll)(Poll("c-1", 8)).replyValue
    assertEquals((edge.missed, edge.signals.size), (false, 32))

  test("acked is Acked up to the seq, moving the cursor; at or below the cursor, or for an unknown connection, it writes nothing"):
    val kit = opened("c-1", "c-2")
    created(kit, "m-1"): Unit
    created(kit, "m-2"): Unit
    val r = kit.call(Channel.acked)(Ack("c-1", 2, secs(1)))
    assertEquals(r.events.toVector, Vector(Acked("c-1", 2, secs(1))))
    assertEquals(kit.currentState.connections("c-1").cursor, 2L)
    assertEquals(kit.currentState.ring.map(_.acked), Vector(Set("c-1"), Set("c-1")))
    assertEquals(kit.call(Channel.acked)(Ack("c-1", 1, secs(2))).events.toVector, Vector.empty)
    assertEquals(kit.call(Channel.acked)(Ack("c-9", 2, secs(2))).replyValue.open, false)
    assertEquals(kit.call(Channel.acked)(Ack("c-2", 99, secs(2))).events.head, Acked("c-2", 2, secs(2)))

  test("Delivered once, for two connections, only after both have acked"):
    val kit = opened("c-1", "c-2")
    created(kit, "m-1"): Unit
    assertEquals(kit.call(Channel.acked)(Ack("c-1", 1, secs(1))).events.toVector, Vector(Acked("c-1", 1, secs(1))))
    assertEquals(kit.call(Channel.acked)(Ack("c-2", 1, secs(2))).events.toVector,
      Vector(Acked("c-2", 1, secs(2)), Delivered(1, "m-1", 2, secs(2))))
    created(kit, "m-2"): Unit
    kit.call(Channel.acked)(Ack("c-1", 2, secs(3))): Unit
    kit.call(Channel.acked)(Ack("c-2", 2, secs(4))): Unit
    assertEquals(delivered(kit).map(_.correlationId), Vector("m-1", "m-2"))

  test("Delivered for one connection when one acked and the other closed without acking (FR-022)"):
    val kit = opened("c-1", "c-2")
    created(kit, "m-1"): Unit
    kit.call(Channel.acked)(Ack("c-1", 1, secs(1))): Unit
    assertEquals(delivered(kit), Vector.empty)
    val r = kit.call(Channel.close)(Close("c-2", "connection_recycled", secs(2)))
    assertEquals(r.events.toVector, Vector(Closed("c-2", "connection_recycled", 1, secs(2)), Delivered(1, "m-1", 1, secs(2))))
    assertEquals(kit.call(Channel.close)(Close("c-1", "client_gone", secs(3))).events.toVector,
      Vector(Closed("c-1", "client_gone", 0, secs(3))))

  test("a connection opened after the signal is not waited for"):
    val kit = opened("c-1")
    created(kit, "m-1"): Unit
    kit.call(Channel.open)(Open("c-2", "i-2", secs(1))): Unit
    assertEquals(kit.call(Channel.acked)(Ack("c-1", 1, secs(2))).events.last, Delivered(1, "m-1", 1, secs(2)))

  test("no Delivered for a read or deleted signal"):
    val kit = opened("c-1")
    kit.call(Channel.signal)(Raise(Frames.InboxChanged, "m-1", Origin.Read, at)): Unit
    kit.call(Channel.signal)(Raise(Frames.InboxChanged, "m-1", Origin.Deleted, at)): Unit
    kit.call(Channel.acked)(Ack("c-1", 2, secs(1))): Unit
    assertEquals(delivered(kit), Vector.empty)

  test("close on 1→0 is Closed with a count of zero, clearing onlineSince; an unknown connection writes nothing"):
    val kit = opened("c-1", "c-2")
    assertEquals(kit.call(Channel.close)(Close("c-1", "shutting_down", secs(1))).events.toVector,
      Vector(Closed("c-1", "shutting_down", 1, secs(1))))
    assertEquals(kit.currentState.onlineSince, Some(at))
    val r = kit.call(Channel.close)(Close("c-2", "shutting_down", secs(2)))
    assertEquals((r.events.toVector, r.replyValue), (Vector(Closed("c-2", "shutting_down", 0, secs(2))), ChannelAnswer(false, 0, 0)))
    assertEquals(kit.currentState.onlineSince, None)
    assertEquals(kit.call(Channel.close)(Close("c-2", "shutting_down", secs(3))).events.toVector, Vector.empty)
    assertEquals(kit.call(Channel.open)(Open("c-3", "i-1", secs(4))).events.toVector, Vector(Opened("c-3", "i-1", 1, secs(4))))
    assertEquals(kit.currentState.onlineSince, Some(secs(4)))

  test("sweep expires a lease older than 90 s, on 1→0 with a count of zero; a lease of exactly 90 s stays"):
    val kit = opened("c-1", "c-2")
    kit.call(Channel.heartbeat)(Heartbeat("c-2", secs(30))): Unit
    assertEquals(kit.call(Channel.sweep)(Sweep(secs(90))).events.toVector, Vector.empty)
    val first = kit.call(Channel.sweep)(Sweep(secs(91)))
    assertEquals(first.events.toVector, Vector(Expired("c-1", at, 1, secs(91))))
    assertEquals(first.replyValue, ChannelAnswer(open = true, connections = 1, seq = 0))
    val last = kit.call(Channel.sweep)(Sweep(secs(121)))
    assertEquals((last.events.toVector, last.replyValue.connections), (Vector(Expired("c-2", secs(30), 0, secs(121))), 0))
    assertEquals(kit.currentState.onlineSince, None)

  test("no Delivered when the only connection closes without acking: no frame reached a live connection (FR-022)"):
    val kit = opened("c-1")
    created(kit, "m-1"): Unit
    assertEquals(kit.call(Channel.close)(Close("c-1", "client_gone", secs(1))).events.toVector,
      Vector(Closed("c-1", "client_gone", 0, secs(1))))
    kit.call(Channel.open)(Open("c-2", "i-1", secs(2))): Unit
    kit.call(Channel.acked)(Ack("c-2", 1, secs(3))): Unit
    assertEquals(delivered(kit), Vector.empty)

  test("a sweep expiring every connection at once counts down to zero and delivers only what one acked"):
    val kit = opened("c-1", "c-2")
    created(kit, "m-1"): Unit
    assertEquals(kit.call(Channel.sweep)(Sweep(secs(200))).events.toVector,
      Vector(Expired("c-1", at, 1, secs(200)), Expired("c-2", at, 0, secs(200))))
    val acked = opened("c-1", "c-2")
    created(acked, "m-1"): Unit
    acked.call(Channel.acked)(Ack("c-1", 1, secs(1))): Unit
    assertEquals(acked.call(Channel.sweep)(Sweep(secs(200))).events.toVector,
      Vector(Expired("c-1", at, 1, secs(200)), Expired("c-2", at, 0, secs(200)), Delivered(1, "m-1", 1, secs(200))))

  test("get answers the state"):
    val kit = opened("c-1")
    assertEquals(kit.call(Channel.get).replyValue, kit.currentState)

  test("replay from the journal gives the same state, and events are stored under their wire names"):
    val kit = opened("c-1", "c-2")
    created(kit, "m-1"): Unit
    kit.call(Channel.acked)(Ack("c-1", 1, secs(1))): Unit
    kit.call(Channel.close)(Close("c-2", "client_gone", secs(2))): Unit
    assertEquals(kit.allEvents.foldLeft(ChannelState())(Channel.applied), kit.currentState)
    val json = String(Channel.eventSerializer.toBytes(Expired("c-1", at, 0, at)), UTF_8)
    assert(json.contains("\"type\":\"expired\""), json)
    assertEquals(Channel.snapshotEvery, Some(100))

  test("Leases.expired takes the expiry as a parameter"):
    val conns = Map("a" -> Connection("i", at, at), "b" -> Connection("i", at, secs(20)))
    assertEquals(Leases.expired(conns, secs(31), java.time.Duration.ofSeconds(30)), Vector("a"))
    assertEquals(Leases.expired(conns, secs(91)), Vector("a"))
