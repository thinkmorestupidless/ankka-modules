package io.eitheror.casino.engagement.channel

import com.thinkmorestupidless.ankka.core.Personal
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.engagement.{EngagementCodecs, TestKeyring}
import io.eitheror.casino.engagement.channel.AgentChannelCommand.*
import io.eitheror.casino.engagement.channel.AgentChannelEvent as E

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant

/** data-model.md's `AgentChannel`: the original's `AgentStreamConsumerSpec` and `AgentStreamRegistry`, as a poll. */
class AgentChannelSuite extends munit.FunSuite:
  private val at = Instant.parse("2026-11-07T10:00:00Z")

  private def kit(brand: String = "spinvibe") = EventSourcedTestKit.of(AgentChannel, brand)

  private def online(player: String, session: String, email: Option[String] = Some("alice@example.com"), at: Instant = at) =
    PlayerOnline.of(player, email, session, at)

  private def prefix(p: Personal[String]) = p.orElse("<erased>")

  private def polled(k: EventSourcedTestKit[AgentChannel, AgentChannelState, AgentChannelEvent], connection: String,
      cursor: Long = 0) = k.call(AgentChannel.poll)(Poll(connection, cursor)).replyValue

  test("a player online reaches the connections open on the brand, from the cursor the open answered") {
    TestKeyring.around { _ =>
      val k = kit()
      assertEquals(k.call(AgentChannel.open)(Open("c-1", "i-1", at)).replyValue, AgentAnswer(open = true, 1, 0))
      val r = k.call(AgentChannel.signal)(online("player-1", "session-1"))
      assertEquals(r.replyValue, AgentAnswer(open = true, 1, 1))
      assertEquals(r.eventOfType[E.Signalled].openAt, Set("c-1"))
      val p = polled(k, "c-1")
      assertEquals(p.frames.map(f => (f.seq, f.online.playerId, f.online.sessionId, f.online.occurredAt)),
        Vector((1L, "player-1", "session-1", at)))
      assertEquals((p.open, p.dropped, p.seq), (true, 0, 1L))
      assertEquals(polled(k, "c-1", cursor = 1).frames, Vector.empty)
    }
  }

  test("a brand's player online reaches only that brand's connections, never another's") {
    TestKeyring.around { _ =>
      val a = kit("spinvibe")
      val b = kit("goldvibe")
      a.call(AgentChannel.open)(Open("c-a", "i-1", at)): Unit
      b.call(AgentChannel.open)(Open("c-b", "i-1", at)): Unit
      a.call(AgentChannel.signal)(online("player-1", "session-1")): Unit
      assertEquals(polled(a, "c-a").frames.map(_.online.playerId), Vector("player-1"))
      assertEquals(polled(b, "c-b").frames, Vector.empty)
    }
  }

  test("the email prefix is the local part of the player's email, no domain") {
    TestKeyring.around { _ =>
      val k = kit()
      k.call(AgentChannel.open)(Open("c-1", "i-1", at)): Unit
      k.call(AgentChannel.signal)(online("player-2", "session-2", Some("bob.smith@casino-brand.io"))): Unit
      val f = polled(k, "c-1").frames.head.online
      assertEquals(prefix(f.emailPrefix), "bob.smith")
      assertEquals(f.emailPrefix.subject.value, "player/player-2")
    }
  }

  test("a player with no email is announced with an empty prefix, not dropped") {
    TestKeyring.around { _ =>
      val k = kit()
      k.call(AgentChannel.open)(Open("c-1", "i-1", at)): Unit
      k.call(AgentChannel.signal)(online("missing-player", "session-3", None)): Unit
      val f = polled(k, "c-1").frames.map(_.online)
      assertEquals(f.map(o => (prefix(o.emailPrefix), o.sessionId)), Vector(("", "session-3")))
    }
  }

  test("an address with no @ is its own prefix") {
    assertEquals(PlayerOnline.emailPrefix("no-at-sign"), "no-at-sign")
    assertEquals(PlayerOnline.emailPrefix("a@b@c"), "a")
  }

  test("with no connection open a player online is not kept") {
    TestKeyring.around { _ =>
      val k = kit()
      val r = k.call(AgentChannel.signal)(online("player-1", "session-1"))
      assertEquals((r.replyValue, r.persisted), (AgentAnswer(open = false, 0, 0), false))
      k.call(AgentChannel.open)(Open("c-1", "i-1", at.plusSeconds(1))): Unit
      assertEquals(polled(k, "c-1").frames, Vector.empty)
    }
  }

  test("a connection opened after a player online does not see it") {
    TestKeyring.around { _ =>
      val k = kit()
      k.call(AgentChannel.open)(Open("c-1", "i-1", at)): Unit
      k.call(AgentChannel.signal)(online("player-1", "session-1")): Unit
      val cursor = k.call(AgentChannel.open)(Open("c-2", "i-2", at.plusSeconds(1))).replyValue.seq
      assertEquals(polled(k, "c-2", cursor).frames, Vector.empty)
      assertEquals(polled(k, "c-2").frames, Vector.empty)
      assertEquals(polled(k, "c-1").frames.size, 1)
    }
  }

  test("the same session announced twice is announced once: the consumer is at least once") {
    TestKeyring.around { _ =>
      val k = kit()
      k.call(AgentChannel.open)(Open("c-1", "i-1", at)): Unit
      k.call(AgentChannel.signal)(online("player-1", "session-1")): Unit
      assert(!k.call(AgentChannel.signal)(online("player-1", "session-1")).persisted)
      assertEquals(polled(k, "c-1").frames.size, 1)
    }
  }

  test("a connection's queue holds 16 frames: on overflow the oldest are dropped, and counted") {
    TestKeyring.around { _ =>
      val k = kit()
      k.call(AgentChannel.open)(Open("c-1", "i-1", at)): Unit
      for i <- 1 to 20 do k.call(AgentChannel.signal)(online(s"player-$i", s"session-$i", at = at.plusSeconds(i))): Unit
      val p = polled(k, "c-1")
      assertEquals(p.frames.size, AgentChannel.Queue)
      assertEquals(p.frames.map(_.seq), (5L to 20L).toVector)
      assertEquals((p.dropped, p.seq), (4, 20L))
      assertEquals(polled(k, "c-1", cursor = 18).frames.map(_.seq), Vector(19L, 20L))
    }
  }

  test("the ring keeps the last 32 frames") {
    TestKeyring.around { _ =>
      val k = kit()
      k.call(AgentChannel.open)(Open("c-1", "i-1", at)): Unit
      for i <- 1 to 40 do k.call(AgentChannel.signal)(online(s"player-$i", s"session-$i")): Unit
      assertEquals(k.currentState.ring.map(_.seq), (9L to 40L).toVector)
      assertEquals(k.currentState.seq, 40L)
    }
  }

  test("a closed connection's poll answers not open, so its stream ends, and later frames skip it") {
    TestKeyring.around { _ =>
      val k = kit()
      k.call(AgentChannel.open)(Open("c-1", "i-1", at)): Unit
      k.call(AgentChannel.open)(Open("c-2", "i-1", at)): Unit
      assertEquals(k.call(AgentChannel.close)(Close("c-1", "client_gone", at.plusSeconds(1))).replyValue,
        AgentAnswer(open = false, 1, 0))
      assert(!k.call(AgentChannel.close)(Close("c-1", "client_gone", at.plusSeconds(2))).persisted)
      val r = k.call(AgentChannel.signal)(online("player-1", "session-1"))
      assertEquals(r.eventOfType[E.Signalled].openAt, Set("c-2"))
      assert(!polled(k, "c-1").open)
      assertEquals(polled(k, "c-1").frames, Vector.empty)
    }
  }

  test("opening an open connection again writes nothing") {
    TestKeyring.around { _ =>
      val k = kit()
      k.call(AgentChannel.open)(Open("c-1", "i-1", at)): Unit
      val r = k.call(AgentChannel.open)(Open("c-1", "i-1", at.plusSeconds(1)))
      assertEquals((r.replyValue, r.persisted), (AgentAnswer(open = true, 1, 0), false))
    }
  }

  test("a lease not refreshed for over 90 s is expired by a sweep or the next open; a heartbeat keeps it") {
    TestKeyring.around { _ =>
      val k = kit()
      k.call(AgentChannel.open)(Open("c-1", "i-1", at)): Unit
      k.call(AgentChannel.open)(Open("c-2", "i-2", at)): Unit
      assert(k.call(AgentChannel.heartbeat)(Heartbeat("c-1", at.plusSeconds(60))).replyValue.open)
      assert(!k.call(AgentChannel.heartbeat)(Heartbeat("c-9", at.plusSeconds(60))).replyValue.open)
      val swept = k.call(AgentChannel.sweep)(Sweep(at.plusSeconds(91)))
      assertEquals(swept.events.collect { case e: E.Expired => e.connectionId }, Vector("c-2"))
      assertEquals(k.currentState.connections.keySet, Set("c-1"))
      val opened = k.call(AgentChannel.open)(Open("c-3", "i-3", at.plusSeconds(200)))
      assertEquals(opened.events.collect { case e: E.Expired => e.connectionId }, Vector("c-1"))
      assertEquals(k.currentState.connections.keySet, Set("c-3"))
    }
  }

  test("the stored state and events hold no email prefix in the clear") {
    TestKeyring.around { _ =>
      val k = kit()
      k.call(AgentChannel.open)(Open("c-1", "i-1", at)): Unit
      val r      = k.call(AgentChannel.signal)(online("player-1", "session-1", Some("alice.secret@example.com")))
      val state  = new String(EngagementCodecs.serializer[AgentChannelState]("agent-channel-state").toBytes(r.state), UTF_8)
      val events = r.events.map(e => new String(EngagementCodecs.serializer[AgentChannelEvent]("agent-channel-event").toBytes(e), UTF_8))
      for text <- state +: events do
        assert(!text.contains("alice.secret"), text)
        assert(text.contains("\"$personal\""), text)
      assert(events.head.contains("\"type\":\"signalled\""), events.head)
    }
  }

  test("an erased player's frame carries no prefix") {
    TestKeyring.around { keyring =>
      val k = kit()
      k.call(AgentChannel.open)(Open("c-1", "i-1", at)): Unit
      k.call(AgentChannel.signal)(online("player-1", "session-1")): Unit
      val stored = EngagementCodecs.serializer[AgentChannelState]("agent-channel-state")
      val bytes  = stored.toBytes(k.currentState)
      keyring.erase(PlayerOnline.subject("player-1"))
      val back = stored.fromBytes(bytes)
      assert(back.ring.head.online.emailPrefix.isErased)
    }
  }
