package io.eitheror.casino.engagement.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.engagement.{EngagementRuntime, Json}
import io.eitheror.casino.engagement.api.Stream
import io.eitheror.casino.engagement.channel.{Channel, ChannelState}

import java.time.{Duration as JDuration, Instant}
import java.util.UUID
import scala.concurrent.duration.*

/**
 * `GET /v1/stream` (US6; FR-020 to FR-023; contracts/routes.md): the frames in order and as written, each closing
 * reason, every refusal before the stream opens, and two devices converging on a read. The stream's times are the
 * kit's clock, moved a minute at most at a time and waited on until the stream has ticked.
 */
class StreamSuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit = SharedKit.kit
  private var open     = Vector.empty[SseReader]

  override def beforeAll(): Unit = kit.reset()

  override def afterEach(context: AfterEach): Unit =
    open.foreach(_.close())
    open = Vector.empty
    Stream.resume()
    kit.reset()

  private def player() = s"s-${UUID.randomUUID().toString.take(8)}"
  private def url      = kit.baseUrl + "/v1/stream"

  private def reader(token: String): SseReader =
    val s = SseReader(url, kit.bearer(token).toMap)
    open = open :+ s
    s

  private def stream(token: String): SseReader =
    val s = reader(token)
    assertEquals(s.status, 200, s.body)
    s.next("ready", 10.seconds): Unit
    s

  private def channel(p: String): ChannelState = kit.components.forEventSourcedEntity(EntityId(p)).call(Channel.get).invoke()
  private def data(frame: (String, String)) = Json.parse(frame._2).get

  private def advance(by: JDuration, s: SseReader): Unit =
    var left = by
    while left.compareTo(JDuration.ZERO) > 0 && !s.closed && !s.frames.exists(_._1 == "closing") do
      val step = if left.compareTo(JDuration.ofMinutes(1)) > 0 then JDuration.ofMinutes(1) else left
      val seen = s.frames.size
      kit.advance(step)
      left = left.minus(step)
      kit.eventually("the stream ticked", 10.seconds)(Option.when(s.frames.size > seen || s.closed)(()))

  private def closing(s: SseReader): String =
    val reason = data(s.next("closing", 10.seconds)).path("reason").asText
    assert(s.awaitClosed(5.seconds), "the stream ends after its closing frame")
    reason

  test("ready names the player at the service's time; heartbeats every 15 s; every frame a named event, no id and no comment") {
    val p = player()
    val s = stream(s"player|$p")
    val ready = data(s.frames.head)
    assertEquals((s.frames.head._1, ready.path("subjectId").asText), ("ready", p))
    assert(JDuration.between(Instant.parse(ready.path("serverTime").asText), kit.now()).abs.toSeconds < 5, ready.toString)
    kit.advance(JDuration.ofSeconds(10))
    Thread.sleep(1500)
    assertEquals(s.frames.map(_._1), Vector("ready"), "no heartbeat before 15 s")
    kit.advance(JDuration.ofSeconds(6))
    val beat = data(s.next("heartbeat", 5.seconds))
    assert(beat.has("at"), beat.toString)
    val lines = s.body.split('\n').toVector.filter(_.nonEmpty)
    assert(lines.forall(l => l.startsWith("event:") || l.startsWith("data:")), lines.mkString("\n"))
  }

  test("an item created is one signal on the player's connection, inbox.changed under <slug>:<message id>, and acked") {
    val p = player()
    val s = stream(s"player|$p")
    val m = s"m-${UUID.randomUUID().toString.take(8)}"
    kit.publish(f.CasinoPlayers, p, f.PlayerMessageCreated(p, m, "service", Instant.now()))
    val signal = data(s.next("signal", 5.seconds))
    assertEquals(Json.render(signal), s"""{"kind":"inbox.changed","correlationId":"${kit.slug}:$m","hint":{}}""")
    val state = kit.eventually("acked")(Option(channel(p)).filter(_.connections.values.forall(_.cursor == 1)))
    assertEquals(state.ring.map(_.delivered), Vector(true))
    kit.publish(f.CasinoPlayers, p, f.PlayerMessageCreated(p, m, "service", Instant.now()))
    Thread.sleep(2000)
    assertEquals(s.frames.count(_._1 == "signal"), 1, "a repeat of the fact is not pushed again")
  }

  test("a read on one device reaches both; neither has a delivered fact for it") {
    val p  = player()
    val ss = Vector(stream(s"player|$p"), stream(s"player|$p"))
    val m  = s"m-${UUID.randomUUID().toString.take(8)}"
    kit.publish(f.CasinoPlayers, p, f.PlayerMessageRead(p, m, Instant.now()))
    kit.publish(f.CasinoPlayers, p, f.PlayerMessageDeleted(p, m, Instant.now()))
    for s <- ss do
      assertEquals(Vector(s.next("signal", 5.seconds), s.next("signal", 5.seconds)).map(data(_).path("correlationId").asText),
        Vector.fill(2)(s"${kit.slug}:$m"))
    Thread.sleep(1500)
    assertEquals(kit.facts(f.CasinoPush).collect { case d: f.PushDelivered if d.playerId == p => d }, Vector.empty)
  }

  test("shutting_down when the service stops; the connection is closed on the channel") {
    val p = player()
    val s = stream(s"player|$p")
    Stream.stop()
    assertEquals(closing(s), "shutting_down")
    kit.eventually("closed on the channel")(Option.when(channel(p).count == 0)(()))
    Stream.resume()
    stream(s"player|$p"): Unit
  }

  test("revalidation_failed at the re-check once the token has expired; a token still valid stays open") {
    val p     = player()
    val valid = stream(s"player|$p|${kit.now().plusSeconds(3600).getEpochSecond}")
    val gone  = stream(s"player|$p|${kit.now().plusSeconds(10).getEpochSecond}")
    advance(JDuration.ofSeconds(40), gone)
    assertEquals(closing(gone), "revalidation_failed")
    assert(!valid.closed && !valid.frames.exists(_._1 == "closing"), valid.frames.toString)
  }

  test("connection_recycled past 5 min × (1 + 0–19 %); the age is per connection") {
    assertEquals(Stream.recycle(JDuration.ofMinutes(5), "a"), JDuration.ofMillis(300000L * (100 + math.abs("a".hashCode % 20)) / 100))
    val ages = (1 to 50).map(i => Stream.recycle(JDuration.ofMinutes(5), s"c-$i"))
    assert(ages.forall(a => a.compareTo(JDuration.ofMinutes(5)) >= 0 && a.compareTo(JDuration.ofSeconds(357)) <= 0), ages.toString)
    assert(ages.distinct.size > 1)
    val s = stream(s"player|${player()}")
    advance(JDuration.ofMinutes(7), s)
    assertEquals(closing(s), "connection_recycled")
  }

  test("refused before the stream opens: no token 401, a sign-in naming no player 403 subject_unresolved, another brand's 403") {
    val none = SseReader(url, Map(kit.bearer("x").head))
    open = open :+ none
    assertEquals(none.status, 401, none.body)
    for t <- Vector(s"staff|op-1|brand_${kit.slug},brand_admin", "part|a-part") do
      val r = reader(t)
      assertEquals(r.status, 403, r.body)
      assert(r.awaitClosed(5.seconds))
      assert(r.body.contains(Stream.Unresolved), r.body)
    val other = io.eitheror.casino.contract.access.Routes.PlayerIssuer
    assertEquals(Stream.refusal("another-brand",
      com.thinkmorestupidless.ankka.http.Principal("u", None, None, false, Set.empty,
        Map("iss" -> s"https://auth.example/realms/casino-${kit.slug}", io.eitheror.casino.contract.access.Routes.PlayerIdClaim -> "p-1"),
        Some(other))).map(_.takeWhile(_ != ':')), Some("wrong_brand"))
  }

  test("503 capacity at the instance's maximum; a closed stream frees its place") {
    EngagementRuntime.settings = kit.settings.copy(maxConnections = 1)
    val p     = player()
    val first = stream(s"player|$p")
    val more  = reader(s"player|$p")
    assertEquals(more.status, 503, more.body)
    assert(more.awaitClosed(5.seconds))
    assert(more.body.contains(Stream.Capacity), more.body)
    first.close()
    kit.eventually("the place freed")(Option.when(EngagementRuntime.connections.get == 0)(()))
    stream(s"player|$p"): Unit
  }
