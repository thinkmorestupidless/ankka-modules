package io.eitheror.casino.modules

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.testkit.GherkinSuite

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Paths}
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Instant
import java.util.UUID
import javax.net.ssl.{SSLContext, TrustManagerFactory}
import scala.jdk.CollectionConverters.*

/**
 * Spec 023 SC-003 on a local installation: every scenario of ankka-predictions'
 * `features/exchange/orders.feature`, against a brand already applied with `predictions`, through the
 * routes a player and an operator use: `players` registers, verifies and signs in each player, kyc's
 * compliance route verifies them, a deposit funds them in the real wallet, `markets` drafts, approves and opens each book, and the
 * orders go through `exchange` and its real gates (players, kyc, responsible-gambling) and wallet.
 *
 * It registers players and creates markets on the brand, so it runs only on purpose:
 * `CASINO_IT_PREDICTIONS=<slug> sbt "modulesIt/testOnly *PredictionsOnKindSuite"`, with
 * `CASINO_IT_STAFF_TOKEN_FILE` naming a file that holds a staff token of the eitheror realm with the
 * brand's role, `markets` and `brand_compliance` (kyc's status route), read again on every call so it can be refreshed while the suite runs,
 * and `CASINO_IT_CA` (default `~/.ankka/local-ca.crt`) the installation's CA. The feature is read
 * from `CASINO_IT_PREDICTIONS_FEATURES` (default the sibling ankka-predictions checkout).
 *
 * A book's limits are fixed when it opens, so a scenario's book is opened at its first order, with
 * the limits its Givens set; a holding is made by a buy matched against a partner's buy of the other
 * outcome, in orders no dearer than the book's maximum order cost.
 */
class PredictionsOnKindSuite extends GherkinSuite(PredictionsOnKindSuite.feature):
  import PredictionsOnKindSuite.*

  override val munitTimeout = scala.concurrent.duration.Duration(30, "min")

  private val brand   = sys.env.getOrElse("CASINO_IT_PREDICTIONS", "")
  private val enabled = brand.nonEmpty
  override def munitTests() = if enabled then super.munitTests() else Seq.empty

  /** A position cannot be made above a book's own maximum, which the library's domain sets directly. */
  override protected def ranOutside: Map[String, String] = Map(
    "a buy order that would take the position above the maximum position is refused naming the limit" ->
      ("the Background's 100 shares of bob exceed this scenario's maximum position of 50, which no order can make; " +
        "ankka-predictions' ExchangeDomainFeatures holds it, and exchange's own OrdersFeatures the refusal")
  )

  // ── the installation ──────────────────────────────────────────────────────────────────────────

  private lazy val http: HttpClient =
    val ca = Paths.get(sys.env.getOrElse("CASINO_IT_CA", s"${sys.props("user.home")}/.ankka/local-ca.crt"))
    val builder = HttpClient.newBuilder()
    if Files.exists(ca) then
      val store = KeyStore.getInstance(KeyStore.getDefaultType)
      store.load(null, null)
      store.setCertificateEntry("installation", CertificateFactory.getInstance("X.509").generateCertificate(Files.newInputStream(ca)))
      val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      tm.init(store)
      val ssl = SSLContext.getInstance("TLS")
      ssl.init(null, tm.getTrustManagers, null)
      builder.sslContext(ssl)
    builder.build()

  private val suffix = sys.env.getOrElse("CASINO_IT_HOST_SUFFIX", "127.0.0.1.sslip.io:8443")
  private def host(service: String) = s"https://$service-$brand.$suffix"

  private def staff: String =
    val file = sys.env.get("CASINO_IT_STAFF_TOKEN_FILE").map(Paths.get(_)).getOrElse(fail("CASINO_IT_STAFF_TOKEN_FILE is not set"))
    Files.readString(file).trim

  private def call(method: String, url: String, body: Option[JsonNode], token: Option[String]): Reply =
    val b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
      .method(method, body.fold(HttpRequest.BodyPublishers.noBody())(j => HttpRequest.BodyPublishers.ofString(json.writeValueAsString(j))))
    token.foreach(t => b.header("Authorization", s"Bearer $t"))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    Reply(r.statusCode, r.body)

  private def ok(r: Reply, what: String): Reply =
    assert(r.status / 100 == 2, s"$what: ${r.status} ${r.text.take(400)}")
    r

  private def eventually[A](what: String, within: Long = 60)(probe: => Option[A]): A =
    val deadline = System.nanoTime() + within * 1000000000L
    @scala.annotation.tailrec def go(): A = probe match
      case Some(a) => a
      case None if System.nanoTime() > deadline => fail(s"not within $within s: $what")
      case None => Thread.sleep(250); go()
    go()

  // ── players: one set for the whole run, registered, verified, signed in and funded ────────────

  private val run = java.lang.Long.toString(System.currentTimeMillis() / 1000, 36)

  private final case class Player(name: String, id: String, email: String, password: String, var token: String)
  private val players = scala.collection.mutable.Map.empty[String, Player]

  private def player(name: String): Player = players.getOrElseUpdate(name, register(name))

  private val attempts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)

  /** A new address on every attempt, so a registration a scenario failed part-way is not repeated. */
  private def register(name: String): Player =
    attempts(name) += 1
    val email    = s"$name-$run-${attempts(name)}@predictions.test"
    val password = s"Sc003-${UUID.randomUUID().toString.take(12)}!"
    val base     = s"${host("players")}/v1/casino/players/$brand"
    val reg = ok(call("POST", s"$base/register", Some(obj("email" -> email, "password" -> password, "currency" -> "EUR")), None), s"registering $name")
    val id  = reg.json.path("playerId").asText()
    val link = ok(call("GET", s"${host("players")}/v1/casino/backoffice/$brand/players/$id/verification-link", None, Some(staff)), s"$name's verification link")
      .json.path("link").asText()
    val token = "[?&]token=([^&]+)".r.findFirstMatchIn(link).map(m => java.net.URLDecoder.decode(m.group(1), "UTF-8"))
      .getOrElse(fail(s"no token in $name's verification link"))
    ok(call("POST", s"$base/verify-email", Some(obj("token" -> token)), None), s"verifying $name")
    val p = Player(name, id, email, password, signIn(base, email, password))
    // The brand's play gate wants KYC approved: the player completes the profile kyc asks for, then a
    // compliance operator verifies them (kyc's status route), once kyc has read the profile from players.
    ok(call("PATCH", s"$base/$id", Some(obj("firstName" -> name.capitalize, "lastName" -> "Sc003", "dateOfBirth" -> "1990-01-01",
      "phone" -> "+27821234567", "addressLine1" -> "1 Long Street", "city" -> "Cape Town", "postalCode" -> "8001", "country" -> Country)),
      Some(p.token)), s"$name's profile")
    eventually(s"$name's kyc verified") {
      val r = call("PUT", s"${host("kyc")}/v1/casino/backoffice/$brand/kyc/$id/status", Some(obj("status" -> "verified")), Some(staff))
      if r.status == 409 && r.json.path("error").asText() == "profile_incomplete" then None else Some(ok(r, s"verifying $name's kyc"))
    }: Unit
    fund(name, id)
    p

  /**
   * A deposit, as `payments/merchant` lands one once a provider confirms it: the wallet's deposits route
   * called with merchant's own service identity, from its pod (`CASINO_IT_FUND_FROM`,
   * `<context>/<namespace>/<deployment>`). A staff adjustment would credit a wallet no deposit has
   * opened, and the wallet refuses a reservation on one (WALLET_NOT_FOUND).
   */
  private def fund(name: String, id: String): Unit =
    val from = sys.env.getOrElse("CASINO_IT_FUND_FROM", "kind-ankka/ankka-payments/merchant").split('/')
    val Secrets = "/var/run/secrets/ankka/service"
    val body = json.writeValueAsString(obj("paymentId" -> s"sc003-$id", "amount" -> Funding.toString, "paymentProvider" -> "sc003"))
    val cmd = Seq("kubectl", "--context", from(0), "-n", from(1), "exec", s"deploy/${from(2)}", "-c", from(2), "--",
      "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "20", "--cert", s"$Secrets/tls.crt", "--key", s"$Secrets/tls.key",
      "--cacert", s"$Secrets/ca.crt", "-H", "Content-Type: application/json", "-X", "POST", "-d", body,
      s"https://wallet.ankka-$brand.svc:9000/v1/casino/brands/$brand/wallets/$id/EUR/deposits")
    val status = scala.sys.process.Process(cmd).!!.trim
    assertEquals(status, "200", s"funding $name")

  private def signIn(base: String, email: String, password: String): String =
    ok(call("POST", s"$base/login", Some(obj("email" -> email, "password" -> password)), None), s"signing in $email").json.path("token").asText()

  /** A player's call, signed in again when the token has lapsed. */
  private def asPlayer(name: String, method: String, path: String, body: Option[JsonNode]): Reply =
    val p = player(name)
    val r = call(method, s"${host("exchange")}/v1/casino/exchange/$brand$path", body, Some(p.token))
    if r.status != 401 then r
    else
      p.token = signIn(s"${host("players")}/v1/casino/players/$brand", p.email, p.password)
      call(method, s"${host("exchange")}/v1/casino/exchange/$brand$path", body, Some(p.token))

  private def reserved(name: String): Long =
    val b = ok(call("GET", s"${host("wallet")}/v1/casino/backoffice/$brand/wallets/${player(name).id}/EUR/balance", None, Some(staff)),
      s"$name's balance").json
    (BigDecimal(b.path("reserved").asText("0")) * 100).toLongExact

  // ── markets: declared by the Givens, opened at the first order ────────────────────────────────

  private final case class Book(id: String, outcomes: Vector[String], state: String, var maxOrderCost: Option[Long] = None,
      var maxPosition: Option[Long] = None, var opened: Boolean = false)
  private val books   = scala.collection.mutable.Map.empty[String, Book]
  private val orderAt = scala.collection.mutable.Map.empty[String, String]
  private var scenario = ""

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    scenario = UUID.randomUUID().toString.take(6)
    books.clear(); orderAt.clear(); wanted = Vector.empty; last = null; first.clear()

  private def operator(path: String, body: JsonNode): Reply =
    call("POST", s"${host("markets")}/v1/casino/backoffice/$brand/markets$path", Some(body), Some(staff))

  private def sequence(b: Book): Option[Long] =
    val r = call("GET", s"${host("exchange")}/v1/casino/exchange/$brand/markets/${b.id}", None, Some(player("ada").token))
    Option.when(r.status == 200)(r.json.path("sequence").asLong(-1)).filter(_ >= 0)

  /** A market command on `markets`, waited on until the book's sequence moves (the driver is a consumer). */
  private def command(b: Book, name: String, body: JsonNode): Unit =
    val before = sequence(b).getOrElse(fail(s"the book of ${b.id} is not open"))
    ok(operator(s"/${b.id}/$name", body), s"$name ${b.id}")
    eventually(s"the book of ${b.id} after $name")(sequence(b).filter(_ > before)): Unit

  private def open(name: String): Book =
    val b = books(name)
    if !b.opened then
      val now   = Instant.now()
      val draft = obj("market" -> b.id, "question" -> s"SC-003 ${b.id}?", "description" -> "orders.feature on kind", "category" -> Category,
        "outcomes" -> b.outcomes, "currency" -> "EUR", "close" -> now.plusSeconds(86400).toString,
        "expectedResolution" -> now.plusSeconds(2 * 86400).toString.take(10), "criteria" -> "None", "authority" -> "None")
      b.maxOrderCost.foreach(v => draft.put("maxOrderCost", v))
      b.maxPosition.foreach(v => draft.put("maxPosition", v))
      ok(operator("/drafts", draft), s"drafting ${b.id}")
      ok(operator(s"/${b.id}/approve", obj()), s"approving ${b.id}")
      // A draft with no opening time opens on approval; one that is still approved is opened early.
      val opening = operator(s"/${b.id}/open", obj())
      if opening.status != 409 || opening.json.path("state").asText() != "open" then ok(opening, s"opening ${b.id}"): Unit
      // A book exchange has not opened answers too, at sequence 0: the open is its first event.
      eventually(s"the book of ${b.id} opens")(sequence(b).filter(_ > 0)): Unit
      b.opened = true
      b.state match
        case "closed"    => command(b, "close", obj("reason" -> "SC-003"))
        case "suspended" => command(b, "suspend", obj("reason" -> "SC-003"))
        case _           => ()
    b

  private def declare(m: String, a: String, c: String, state: String) =
    books(m) = Book(s"$m-$run-$scenario", Vector(a, c), state)

  Given("an open exchange {string} with the outcomes {string} and {string} and a unit of {string}") { (m: String, a: String, c: String, unit: String) =>
    assertEquals(unit, "100", "a book's unit is its currency's: EUR has 100"); declare(m, a, c, "open")
  }
  Given("a closed exchange {string} with the outcomes {string} and {string} and a unit of {string}") { (m: String, a: String, c: String, _: String) =>
    declare(m, a, c, "closed")
  }
  Given("a suspended exchange {string} with the outcomes {string} and {string} and a unit of {string}") { (m: String, a: String, c: String, _: String) =>
    declare(m, a, c, "suspended")
  }
  Given("the maximum order cost of {string} is {string}") { (m: String, v: String) => books(m).maxOrderCost = Some(v.toLong) }
  Given("the maximum position of {string} is {string}") { (m: String, v: String) => books(m).maxPosition = Some(v.toLong) }

  Given("the exchange {string} is suspended") { (m: String) => made(); command(open(m), "suspend", obj("reason" -> "SC-003")) }
  When("the exchange {string} resumes") { (m: String) => command(open(m), "resume", obj()) }

  // ── holdings ──────────────────────────────────────────────────────────────────────────────────

  private var wanted = Vector.empty[(String, Long, String, String)]

  Given("{string} holds {string} shares of {string} in {string}") { (p: String, n: String, o: String, m: String) =>
    wanted = wanted :+ ((p, n.toLong, o, m))
  }

  private def position(p: String, m: String, o: String): Long =
    val r = ok(asPlayer(p, "GET", s"/players/${player(p).id}?read=positions", None), s"$p's positions").json
    r.path("positions").elements().asScala.find(x => x.path("market").asText() == books(m).id && x.path("outcome").asText() == o)
      .map(_.path("quantity").asLong()).getOrElse(0L)

  /** Each wanted holding bought at 50 against the partner's buy of the other outcome at 50, then settled. */
  private def made(): Unit =
    val w = wanted
    wanted = Vector.empty
    w.foreach { (p, n, o, m) =>
      val b     = open(m)
      val other = b.outcomes.find(_ != o).get
      val chunk = math.max(1L, math.min(n, b.maxOrderCost.getOrElse(Long.MaxValue) / 50))
      Iterator.iterate(0L)(_ + chunk).takeWhile(_ < n).zipWithIndex.foreach { (done, i) =>
        val q = math.min(chunk, n - done)
        ok(placeOrder(p, s"hold-$p-$i", "buy", o, q, m, 50), s"$p's holding $i")
        ok(placeOrder("partner", s"hold-partner-$p-$i", "buy", other, q, m, 50), s"the partner's holding $i")
      }
      eventually(s"$p holds $n of $o in ${b.id}")(Option.when(position(p, m, o) == n)(()))
    }

  // ── orders ────────────────────────────────────────────────────────────────────────────────────

  private var last: Reply = null
  private val first = scala.collection.mutable.Map.empty[String, Reply]
  private var sequenceBefore = Option.empty[Long]
  private var reservedBefore = Map.empty[String, Long]

  private def placeOrder(p: String, o: String, side: String, outcome: String, q: Long, m: String, price: Long): Reply =
    orderAt(o) = m
    asPlayer(p, "POST", s"/orders/${books(m).id}", Some(obj("order" -> o, "outcome" -> outcome, "side" -> side, "price" -> price, "quantity" -> q)))

  /** A quantity as written: a whole number as a number, anything else as the text a client sent. */
  private def place(p: String, side: String, o: String, n: String, outcome: String, m: String, price: String): Reply =
    made()
    val b = open(m)
    sequenceBefore = sequence(b)
    reservedBefore = Map(p -> reserved(p))
    val body = obj("order" -> o, "outcome" -> outcome, "side" -> side, "price" -> price.toLong)
    n.toLongOption.fold(body.put("quantity", BigDecimal(n).bigDecimal))(q => body.put("quantity", q)): Unit
    orderAt(o) = m
    last = asPlayer(p, "POST", s"/orders/${b.id}", Some(body))
    if last.status == 200 then first.getOrElseUpdate(o, last): Unit
    last

  for (tense, verb) <- Seq("When" -> "places", "Given" -> "placed"); side <- Seq("buy", "sell") do
    Given(s"{string} $verb a $side order {string} for {string} shares of {string} in {string} at price {string}") {
      (p: String, o: String, n: String, outcome: String, m: String, price: String) =>
        val r = place(p, side, o, n, outcome, m, price)
        if tense == "Given" then ok(r, s"$p's $side order $o"): Unit
    }

  When("{string} cancels the order {string}") { (p: String, o: String) =>
    made()
    reservedBefore = Map(p -> reserved(p))
    last = asPlayer(p, "POST", s"/orders/${books(orderAt(o)).id}/$o", Some(obj("cancel" -> true)))
  }

  private def refused(reason: String, extra: (String, String)*): Unit =
    assert(last.status / 100 == 4, s"accepted: ${last.status} ${last.text}")
    val j = last.json
    val named = j.path("error").asText() == reason || (j.path("error").asText() == "field" && j.path("field").asText() == reason)
    assert(named, s"refused naming ${j.path("error").asText()}, not $reason: ${last.text}")
    extra.foreach((k, v) => assertEquals(j.path(k).asText(), v, last.text))

  Then("the order is refused naming the price")(() => refused("price"))
  Then("the order is refused naming the quantity")(() => refused("quantity"))
  Then("the order is refused naming the outcome")(() => refused("outcome"))
  Then("the order is refused naming the state {string}")((s: String) => refused("state", "state" -> s))
  Then("the order is refused naming the maximum order cost")(() => refused("limit", "limit" -> "maximum order cost"))
  Then("the order is refused naming the maximum position")(() => refused("limit", "limit" -> "maximum position"))
  Then("the order is refused naming the id {string}")((o: String) => refused("id", "id" -> o))
  Then("the cancel is refused naming the owner")(() => refused("owner"))
  Then("the cancel is refused naming the state {string}")((s: String) => refused("state", "state" -> s))

  private def depth(m: String, outcome: String): JsonNode =
    ok(asPlayer("ada", "GET", s"/markets/${books(m).id}?outcome=$outcome", None), s"the depth of $outcome").json

  private def order(p: String, o: String): Option[JsonNode] =
    val r = asPlayer(p, "GET", s"/players/${player(p).id}?read=orders", None)
    Option.when(r.status == 200)(r.json).flatMap(_.path("orders").elements().asScala
      .find(x => x.path("order").asText() == o && x.path("market").asText() == books(orderAt(o)).id))

  /** The player whose orders read shows `o`, once the view has caught up with it. */
  private def ownerOf(o: String): String =
    eventually(s"a player's orders showing $o")(players.keys.find(p => order(p, o).nonEmpty))

  Then("the order {string} rests in the book of {string} in {string} at price {string} for {string} shares") {
    (o: String, outcome: String, m: String, price: String, n: String) =>
      val levels = depth(m, outcome).path("buys").elements().asScala.toVector ++ depth(m, outcome).path("sells").elements().asScala
      assert(levels.exists(l => l.path("price").asLong() == price.toLong && l.path("quantity").asLong() == n.toLong), levels.toString)
      val row = eventually(s"$o in the orders read")(players.keys.toVector.flatMap(p => order(p, o)).headOption)
      assertEquals(row.path("state").asText(), "resting", row.toString)
      assertEquals(row.path("quantity").asLong() - row.path("filled").asLong(), n.toLong, row.toString)
  }

  Then("the cost of the order {string} is {string}") { (o: String, cost: String) =>
    val (p, before) = reservedBefore.head
    eventually(s"$p's reservation for $o")(Option.when(reserved(p) - before == cost.toLong)(())): Unit
  }

  Then("the order {string} is cancelled") { (o: String) =>
    ok(last, s"the cancel of $o")
    val p = ownerOf(o)
    eventually(s"$o cancelled in $p's orders")(order(p, o).filter(_.path("state").asText() == "cancelled")): Unit
  }

  Then("nothing rests in the book of {string} in {string}") { (outcome: String, m: String) =>
    val d = depth(m, outcome)
    assert(d.path("buys").isEmpty && d.path("sells").isEmpty, d.toString)
  }

  Then("{string} is released {string}") { (p: String, amount: String) =>
    val before = reservedBefore(p)
    eventually(s"$p released $amount")(Option.when(before - reserved(p) == amount.toLong)(())): Unit
  }

  Then("the answer is the one the first order {string} received") { (o: String) =>
    val f = first.getOrElse(o, fail(s"no first answer to $o"))
    assertEquals((last.status, last.text), (f.status, f.text))
  }

  Then("no event is produced") { () =>
    val m = books.values.find(_.opened).get
    assertEquals(sequence(m), sequenceBefore)
  }

  Then("one trade is made for {string} shares at price {string} between the orders {string} and {string}") {
    (n: String, price: String, a: String, b: String) =>
      def legs(p: String) =
        val r = asPlayer(p, "GET", s"/players/${player(p).id}?read=trades", None)
        Option.when(r.status == 200)(r.json).toVector.flatMap(_.path("trades").elements().asScala)
          .filter(_.path("market").asText() == books(orderAt(a)).id)
      val found = eventually(s"the trade between $a and $b") {
        val all = players.keys.toVector.flatMap(legs)
        val la  = all.filter(_.path("order").asText() == a)
        val lb  = all.filter(_.path("order").asText() == b)
        Option.when(la.size == 1 && lb.size == 1)((la.head, lb.head))
      }
      assertEquals(found._1.path("trade").asText(), found._2.path("trade").asText())
      Seq(found._1, found._2).foreach { l =>
        assertEquals(l.path("quantity").asLong(), n.toLong)
        assertEquals(l.path("price").asLong(), price.toLong)
      }
  }

object PredictionsOnKindSuite:
  val feature: String = sys.env.getOrElse("CASINO_IT_PREDICTIONS_FEATURES",
    s"${sys.props("user.home")}/Documents/projects/thinkmorestupidless/ankka-predictions/features/exchange/orders.feature")

  /** What each player deposits, in minor units: enough for every scenario's holdings at 50. */
  val Funding  = 500000L
  val Category = sys.env.getOrElse("CASINO_IT_PREDICTIONS_CATEGORY", "politics")
  val Country  = sys.env.getOrElse("CASINO_IT_COUNTRY", "ZA")

  private val json = ObjectMapper()

  final case class Reply(status: Int, text: String):
    lazy val json: JsonNode = PredictionsOnKindSuite.json.readTree(if text.isEmpty then "{}" else text)

  def obj(pairs: (String, Any)*): ObjectNode =
    val o = json.createObjectNode()
    pairs.foreach {
      case (k, v: String)          => o.put(k, v)
      case (k, v: Long)            => o.put(k, v)
      case (k, v: Int)             => o.put(k, v)
      case (k, v: Boolean)         => o.put(k, v)
      case (k, v: Vector[?])       => val a = o.putArray(k); v.foreach(x => a.add(x.toString))
      case (k, v)                  => o.put(k, v.toString)
    }
    o
