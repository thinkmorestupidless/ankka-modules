package io.eitheror.casino.exchange.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.exchange.Json
import io.eitheror.casino.exchange.api.PlayerEndpoint

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.UUID
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The player's reads (T035, R10, FR-005): a book's prices and depth at its sequence, a player's own
 * orders, positions with the shares resting under their sells added, and trades, each saying the
 * sequence seen and whether it is behind; the stream sends the book whenever its sequence moves.
 */
class ReadRoutesItSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private def kit = SharedKit.kit
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def read(p: String, what: String, as: String = "") =
    kit.request("GET", kit.playerPath(s"/players/$p?read=$what"), headers = kit.asPlayer(if as.isEmpty then p else as))

  private def in(m: String, rows: com.fasterxml.jackson.databind.JsonNode) = rows.elements.asScala.filter(_.get("market").asText == m).toVector

  /** ada holds 10 yes (split with bob), rests a sell of 4 of them at 70 and a buy of 2 yes at 50. */
  private def market() =
    val m = s"reads-${UUID.randomUUID().toString.take(8)}"
    assertEquals(kit.open(m, Vector("yes", "no"), "EUR").status, 200)
    Seq("ada", "bob").foreach(kit.wallet.real.put(_, 10000L))
    assertEquals(kit.place("ada", m, s"$m-a1", "buy", "yes", 10, 60).status, 200)
    assertEquals(kit.place("bob", m, s"$m-b1", "buy", "no", 10, 40).status, 200)
    kit.eventually("ada holds 10")(Option.when(kit.wallet.reserved("ada") == 0 && kit.wallet.reserved("bob") == 0)(()))
    assertEquals(kit.place("ada", m, s"$m-a2", "sell", "yes", 4, 70).status, 200)
    assertEquals(kit.place("ada", m, s"$m-a3", "buy", "yes", 2, 50).status, 200)
    m

  test("a book's prices and each outcome's depth, at its sequence, and one outcome's depth alone") {
    val m = market()
    val p = kit.request("GET", kit.playerPath(s"/markets/$m"), headers = kit.asPlayer("cat")).json
    assert(p.get("sequence").asLong > 0)
    val yes = p.get("outcomes").elements.asScala.find(_.get("outcome").asText == "yes").get
    assertEquals((yes.get("lastPrice").asLong, yes.get("bestBid").asLong, yes.get("bestAsk").asLong), (60L, 50L, 70L))
    assertEquals(yes.get("sells").get(0).get("quantity").asLong, 4L)
    val depth = kit.request("GET", kit.playerPath(s"/markets/$m?outcome=yes"), headers = kit.asPlayer("cat")).json
    assertEquals(depth.get("buys").get(0).get("price").asLong, 50L)
  }

  test("a player's own orders, positions with resting sells added, and trades; another player's are forbidden") {
    val m = market()
    val orders = kit.eventually("ada's three orders")(Option(read("ada", "orders").json).filter(o => in(m, o.get("orders")).size == 3))
    assertEquals(in(m, orders.get("markets")).size, 1)
    val positions = kit.eventually("ada's position")(Option(in(m, read("ada", "positions").json.get("positions"))).filter(_.size == 1))
    val yes = positions.head
    assertEquals((yes.get("outcome").asText, yes.get("quantity").asLong, yes.get("restingUnderSells").asLong), ("yes", 10L, 4L))
    val trades = in(m, read("bob", "trades").json.get("trades"))
    assertEquals(trades.map(t => (t.get("outcome").asText, t.get("quantity").asLong)), Vector(("no", 10L)))
    assertEquals(read("bob", "orders", as = "ada").status, 403)
    assertEquals(read("ada", "everything").status, 400)
    kit.eventually("caught up")(Option.when(!in(m, read("ada", "orders").json.get("markets")).head.get("stale").asBoolean)(()))
  }

  test("a view behind the book's sequence says it is stale") {
    assertEquals(PlayerEndpoint.seen("m", 4, 6).get("stale").asBoolean, true)
    assertEquals(PlayerEndpoint.seen("m", 6, 6).get("stale").asBoolean, false)
  }

  test("the stream sends the book first and again when its sequence moves") {
    val m = market()
    val http = HttpClient.newHttpClient()
    val b = HttpRequest.newBuilder(URI.create(kit.baseUrl + kit.playerPath(s"/stream/$m")))
    kit.asPlayer("cat").foreach((k, v) => b.header(k, v))
    val lines = http.send(b.build(), HttpResponse.BodyHandlers.ofLines()).body().iterator().asScala
    def next() =
      val data = lines.find(_.startsWith("data:")).get.stripPrefix("data:").trim
      Json.parse(Json.parse(data).get.asText).get.get("sequence").asLong
    val first = next()
    assertEquals(kit.place("bob", m, s"$m-b2", "sell", "no", 1, 45).status, 200)
    assert(next() > first)
  }
