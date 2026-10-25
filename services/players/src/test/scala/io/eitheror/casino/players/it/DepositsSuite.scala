package io.eitheror.casino.players.it

import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*

import scala.concurrent.duration.*

/** The player's deposit start (FR-043, research R15): the checks in order, each bounded, then the cashier. */
class DepositsSuite extends munit.FunSuite:
  override val munitTimeout = 120.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def signedIn(fields: (String, Any)*): (String, String) =
    val (email, id) = kit.activePlayer(fields*)
    (id, kit.signIn(email).str("token"))
  private def deposit(id: String, token: String, body: String = """{"amount":5000,"currency":"EUR","method":"card"}"""): Reply =
    kit.request("POST", kit.playerPath(s"/$id/deposits"), Some(body), kit.asToken(token))
  private def kycAsked(id: String) = kit.kyc.received.filter(_.json.exists(_.text("playerId").contains(id)))
  private def rgAsked(id: String)  = kit.rg.asked("may-deposit").filter(_.text("playerId").contains(id))
  private def created(id: String)  = kit.ingest.creates.filter(_.json.exists(_.text("customerId").contains(s"${kit.slug}:$id")))

  test("a deposit: kyc asked, the sums read, responsible gambling asked with them, the cashier opened under the merchant key with the customer fields") {
    val (id, token) = signedIn("firstName" -> "Ada", "lastName" -> "Byron", "country" -> "GB")
    kit.wallet.sums.put(id, (100L, 700L, 3000L))
    val r = deposit(id, token)
    assertEquals(r.status, 200, r.text)
    assert(r.str("redirectUrl").startsWith("https://cashier.example/"), r.text)
    assert(r.str("paymentId").nonEmpty)
    assertEquals(kycAsked(id).flatMap(_.json).map(_.get("amount").asLong), Vector(5000L))
    val asked = rgAsked(id)
    assertEquals(asked.map(a => (a.get("amount").asLong, a.get("last24h").asLong, a.get("last7d").asLong, a.get("last30d").asLong)),
      Vector((5000L, 100L, 700L, 3000L)))
    val c = created(id)
    assertEquals(c.size, 1)
    assertEquals(c.head.headers.get("merchantkey"), Some("mk_kit_goldvibe"))
    val b = c.head.json.get
    assertEquals((b.get("amountCents").asLong, b.textOr("currency", ""), b.textOr("customerFirstName", ""), b.textOr("customerLastName", ""),
      b.textOr("customerCountry", "")), (5000L, "EUR", "Ada", "Byron", "GB"))
    Seq("customerEmail", "customerPhone", "customerMobile", "customerProvince", "customerCity", "customerAddress", "customerPostCode",
      "customerState", "country", "redirectUrl").foreach(f => assert(b.has(f), f))
    assert(b.textOr("customerEmail", "").contains("@"))
  }

  test("kyc refusing: its code and message, naming it, and nothing after it asked") {
    val (id, token) = signedIn()
    kit.kyc.refusals.put(id, ("kyc_required", "verify your identity first"))
    val r = deposit(id, token)
    assertEquals((r.status, r.str("error"), r.str("message"), r.str("service")), (403, "kyc_required", "verify your identity first", "kyc"))
    assertEquals((rgAsked(id).size, created(id).size), (0, 0))
  }

  test("responsible gambling refusing: its code passed on, naming it, and the cashier not opened") {
    val (id, token) = signedIn()
    kit.rg.deposit = Json.obj("decision" -> "limit_exceeded", "message" -> "daily deposit limit reached")
    val r = deposit(id, token)
    assertEquals((r.status, r.str("service")), (403, "responsible-gambling"), r.text)
    assertEquals(created(id).size, 0)
  }

  test("a silent service: 503 naming it within its bound") {
    val (id, token) = signedIn()
    for (fake, name) <- Seq((kit.kyc, "kyc"), (kit.wallet, "wallet"), (kit.rg, "responsible-gambling"), (kit.ingest, "ingest")) do
      fake.silent = true
      try
        val t0 = System.nanoTime
        val r  = deposit(id, token)
        val ms = (System.nanoTime - t0) / 1_000_000
        assertEquals((r.status, r.str("service")), (503, name), r.text)
        assert(ms < 4000, s"$name took $ms ms")
      finally fake.silent = false
  }

  test("an amount in decimals, at zero or missing: 400; no idempotency key: a repeat opens a second cashier session") {
    val (id, token) = signedIn()
    Seq("""{"amount":50.5,"currency":"EUR"}""", """{"amount":0,"currency":"EUR"}""", """{"currency":"EUR"}""").foreach { b =>
      assertEquals(deposit(id, token, b).status, 400, b)
    }
    assertEquals(deposit(id, token, """{"amount":5000,"currency":"euro"}""").status, 400)
    assertEquals(created(id).size, 0)
    assertEquals(deposit(id, token).status, 200)
    assertEquals(deposit(id, token).status, 200)
    assertEquals(created(id).size, 2)
  }

  test("another player's token: refused; ingest refusing: its status and code, naming it") {
    val (id, token)   = signedIn()
    val (other, _)    = signedIn()
    assert(Set(403, 404).contains(deposit(other, token).status))
    kit.ingest.refusal = Some((422, "invalid_currency"))
    val r = deposit(id, token)
    assertEquals((r.status, r.str("error"), r.str("service")), (422, "invalid_currency", "ingest"), r.text)
  }
