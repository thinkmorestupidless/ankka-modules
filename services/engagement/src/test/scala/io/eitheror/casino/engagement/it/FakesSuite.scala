package io.eitheror.casino.engagement.it

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.Json.*

import java.net.{InetSocketAddress, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*

/** The fakes the integration suites stand on: credentials checked, scripts taken in order, every call kept. */
class FakesSuite extends munit.FunSuite:
  private val crm     = FakeCrm()
  private val players = FakePlayers("spinvibe")
  private val rewards = FakeRewards("spinvibe")
  private val client  = HttpClient.newHttpClient()

  override def beforeEach(context: BeforeEach): Unit =
    crm.reset()
    crm.expect(FakeCrm.SiteId, FakeCrm.TrackKey, FakeCrm.AppKey)
    players.reset()
    rewards.reset()

  override def afterAll(): Unit =
    crm.stop(); players.stop(); rewards.stop()

  private def post(url: String, body: String, headers: (String, String)*): HttpResponse[String] =
    val b = headers.foldLeft(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body)))((b, h) =>
      b.header(h._1, h._2))
    client.send(b.timeout(java.time.Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString())

  private val trackAuth = "Authorization" -> FakeCrm.basic(FakeCrm.SiteId, FakeCrm.TrackKey)
  private val appAuth   = "Authorization" -> s"Bearer ${FakeCrm.AppKey}"

  private def entity(action: String, id: String, attributes: (String, Any)*): String =
    Json.render(Json.obj("type" -> "person", "action" -> action, "identifiers" -> Json.obj("id" -> id),
      "attributes" -> Json.obj(attributes*)))

  private def identify(id: String, attributes: (String, Any)*) =
    post(s"${crm.trackUrl}/api/v2/entity", entity("identify", id, attributes*), trackAuth)

  test("Track: the site id and track key as basic auth, 200 {}; a wrong one 401"):
    assertEquals(identify("p-1").statusCode, 200)
    assertEquals(identify("p-1").body, "{}")
    val wrong = post(s"${crm.trackUrl}/api/v2/entity", entity("identify", "p-1"), "Authorization" -> FakeCrm.basic(FakeCrm.SiteId, "nope"))
    assertEquals(wrong.statusCode, 401)
    assertEquals(post(s"${crm.trackUrl}/api/v2/entity", entity("identify", "p-1")).statusCode, 401)
    assertEquals(crm.calls.map(_.status), Vector(200, 200, 401, 401))
    assertEquals(crm.accepted, 2)

  test("App: the bearer app key, 200 with a delivery id and queued_at; a wrong one 401"):
    val body = Json.render(Json.obj("transactional_message_id" -> "7", "to" -> "a@b.c"))
    val ok   = post(s"${crm.appUrl}/v1/send/email", body, appAuth)
    assertEquals(ok.statusCode, 200)
    val answer = Json.parse(ok.body).get
    assert(answer.text("delivery_id").nonEmpty, ok.body)
    assert(answer.long("queued_at").nonEmpty, ok.body)
    assertEquals(post(s"${crm.appUrl}/v1/send/email", body, "Authorization" -> FakeCrm.basic(FakeCrm.SiteId, FakeCrm.TrackKey)).statusCode, 401)
    assertEquals(crm.sends.flatMap(_.text("to")), Vector("a@b.c", "a@b.c"))

  test("expect sets the credentials both hosts check"):
    crm.expect("site-2", "track-2", "app-2")
    assertEquals(identify("p-1").statusCode, 401)
    assertEquals(post(s"${crm.trackUrl}/api/v2/entity", entity("identify", "p-1"), "Authorization" -> FakeCrm.basic("site-2", "track-2")).statusCode, 200)
    assertEquals(post(s"${crm.appUrl}/v1/send/email", "{}", "Authorization" -> "Bearer app-2").statusCode, 200)

  test("the script is shared by both hosts, taken in order before the credential check"):
    crm.fail(429)
    crm.refuse(400, """{"meta":{"error":"bad id"}}""")
    crm.fail(503, count = 2)
    val answers = Vector(
      identify("p-1"),
      post(s"${crm.appUrl}/v1/send/email", "{}"),
      post(s"${crm.trackUrl}/api/v2/entity", entity("identify", "p-1")),
      post(s"${crm.appUrl}/v1/send/email", "{}", appAuth),
      identify("p-1"))
    assertEquals(answers.map(_.statusCode), Vector(429, 400, 503, 503, 200))
    assertEquals(answers(1).body, """{"meta":{"error":"bad id"}}""")
    assertEquals(crm.calls.map(c => c.host -> c.status), Vector("track" -> 429, "app" -> 400, "track" -> 503, "app" -> 503, "track" -> 200))
    assertEquals(crm.accepted, 1)

  test("a hang answers after hangMillis, so the caller's timeout fires; the next call is answered"):
    crm.hangMillis = 1_500L
    crm.hang()
    val request = HttpRequest.newBuilder(URI.create(s"${crm.trackUrl}/api/v2/entity")).header(trackAuth._1, trackAuth._2)
      .timeout(java.time.Duration.ofMillis(200)).POST(HttpRequest.BodyPublishers.ofString(entity("identify", "p-1"))).build()
    intercept[HttpTimeoutException](client.send(request, HttpResponse.BodyHandlers.ofString()))
    assertEquals(crm.calls.map(_.status), Vector(504))
    assertEquals(identify("p-1").statusCode, 200)

  test("a body that is not JSON is 400 and recorded with no parsed body"):
    assertEquals(post(s"${crm.trackUrl}/api/v2/entity", "nope", trackAuth).statusCode, 400)
    assertEquals(crm.calls.map(c => (c.raw, c.body.isEmpty)), Vector(("nope", true)))

  test("records: path, headers and body; identifies, tracks, deletes and attributes by player"):
    identify("p-1", "email" -> "a@b.c", "deposit_count" -> 2L)
    post(s"${crm.trackUrl}/api/v2/entity", entity("event", "p-1"), trackAuth)
    post(s"${crm.trackUrl}/api/v2/entity", entity("delete", "p-2"), trackAuth)
    identify("p-2")
    val first = crm.calls.head
    assertEquals((first.host, first.path), ("track", "/api/v2/entity"))
    assertEquals(first.headers.get("authorization"), Some(trackAuth._2))
    assertEquals(crm.identifies("p-1").size, 1)
    assertEquals(crm.tracks("p-1").size, 1)
    assertEquals(crm.deletes("p-2").size, 1)
    assertEquals(crm.identifies("p-2").size, 1)
    assertEquals(crm.deletes("p-1"), Vector.empty)
    val attrs = crm.attributes(crm.identifies("p-1").head)
    assertEquals(attrs.keySet, Set("email", "deposit_count"))
    assertEquals(attrs("deposit_count").asLong, 2L)

  test("reset clears the calls and the script, and keeps the credentials"):
    crm.expect("site-2", "track-2", "app-2")
    crm.fail(500, count = 3)
    identify("p-1")
    crm.reset()
    assertEquals(crm.calls, Vector.empty)
    assertEquals(post(s"${crm.trackUrl}/api/v2/entity", entity("identify", "p-1"), "Authorization" -> FakeCrm.basic("site-2", "track-2")).statusCode, 200)

  test("players: 200 {ok} by default, an answer per player, every message recorded, other routes 404"):
    val base = s"${players.address}/v1/casino/brands/spinvibe/players"
    assertEquals(post(s"$base/p-1/messages", """{"title":"hi"}""").body, """{"ok":true}""")
    players.answer("p-2", Answer(404, """{"error":"PLAYER_NOT_FOUND"}"""))
    assertEquals(post(s"$base/p-2/messages", "{}").statusCode, 404)
    assertEquals(post(s"$base/p-1/messages", "{}").statusCode, 200)
    assertEquals(post(s"${players.address}/v1/casino/brands/other/players/p-1/messages", "{}").statusCode, 404)
    assertEquals(players.messages.size, 3)
    assertEquals(players.messages("p-1").flatMap(_.json.flatMap(_.text("title"))), Vector("hi"))
    players.reset()
    assertEquals(players.messages, Vector.empty)
    assertEquals(post(s"$base/p-2/messages", "{}").statusCode, 200)

  test("rewards: target and award, 200 {ok} by default, an answer per route or per player and route"):
    val base = s"${rewards.address}/v1/casino/brands/spinvibe/reward-campaigns"
    assertEquals(post(s"$base/p-1/target", "{}").body, """{"ok":true}""")
    rewards.answer("award", Answer(409, """{"error":"CAMPAIGN_INACTIVE"}"""))
    rewards.answer("p-2", "award", Answer(500, "{}"))
    assertEquals(post(s"$base/p-1/award", "{}").statusCode, 409)
    assertEquals(post(s"$base/p-2/award", "{}").statusCode, 500)
    assertEquals(post(s"$base/p-2/target", "{}").statusCode, 200)
    assertEquals(post(s"$base/p-1/other", "{}").statusCode, 404)
    assertEquals(rewards.calls("award").size, 2)
    assertEquals(rewards.calls("target", "p-2").size, 1)
    rewards.down = Some(Answer(503, "{}"))
    assertEquals(post(s"$base/p-1/target", "{}").statusCode, 503)
    rewards.reset()
    assertEquals(post(s"$base/p-1/award", "{}").statusCode, 200)
    assertEquals(rewards.received.size, 1)

  private def sseServer(status: Int, body: String): HttpServer =
    val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    s.createContext("/", (ex: HttpExchange) => {
      val got = Option(ex.getRequestHeaders.getFirst("Authorization")).getOrElse("")
      ex.getResponseHeaders.add("Content-Type", "text/event-stream")
      ex.sendResponseHeaders(if got == "Bearer t" then status else 401, 0)
      val out = ex.getResponseBody
      body.split("(?<=\n\n)").foreach { chunk =>
        out.write(chunk.getBytes(UTF_8)); out.flush(); Thread.sleep(20)
      }
      ex.close()
    })
    s.start()
    s

  test("SseReader: named frames in order, comments and keep-alives ignored, the end observed"):
    val server = sseServer(200,
      ": hello\n\nevent: ready\ndata: {\"subjectId\":\"p-1\"}\n\n\n\nevent: heartbeat\ndata: {\"at\":\"x\"}\n\nevent: closing\ndata: {\"reason\":\"shutting_down\"}\n\n")
    val sse = SseReader(s"http://127.0.0.1:${server.getAddress.getPort}/v1/stream", Map("Authorization" -> "Bearer t"))
    try
      assertEquals(sse.status, 200)
      assertEquals(sse.next("ready"), ("ready", """{"subjectId":"p-1"}"""))
      assertEquals(sse.next("closing")._2, """{"reason":"shutting_down"}""")
      assert(sse.awaitClosed(), "the stream should have ended")
      assert(sse.closed)
      assertEquals(sse.frames.map(_._1), Vector("ready", "heartbeat", "closing"))
      intercept[AssertionError](sse.next("ready", 100.millis))
    finally
      sse.close()
      server.stop(0)

  test("SseReader: a refusal's status and body"):
    val server = sseServer(200, """{"error":"unauthenticated"}""")
    val sse    = SseReader(s"http://127.0.0.1:${server.getAddress.getPort}/v1/stream")
    try
      assertEquals(sse.status, 401)
      assert(sse.awaitClosed())
      assertEquals(sse.body, """{"error":"unauthenticated"}""")
      assertEquals(sse.frames, Vector.empty)
    finally
      sse.close()
      server.stop(0)
