package io.eitheror.casino.engagement.it

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.contract.facts as f
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.channel.{AgentChannel, PlayerOnline}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration as JDuration, Instant}
import java.util.UUID
import scala.concurrent.duration.*

/**
 * The backoffice's agent stream (US7; contracts/routes.md): a ticket bought with the operator's bearer, the stream
 * opened with it and no bearer, a `player_online` frame within 5 s of a login with the profile's email prefix, a `ping`
 * every 15 s, and a ticket that is used, late or unknown refused `invalid_ticket` (the original's
 * `AgentStreamTicketStoreSpec` and `AgentStreamConsumerSpec`, at the route).
 */
class AgentStreamSuite extends munit.FunSuite:
  override val munitTimeout = 180.seconds
  private lazy val kit   = SharedKit.kit
  private lazy val facts = Facts(kit)
  private var open       = Vector.empty[SseReader]

  override def beforeAll(): Unit = kit.reset()

  override def afterEach(context: AfterEach): Unit =
    open.foreach(_.close())
    open = Vector.empty
    kit.reset()

  private def path(rest: String) = s"/v1/casino/backoffice/${kit.slug}$rest"

  private def ticket(): String =
    val r = kit.request("POST", path("/agent-stream/ticket"), None, kit.asOperator())
    assertEquals((r.status, r.json.path("expires_in").asLong), (200, 30L), r.text)
    r.str("ticket")

  /** The stream as `EventSource` opens it: the ticket in the query, no bearer. */
  private def stream(t: String): SseReader =
    val s = SseReader(kit.baseUrl + path(s"/agent-stream?ticket=${URLEncoder.encode(t, UTF_8)}"), Map(kit.bearer("x").head))
    open = open :+ s
    s

  private def refused(s: SseReader): Unit =
    assertEquals(s.status, 403, s.body)
    assert(s.awaitClosed(5.seconds))
    assert(s.body.contains("invalid_ticket"), s.body)

  test("a login reaches an open agent stream within 5 s as player_online with the email prefix; a ping every 15 s") {
    val s = stream(ticket())
    // The connection is opened on the brand's channel before the stream answers, so a login from now reaches it.
    assertEquals(s.status, 200, s.body)
    val p       = facts.player("agent")
    val session = s"s-${UUID.randomUUID().toString.take(8)}"
    val at      = Instant.now()
    val raised  = System.nanoTime
    kit.publish(f.CasinoPlayers, p, f.PlayerLoggedIn(p, session, at))
    val frame = Json.parse(s.next("player_online", 5.seconds)._2).get
    assert((System.nanoTime - raised).nanos < 5.seconds)
    assertEquals(Json.render(frame),
      s"""{"player_id":"$p","player_email_prefix":"$p","session_id":"$session","occurred_at":"$at"}""")
    kit.advance(JDuration.ofSeconds(16))
    assertEquals(Json.render(Json.parse(s.next("ping", 5.seconds)._2).get), "{}")
  }

  test("more than the 16-frame queue waiting drops the connection with no frame (FR-024)") {
    val s = stream(ticket())
    assertEquals(s.status, 200, s.body)
    val brand = kit.components.forEventSourcedEntity(EntityId(kit.slug))
    // Sixty logins between two polls of one second each: some poll finds more than sixteen after its cursor.
    for i <- 1 to 60 do
      brand.call(AgentChannel.signal).invoke(PlayerOnline.of(s"p-$i", Some(s"p-$i@x.test"), s"s-$i", Instant.now())): Unit
    assert(s.awaitClosed(10.seconds), "the stream was not dropped")
    assertEquals(s.frames.count(_._1 == "player_online"), 0)
  }

  test("a ticket is spent by its first use; a second use is refused") {
    val t     = ticket()
    val first = stream(t)
    assertEquals(first.status, 200)
    refused(stream(t))
  }

  test("a ticket presented 30 s after its issue is refused, and so is one never issued or none") {
    val t = ticket()
    kit.advance(JDuration.ofSeconds(31))
    refused(stream(t))
    refused(stream("never-issued"))
    val none = SseReader(kit.baseUrl + path("/agent-stream"), Map(kit.bearer("x").head))
    open = open :+ none
    refused(none)
  }

  test("a ticket is bought only with an operator's bearer for the brand") {
    assertEquals(kit.request("POST", path("/agent-stream/ticket"), None, kit.bearer("x").take(1)).status, 401)
    assertEquals(kit.request("POST", path("/agent-stream/ticket"), None, kit.bearer("staff|op-2|brand_another,brand_admin")).status, 403)
    assertEquals(kit.request("POST", path("/agent-stream/ticket"), None, kit.asPlayer("p-1")).status, 403)
  }
