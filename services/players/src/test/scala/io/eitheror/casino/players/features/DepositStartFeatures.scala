package io.eitheror.casino.players.features

import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*
import io.eitheror.casino.players.it.{Kit, Reply, SharedKit}

/**
 * features/players/deposit-start.feature (spec 027 T021, spec 008 FR-043 amended): the start's answer
 * carries the cashier session's ticket and terminals, a refusal the figures the deposit gate and
 * responsible gambling answered, and a request id is the cashier session's. The feature's brand is
 * `guyanabet`, so it runs on a kit of its own for that brand (in a JVM of its own, `build.sbt`), with
 * `kyc`, the wallet, `responsible-gambling` and `ingest` as the kit's fakes.
 */
class DepositStartFeatures extends PlayersFeatures("deposit-start.feature"):
  protected def pending = Map.empty

  private var own: Kit = null
  override protected def kit: Kit = own

  override def beforeAll(): Unit =
    own = Kit(Kit.Base.copy(brand = "guyanabet"))
    super.beforeAll()

  override def afterAll(): Unit =
    try super.afterAll()
    finally
      if own != null then own.stop()
      SharedKit.readopt()

  private var playerId = ""
  private var token    = ""
  private var first: Reply = null
  private var reply: Reply = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    playerId = ""; token = ""; first = null; reply = null

  private def start(amount: String, currency: String, requestId: Option[String] = None): Reply =
    val body = Json.obj("amount" -> amount.toLong, "currency" -> currency, "method" -> "card", "requestId" -> requestId)
    kit.json("POST", kit.playerPath(s"/$playerId/deposits"), body, kit.asToken(token))

  private def created = kit.ingest.creates.flatMap(_.json).filter(_.text("customerId").contains(s"${kit.slug}:$playerId"))
  private def opened  = kit.ingest.opened

  private def refused(status: Int, code: String, service: String): Unit =
    assertEquals((reply.status, reply.str("error"), reply.str("service")), (status, code, service), reply.text)
    assert(reply.str("cashierTicket").isEmpty, reply.text)

  Given("a player of brand {string} who is active") { (slug: String) =>
    assertEquals(kit.slug, slug)
    val (email, id) = kit.activePlayer()
    playerId = id
    token = kit.signIn(email).str("token")
    assert(token.nonEmpty, "the player signs in")
  }

  // ── started ────────────────────────────────────────────────────────────────────────────────────

  Given("the deposit gate and responsible gambling allow a deposit of {string} minor units of {string}") { (_: String, _: String) =>
    kit.kyc.refusals.remove(playerId)
    kit.rg.deposit = Json.obj("decision" -> "allowed")
  }
  When("the player starts a deposit of {string} minor units of {string}") { (amount: String, currency: String) =>
    reply = start(amount, currency)
  }
  Then("a cashier session is opened at the shared payments for the player") { () =>
    assertEquals(created.map(b => (b.long("amountCents"), b.text("currency"))), Vector((Some(5000L), Some("EUR"))))
    assertEquals(kit.ingest.merchantKeys, Vector("mk_kit_goldvibe"))
  }
  Then("the player is answered the cashier ticket and the terminals the cashier session offers") { () =>
    assertEquals(reply.status, 200, reply.text)
    val session = opened.head
    assertEquals(reply.str("cashierTicket"), session.textOr("session_token", "?"))
    assertEquals(reply.json.get("terminals"), kit.ingest.terminals)
    assertEquals((reply.str("requestId"), reply.str("paymentId")), (session.textOr("transactionId", "?"), session.textOr("transactionId", "?")))
    assertEquals(reply.str("redirectUrl"), session.textOr("redirectUrl", "?"))
  }

  // ── refused ────────────────────────────────────────────────────────────────────────────────────

  Given("the deposit gate refuses the player") { () =>
    kit.kyc.refusals.put(playerId, ("DEPOSIT_BLOCKED_KYC", "Verify your identity before depositing")): Unit
  }
  Then("the player is refused with the deposit gate's code and words and their KYC status") { () =>
    refused(403, "DEPOSIT_BLOCKED_KYC", "kyc")
    assertEquals((reply.str("message"), reply.str("kycStatus")), ("Verify your identity before depositing", "unverified"))
  }
  Then("no cashier session is opened") { () =>
    assertEquals(created, Vector.empty)
    assertEquals(opened, Vector.empty)
  }

  Given("the player has a deposit limit that {string} minor units would pass") { (amount: String) =>
    val limit = amount.toLong - 1000
    kit.rg.deposit = Json.obj("decision" -> "over_limit", "period" -> "daily", "limit" -> limit, "used" -> 1500L, "remaining" -> (limit - 1500L))
  }
  Then("the player is refused as {string} naming the period, the limit, what was used and what remains") { (code: String) =>
    refused(403, code, "responsible-gambling")
    val b = reply.json
    assertEquals((b.text("period"), b.long("limit"), b.long("used"), b.long("remaining")), (Some("daily"), Some(4000L), Some(1500L), Some(2500L)))
    assert(b.get("limit").isIntegralNumber && b.get("remaining").isIntegralNumber, "the figures stay whole minor units")
    assertEquals(kit.rg.asked("may-deposit").flatMap(_.long("amount")), Vector(5000L))
  }

  private val breakEnds = "2026-12-01T09:00:00Z"
  Given("the player is cooling off") { () =>
    kit.rg.deposit = Json.obj("decision" -> "cooling_off", "endsAt" -> breakEnds)
  }
  Then("the player is refused as {string} naming the end of the break") { (code: String) =>
    refused(403, code, "responsible-gambling")
    assertEquals(reply.str("endsAt"), breakEnds)
  }

  // ── under a request id ─────────────────────────────────────────────────────────────────────────

  Given("the player started a deposit of {string} minor units of {string} under the request id {string}") {
    (amount: String, currency: String, requestId: String) =>
      first = start(amount, currency, Some(requestId))
      assertEquals((first.status, first.str("requestId")), (200, requestId), first.text)
  }
  When("the player starts a deposit of {string} minor units of {string} under the request id {string}") {
    (amount: String, currency: String, requestId: String) =>
      reply = start(amount, currency, Some(requestId))
  }
  Then("the player is answered the first cashier session's cashier ticket") { () =>
    assertEquals(reply.status, 200, reply.text)
    assertEquals((reply.str("cashierTicket"), reply.str("requestId")), (first.str("cashierTicket"), first.str("requestId")))
    assertEquals(created.map(_.text("transactionId")), Vector.fill(2)(Some(first.str("requestId"))), "both starts sent the request id")
  }
  Then("one cashier session exists for the player") { () => assertEquals(opened.size, 1) }
  Then("the player is refused naming the request id {string}") { (requestId: String) =>
    refused(409, "REQUEST_ID_REUSED", "ingest")
    assertEquals((reply.str("requestId"), reply.str("transactionId")), (requestId, requestId))
  }
