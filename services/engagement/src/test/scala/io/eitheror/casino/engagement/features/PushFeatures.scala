package io.eitheror.casino.engagement.features

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.api.Stream
import io.eitheror.casino.engagement.channel.{Channel, ChannelState}
import io.eitheror.casino.engagement.it.SseReader

import java.time.{Duration as JDuration, Instant}
import java.util.UUID
import scala.concurrent.duration.*

/**
 * features/engagement/push.feature (US6; FR-020 to FR-023): `GET /v1/stream` read as the page reads it, the message
 * facts published on `casino.players` as `players` publishes them. The kit runs one instance, so "two instances" are
 * two connections of the one: the channel is the only place a connection is known (research R10), and the kind run
 * proves the rest. The stream's times are the kit's clock, which a step moves, so the 15 s, 30 s and 5 min waits are
 * reached at once; the stream ticks every second of real time, which is how long a step waits for a frame.
 */
class PushFeatures extends EngagementFeatures("push.feature"):
  private var streams   = Vector.empty[SseReader]
  private var refused   = Vector.empty[SseReader]
  private var token     = ""
  private var messageId = ""
  private var checkAt   = 30L

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    streams = Vector.empty
    refused = Vector.empty
    token = s"player|$player"
    messageId = s"m-${UUID.randomUUID().toString.take(8)}"

  override def afterEach(context: AfterEach): Unit =
    (streams ++ refused).foreach(_.close())
    Stream.resume()
    kit.clockOffset = JDuration.ZERO
    super.afterEach(context)

  private def open(as: String = token): SseReader =
    val s = SseReader(kit.baseUrl + "/v1/stream", kit.bearer(as).toMap)
    assertEquals(s.status, 200, s.body)
    s.next("ready", 10.seconds): Unit
    s

  private def channel: ChannelState = kit.components.forEventSourcedEntity(EntityId(player)).call(Channel.get).invoke()

  private def connected(n: Int): Unit = eventually(s"$n connections")(Option.when(channel.count == n)(()))

  private def data(frame: (String, String)) = Json.parse(frame._2).get

  private def signals(s: SseReader) = s.frames.filter(_._1 == "signal").map(data)

  private def mine = kit.facts(f.CasinoPush).filter(_.playerId == player)

  /** The kit's clock moved by `by` in steps of at most a minute, each waited on until the stream has ticked past it. */
  private def advance(by: JDuration, s: SseReader): Unit =
    var left = by
    while !left.isNegative && !left.isZero && !s.closed && !s.frames.exists(_._1 == "closing") do
      val step = if left.compareTo(JDuration.ofMinutes(1)) > 0 then JDuration.ofMinutes(1) else left
      val seen = s.frames.size
      kit.advance(step)
      left = left.minus(step)
      eventually("the stream ticked", 10.seconds)(Option.when(s.frames.size > seen || s.closed)(()))

  private def closedWith(s: SseReader, reason: String): Unit =
    assertEquals(data(s.next("closing", 10.seconds)).path("reason").asText, reason)
    assert(s.awaitClosed(5.seconds), "the stream ended after its closing frame")

  Given("a player of brand {string}") { (slug: String) => assertEquals(kit.slug, slug) }

  // The stream opened.

  Given("the player's sign-in carries their id") { () => token = s"player|$player" }

  When("the player opens the stream") { () => streams = Vector(open()) }

  Then("a {string} frame names them") { (name: String) =>
    val ready = streams.head.frames.find(_._1 == name).map(data).get
    assertEquals(ready.path("subjectId").asText, player)
    assert(Instant.parse(ready.path("serverTime").asText).isAfter(Instant.now().minusSeconds(60)), ready.toString)
  }

  Then("a heartbeat arrives every {string} seconds") { (every: String) =>
    val s = streams.head
    assertEquals(s.frames.count(_._1 == "heartbeat"), 0, "no heartbeat before the first interval")
    for n <- 1 to 2 do
      kit.advance(JDuration.ofSeconds(every.toLong + 1))
      s.next("heartbeat", 5.seconds): Unit
      assertEquals(s.frames.count(_._1 == "heartbeat"), n)
  }

  Then("a closing frame with a reason precedes any close the brand makes") { () =>
    Stream.stop()
    closedWith(streams.head, "shutting_down")
    connected(0)
  }

  // Signals.

  Given("push connections for the player on two instances") { () =>
    streams = Vector(open(), open())
    connected(2)
  }

  Given("push connections for the player on two devices") { () =>
    streams = Vector(open(), open())
    connected(2)
  }

  Given("no push connection for the player anywhere") { () => assertEquals(channel.count, 0) }

  When("a {string} fact arrives") { (name: String) =>
    assertEquals(name, "player_message_created")
    publishPlayers(f.PlayerMessageCreated(player, messageId, "marketing", Instant.now()))
  }

  When("a {string} fact arrives from one device") { (name: String) =>
    assertEquals(name, "player_message_read")
    publishPlayers(f.PlayerMessageRead(player, messageId, Instant.now()))
  }

  Then("each connection gets one push of kind {string} with the item's correlation id, within {string} seconds") {
    (kind: String, within: String) =>
      for s <- streams do
        val signal = data(s.next("signal", within.toLong.seconds + 2.seconds))
        assertEquals((signal.path("kind").asText, signal.path("correlationId").asText), (kind, s"${kit.slug}:$messageId"))
      Thread.sleep(1500)
      assertEquals(streams.map(signals(_).size), Vector(1, 1), "one push each")
  }

  Then("one {string} fact reports two connections") { (name: String) =>
    assertEquals(name, "push_delivered")
    val delivered = eventually("push_delivered")(mine.collectFirst { case d: f.PushDelivered => d })
    assertEquals((delivered.correlationId, delivered.connections), (messageId, 2))
    Thread.sleep(1500)
    assertEquals(mine.count(_.isInstanceOf[f.PushDelivered]), 1)
  }

  Then("nothing is pushed") { () =>
    Thread.sleep(2000)
    assertEquals(channel.ring, Vector.empty)
  }

  Then("no fact is produced") { () => assertEquals(mine, Vector.empty) }

  Then("every other connection gets an {string} push") { (kind: String) =>
    for s <- streams do
      val signal = data(s.next("signal", 5.seconds))
      assertEquals((signal.path("kind").asText, signal.path("correlationId").asText), (kind, s"${kit.slug}:$messageId"))
    Thread.sleep(1500)
    assertEquals(mine.count(_.isInstanceOf[f.PushDelivered]), 0, "a read is not delivered")
  }

  // Closes the brand makes.

  Given("a push connection whose sign-in no longer verifies at the {string} second re-check") { (at: String) =>
    checkAt = at.toLong
    token = s"player|$player|${kit.now().plusSeconds(checkAt / 2).getEpochSecond}"
    streams = Vector(open())
  }

  When("the check runs") { () => advance(JDuration.ofSeconds(checkAt * 6 / 5 + 2), streams.head) }

  Given("a push connection open for more than {string} minutes") { (minutes: String) =>
    streams = Vector(open())
    checkAt = minutes.toLong * 60
  }

  // The recycle age is 5 min × (1 + 0–19 %), judged when the lease is next refreshed, every 30 s.
  When("the sweep runs") { () => advance(JDuration.ofSeconds(checkAt * 6 / 5 + 40), streams.head) }

  Then("the connection is closed with {string}") { (reason: String) => closedWith(streams.head, reason) }

  Then("the page opens the stream again") { () =>
    streams = streams :+ open()
    connected(1)
  }

  // Refusals.

  Given("an operator's or a part's sign-in without a player id") { () => () }

  When("it opens the stream") { () =>
    refused = Vector(s"staff|op-1|brand_${kit.slug},brand_admin", s"part|part-${player.drop(2)}")
      .map(t => SseReader(kit.baseUrl + "/v1/stream", kit.bearer(t).toMap))
  }

  Then("it is refused as {string}") { (code: String) =>
    for s <- refused do
      assertEquals(s.status, 403, s.body)
      assert(s.awaitClosed(5.seconds))
      assert(s.body.contains(code), s.body)
  }
