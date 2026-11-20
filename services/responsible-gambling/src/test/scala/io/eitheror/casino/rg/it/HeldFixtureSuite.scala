package io.eitheror.casino.rg.it

import com.thinkmorestupidless.ankka.http.{Acl, HttpEndpoint, HttpServer, Respond}
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import io.eitheror.casino.contract.http.Json
import io.eitheror.casino.contract.port.HeldToPlayerRoutes

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Files

/**
 * Two scenarios of features/brands/service-descriptions.feature that need a served route, so run
 * here and not in the contract's `DescriptionFeatures` (spec 031 T046): a test that causes an answer
 * the service description does not allow fails, naming the route and the answer; and describing a
 * route changes nothing it answers. Over a fixture endpoint of its own, never the service's routes:
 * a described route that answers what its description names and, asked to, a status it does not;
 * and the same handler with no description.
 */
class HeldFixtureSuite extends munit.FunSuite:
  import HeldFixtureSuite.*

  private val kept = Files.createTempFile("held-fixture", ".json")
  Files.writeString(kept,
    """{"openapi":"3.1.0","paths":{"/v1/casino/fixture/the-brand/described/{mood}":{"get":{"operationId":"get_fixture","tags":["player"],"responses":{"200":{"description":"ok"}}}}}}""")

  private val held   = HeldToPlayerRoutes(kept, "goldvibe")
  private val server = held.hold(HttpServer.at("127.0.0.1", 0)(_ => Fixture()))
  private val kit    = AnkkaTestKit.start(Seq.empty, Seq(server))
  private val http   = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

  override def afterAll(): Unit =
    kit.stop()
    Files.deleteIfExists(kept): Unit

  private def get(path: String): (Int, Option[String], String) =
    val r = http.send(HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${server.boundPort.get}$path")).GET().build(),
      HttpResponse.BodyHandlers.ofString())
    (r.statusCode, Option(r.headers.firstValue("content-type").orElse(null)).map(_.takeWhile(_ != ';')), r.body)

  test("an answer the description allows leaves the tests held") {
    assertEquals(get("/v1/casino/fixture/goldvibe/described/fine")._1, 200)
    held.assertHeld()
  }

  test("a test that causes an answer the service description does not allow fails, naming the route and the answer") {
    assertEquals(get("/v1/casino/fixture/goldvibe/described/teapot")._1, 418)
    val failure = intercept[AssertionError](held.assertHeld())
    assert(failure.getMessage.contains("GET /v1/casino/fixture/goldvibe/described/{mood}"), failure.getMessage)
    assert(failure.getMessage.contains("418"), failure.getMessage)
    held.assertHeld() // cleared: the next test starts held
  }

  test("an answer of a route the kept description does not hold is not this hold's") {
    assertEquals(get("/v1/casino/fixture/goldvibe/other/teapot")._1, 418)
    held.assertHeld()
  }

  test("describing a route changes nothing the route answers") {
    for mood <- Vector("fine", "refused", "teapot") do
      assertEquals(get(s"/v1/casino/fixture/goldvibe/described/$mood"), get(s"/v1/casino/fixture/goldvibe/plain/$mood"), mood)
    held.violations.clear()
  }

object HeldFixtureSuite:
  final case class Fine(mood: String)
  final case class Refusal(error: String)

  private def answer(mood: String): Respond[Json] = mood match
    case "fine"    => Respond(Json("""{"mood":"fine"}""".getBytes("UTF-8")), 200)
    case "refused" => Respond(Json("""{"error":"refused"}""".getBytes("UTF-8")), 404)
    case _         => Respond(Json("""{"error":"teapot"}""".getBytes("UTF-8")), 418)

  /** One handler three times: described and kept, described and not kept, and with no description. */
  final class Fixture extends HttpEndpoint("/v1/casino/fixture/goldvibe"):
    val acl: Acl = Acl.AllowAll

    described(_.summary("The fixture's mood").answers[Fine](200).refuses[Refusal](404, "refused").tag("player")) {
      get("/described/{mood}") { (mood: String) => answer(mood) }
    }
    described(_.summary("A described route the kept description does not hold").answers[Fine](200).refuses[Refusal](404, "refused")) {
      get("/other/{mood}") { (mood: String) => answer(mood) }
    }
    get("/plain/{mood}") { (mood: String) => answer(mood) }
