package io.eitheror.casino.engagement.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.channel.{Channel, ChannelState}
import io.eitheror.casino.engagement.channel.ChannelCommand.Open
import io.eitheror.casino.engagement.timers.Sweeps

import java.time.{Duration as JDuration, Instant}
import java.util.UUID
import scala.concurrent.duration.*

/**
 * SC-004 and FR-023 (research R10): a connection is known only to the player's channel, on the database, so what holds
 * before `restartService()` holds after it; the leases of an instance that died expire at the sweep and take the
 * player offline. The kit is one instance; a dead instance's connection is opened on the channel directly.
 */
class ChannelSuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit = SharedKit.kit
  private var open     = Vector.empty[SseReader]

  override def beforeAll(): Unit = kit.reset()

  override def afterEach(context: AfterEach): Unit =
    open.foreach(_.close())
    open = Vector.empty
    kit.reset()

  private def player() = s"c-${UUID.randomUUID().toString.take(8)}"

  private def stream(p: String): SseReader =
    val s = SseReader(kit.baseUrl + "/v1/stream", kit.bearer(s"player|$p").toMap)
    open = open :+ s
    assertEquals(s.status, 200, s.body)
    s.next("ready", 10.seconds): Unit
    s

  private def channel(p: String): ChannelState = kit.components.forEventSourcedEntity(EntityId(p)).call(Channel.get).invoke()
  private def push(p: String) = kit.facts(f.CasinoPush).filter(_.playerId == p)

  /** Two connections, one created item: one signal each within 2 s and one `push_delivered` of 2; nothing for `none`. */
  private def deliversToBoth(): Unit =
    val p    = player()
    val none = player()
    val ss   = Vector(stream(p), stream(p))
    kit.eventually("two connections")(Option.when(channel(p).count == 2)(()))
    val m      = s"m-${UUID.randomUUID().toString.take(8)}"
    val raised = System.nanoTime
    kit.publish(f.CasinoPlayers, p, f.PlayerMessageCreated(p, m, "service", Instant.now()))
    kit.publish(f.CasinoPlayers, none, f.PlayerMessageCreated(none, m, "service", Instant.now()))
    for s <- ss do assertEquals(Json.parse(s.next("signal", 5.seconds)._2).get.path("correlationId").asText, s"${kit.slug}:$m")
    assert((System.nanoTime - raised).nanos < 2.seconds + 500.millis, s"${(System.nanoTime - raised).nanos.toMillis} ms")
    val delivered = kit.eventually("push_delivered")(push(p).collectFirst { case d: f.PushDelivered => d })
    assertEquals((delivered.correlationId, delivered.connections), (m, 2))
    Thread.sleep(1500)
    assertEquals(ss.map(_.frames.count(_._1 == "signal")), Vector(1, 1))
    assertEquals(push(p).count(_.isInstanceOf[f.PushDelivered]), 1)
    assertEquals((channel(none).ring, push(none)), (Vector.empty, Vector.empty))

  test("two connections each get the item once and one push_delivered counts them; a player with none, nothing") {
    deliversToBoth()
  }

  test("the same after the service restarts: nothing about a connection is held by the instance") {
    kit.restartService()
    deliversToBoth()
  }

  test("presence: online at the first open, once; offline when the last closes; the sweep timer kept only between") {
    val p = player()
    val a = stream(p)
    val b = stream(p)
    kit.eventually("online")(push(p).collectFirst { case c: f.PresenceChanged if c.online => c })
    assert(kit.eventually("the sweep timer")(Option.when(kit.timerExists(Sweeps.timerName(p)))(true)))
    a.close()
    b.close()
    val off = kit.eventually("offline")(push(p).collectFirst { case c: f.PresenceChanged if !c.online => c })
    assertEquals(push(p).collect { case c: f.PresenceChanged => c.online }, Vector(true, false))
    assert(kit.eventually("the sweep timer removed")(Option.when(!kit.timerExists(Sweeps.timerName(p)))(true)), off.toString)
  }

  test("a dead instance's lease expires at the sweep after 90 s and takes the player offline") {
    val p = player()
    kit.components.forEventSourcedEntity(EntityId(p)).call(Channel.open).invoke(Open("dead-1", "instance-gone", kit.now())): Unit
    kit.eventually("the sweep timer")(Option.when(kit.timerExists(Sweeps.timerName(p)))(()))
    kit.advance(JDuration.ofSeconds(60))
    kit.fireTimer(Sweeps.timerName(p))
    Thread.sleep(1500)
    assertEquals(channel(p).count, 1, "a lease of 60 s is not expired")
    kit.advance(JDuration.ofSeconds(31))
    kit.fireTimer(Sweeps.timerName(p))
    kit.eventually("expired")(Option.when(channel(p).count == 0)(()))
    val off = kit.eventually("offline")(push(p).collectFirst { case c: f.PresenceChanged if !c.online => c })
    assert(!off.online)
    kit.eventually("the sweep timer removed")(Option.when(!kit.timerExists(Sweeps.timerName(p)))(()))
  }
