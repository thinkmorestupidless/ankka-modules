package io.eitheror.casino.engagement.facts

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.contract.facts.{PresenceChanged, PushDelivered}
import io.eitheror.casino.engagement.channel.*
import io.eitheror.casino.engagement.channel.ChannelCommand.*
import io.eitheror.casino.engagement.channel.ChannelEvent.*

import java.time.Instant

/** contracts/facts.md's `casino.push`, from the channel's events: presence crossing zero, delivery of created items only. */
class PushFactsSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-07T12:00:00Z")

  test("the first open is online since then; a second open, and a close leaving one, make nothing"):
    assertEquals(PushFacts.of("p-1", Opened("c-1", "i-1", 1, at)).map(_._2), Vector(PresenceChanged("p-1", online = true, at, at)))
    assertEquals(PushFacts.of("p-1", Opened("c-2", "i-2", 2, at)), Vector.empty)
    assertEquals(PushFacts.of("p-1", Closed("c-2", "client_closed", 1, at)), Vector.empty)
    assertEquals(PushFacts.of("p-1", Expired("c-2", at, 1, at)), Vector.empty)

  test("the last close, or the last lease expiring, is offline"):
    for e <- Vector(Closed("c-1", "connection_recycled", 0, at), Expired("c-1", at.minusSeconds(91), 0, at)) do
      assertEquals(PushFacts.of("p-1", e).map(_._2), Vector(PresenceChanged("p-1", online = false, at, at)))

  test("Delivered is push_delivered with the bare message id and the count, under one ce-id per signal"):
    val facts = PushFacts.of("p-1", Delivered(7, "m-1", 2, at))
    assertEquals(facts, Vector("push/p-1/7" -> PushDelivered("p-1", "m-1", 2, at)))

  test("no fact for a lease, an ack or a signal"):
    for e <- Vector(Leased("c-1", at), Acked("c-1", 3, at), Signalled(3, Frames.InboxChanged, "m-1", Origin.Created, Set("c-1"), at)) do
      assertEquals(PushFacts.of("p-1", e), Vector.empty, e.toString)

  test("a read or a delete, acked by every connection, is never delivered; a created item is, once"):
    val kit = EventSourcedTestKit.of(Channel, "p-1")
    kit.call(Channel.open)(Open("c-1", "i-1", at)): Unit
    for (m, origin) <- Vector("m-1" -> Origin.Read, "m-2" -> Origin.Deleted, "m-3" -> Origin.Created) do
      kit.call(Channel.signal)(Raise(Frames.InboxChanged, m, origin, at)): Unit
    kit.call(Channel.acked)(Ack("c-1", 3, at)): Unit
    val facts = kit.allEvents.flatMap(PushFacts.of("p-1", _)).map(_._2).collect { case d: PushDelivered => d }
    assertEquals(facts.toVector, Vector(PushDelivered("p-1", "m-3", 1, at)))
