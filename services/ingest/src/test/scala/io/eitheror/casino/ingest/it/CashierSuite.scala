package io.eitheror.casino.ingest.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.ingest.Json
import io.eitheror.casino.ingest.Json.*
import io.eitheror.casino.ingest.requests.{Outcome, Ownership, Route}

/**
 * T027, T038 (US1, US3): the create admitted by the cashier key and shaped for `merchant`; the submit, the
 * abandon and the read admitted by the ticket or the creating sub-brand's key; every request one record.
 */
class CashierSuite extends munit.FunSuite with LogCapturing:
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def creates = kit.merchant.creates

  // ── the create (US1) ────────────────────────────────────────────────────────────────────────────

  test("a create under an active key is merchant's answer byte for byte, shaped as 004's command under the key's sub-brand") {
    val key = kit.issueKey()
    val body = kit.body("transactionId" -> "t-1", "subBrandId" -> "someone-else", "customerEmail" -> "p@example.com",
      "customerFirstName" -> "Ada")
    val r = kit.create(Some(key), body, "X-Forwarded-For" -> "203.0.113.9, 10.0.0.1")
    assertEquals(r.status, 200, r.text)
    val sent = kit.merchant.received(FakeMerchant.Create).loneElement
    val answered = Json.parse(r.text).get
    assertEquals((answered.text("transactionId"), answered.text("redirectUrl")), (Some("t-1"), Some("https://example.test/return")))
    assert(answered.text("session_token").exists(_.nonEmpty))
    assertEquals(answered.get("available_terminals").get(0).text("terminalEntityId"), Some("sim-eur"))
    assertEquals(answered.get("resolvedFrom").text("level"), Some("subBrand"))
    val c = sent.json.get
    assertEquals((c.text("subBrandId"), c.long("amount"), c.text("clientIp")), (Some(key.subBrand), Some(5000L), Some("203.0.113.9")))
    assertEquals((c.text("customerEmail"), c.text("customerFirstName")), (Some("p@example.com"), Some("Ada")))

    val record = kit.recordWhere(key.subBrand)(_.requestId.contains("t-1"))
    assertEquals((record.route, record.amount, record.currency, record.country, record.outcome), (Route.Create, Some(5000L), Some("EUR"),
      Some("GB"), Outcome.Served))
    assertEquals(record.ticket, answered.text("session_token"))
    assert(record.receivedAt.isDefined)
  }

  test("no merchantKey is 401 UNAUTHENTICATED, recorded key_missing under no sub-brand; merchant is not asked") {
    val r = kit.create(None, kit.body("transactionId" -> "t-nokey"))
    assertEquals((r.status, r.str("error")), (401, "UNAUTHENTICATED"))
    assertEquals(creates, Vector.empty)
    kit.eventually("the key_missing record")(kit.unbranded().find(_.text("outcome").contains(Outcome.KeyMissing))): Unit
  }

  test("a made-up key and a disabled key are the same 401; the records tell them apart, the disabled one under its sub-brand (FR-005)") {
    val madeUp = kit.create(Some(RawKey("mk_made_up", "k-x", "sb-x")), kit.body())
    val key    = kit.issueKey()
    assertEquals(kit.create(key).status, 200)
    kit.deactivateKey(key)
    val disabled = kit.create(key)
    assertEquals((madeUp.status, madeUp.text), (disabled.status, disabled.text))
    assertEquals((disabled.status, disabled.str("error")), (401, "INVALID_MERCHANT_KEY"))
    assertEquals(creates.size, 1)
    kit.eventually("the key_unknown record")(kit.unbranded().find(_.text("outcome").contains(Outcome.KeyUnknown))): Unit
    kit.recordWhere(key.subBrand)(_.outcome == Outcome.KeyDisabled): Unit
  }

  test("the header is read whatever its case (research R11)") {
    val key = kit.issueKey()
    Seq("MerchantKey", "merchantkey", "MERCHANTKEY").foreach { name =>
      assertEquals(kit.request("POST", "/v1/cashier/sessions", Some(kit.body()), kit.asSubBrand :+ (name -> key.raw)).status, 200, name)
    }
  }

  test("a body over the bound is 413 before the key is checked, recorded under no sub-brand") {
    val big = kit.body("customerAddress" -> "x" * kit.settings.bodyBound)
    val r   = kit.create(Some(RawKey("mk_made_up", "k-x", "sb-x")), big)
    assertEquals((r.status, r.str("error")), (413, "body_too_large"))
    kit.eventually("the body_too_large record")(kit.unbranded().find(_.text("outcome").contains(Outcome.BodyTooLarge))): Unit
  }

  test("a body that is not a JSON object is 400 not_json") {
    val key = kit.issueKey()
    Seq("[]", "\"x\"", "{\"amountCents\":").foreach { b =>
      val r = kit.create(Some(key), b)
      assertEquals((r.status, r.str("error")), (400, "not_json"), b)
    }
    assertEquals(creates, Vector.empty)
  }

  test("each field rule is 400 INVALID_REQUEST naming the field, recorded invalid_field; merchant is not asked (FR-007)") {
    val key = kit.issueKey()
    Seq[(String, Any)]("amountCents" -> None, "amountCents" -> 50.5, "currency" -> None, "customerId" -> None, "customerId" -> "p1",
      "country" -> None, "redirectUrl" -> None).foreach { change =>
      val r = kit.create(key, change)
      assertEquals((r.status, r.str("error"), r.str("field")), (400, "INVALID_REQUEST", change._1), change.toString)
    }
    assertEquals(creates, Vector.empty)
    val invalid = kit.eventually("seven invalid_field records")(Some(kit.records(key.subBrand).filter(_.outcome == Outcome.InvalidField)).filter(_.size == 7))
    assertEquals(invalid.flatMap(_.refusal).toSet, Set("amountCents", "currency", "customerId", "country", "redirectUrl"))
  }

  test("merchant's refusal is returned as it sent it and recorded refused with its code") {
    val key = kit.issueKey()
    kit.merchant.refuseCreate = Some(400 -> "NO_ROUTING_TREE")
    val r = kit.create(key, "transactionId" -> "t-notree")
    assertEquals((r.status, r.text), (400, """{"error":"NO_ROUTING_TREE"}"""))
    val record = kit.recordWhere(key.subBrand)(_.requestId.contains("t-notree"))
    assertEquals((record.outcome, record.refusal), (Outcome.Refused, Some("NO_ROUTING_TREE")))
  }

  test("a key refusal is one audit record per refusal, ce-id the record id, and never the key (FR-019)") {
    val raw = "mk_audit_marker_7f3a"
    kit.create(Some(RawKey(raw, "k-x", "sb-x")), kit.body()): Unit
    val record = kit.eventually("the key_unknown record")(kit.unbranded().find(_.text("outcome").contains(Outcome.KeyUnknown)))
    val id     = record.text("recordId").get
    val audit  = kit.eventually("its audit record")(kit.audit().find(_.recordId == id))
    assertEquals((audit.service, audit.reasonCode, audit.actorKind), ("ingest", Some(Outcome.KeyUnknown), "unknown"))
    assert(!kit.auditTexts().exists(_.contains(raw)))
    assert(!kit.auditTexts().exists(_.contains(io.eitheror.casino.ingest.shape.Digests.ofKey(raw))))
  }

  // ── the submit, the abandon and the read (US3) ──────────────────────────────────────────────────

  private def session(key: RawKey, requestId: String = s"t-${java.util.UUID.randomUUID()}"): String =
    val r = kit.create(key, "transactionId" -> requestId)
    assertEquals(r.status, 200, r.text)
    r.str("session_token")

  test("a submit by the ticket reaches merchant, and its payment is owned by the ticket's sub-brand") {
    val key    = kit.issueKey()
    val ticket = session(key, "t-sub")
    val r      = kit.submit(ticket)
    assertEquals((r.status, r.str("status")), (200, "AWAITING_REDIRECT"), r.text)
    val paymentId = r.str("paymentId")
    assertEquals(kit.ownership(Ownership.ofPayment(paymentId)).map(o => (o.subBrand, o.requestId)), Some((key.subBrand, Some("t-sub"))))
    val record = kit.recordWhere(key.subBrand)(r => r.route == Route.Submit && r.paymentId.contains(paymentId))
    assertEquals((record.outcome, record.ticket, record.requestId), (Outcome.Served, Some(ticket), Some("t-sub")))
  }

  test("a second submit is merchant's first answer, and merchant's refusal of a terminal is passed through") {
    val key    = kit.issueKey()
    val ticket = session(key)
    val first  = kit.submit(ticket)
    assertEquals(kit.submit(ticket).text, first.text)
    val other = session(key)
    val r     = kit.submit(other, terminal = "nope")
    assertEquals((r.status, r.str("error")), (400, "terminal_not_found"))
  }

  test("a ticket nobody was given is 404 SESSION_NOT_FOUND, and merchant is not asked") {
    val r = kit.submit("no-such-ticket")
    assertEquals((r.status, r.str("error")), (404, "SESSION_NOT_FOUND"))
    assertEquals(kit.abandon("no-such-ticket").status, 404)
    assertEquals(kit.merchant.received(FakeMerchant.Submit) ++ kit.merchant.received(FakeMerchant.Abandon), Vector.empty)
  }

  test("an abandon by the ticket reaches merchant") {
    val key    = kit.issueKey()
    val ticket = session(key)
    assertEquals(kit.abandon(ticket).status, 200)
    assertEquals(kit.merchant.received(FakeMerchant.Abandon).loneElement.path, s"/v1/cashier/sessions/$ticket/abandon")
  }

  test("a read: the creating key or the session's ticket is merchant's answer; another sub-brand, a stranger's ticket, nothing at all are not (FR-009)") {
    val key       = kit.issueKey()
    val ticket    = session(key)
    val paymentId = kit.submit(ticket).str("paymentId")
    val byKey     = kit.readPayment(paymentId, key = Some(key))
    assertEquals((byKey.status, byKey.str("status"), byKey.json.long("amountCents")), (200, "AWAITING_REDIRECT", Some(5000L)), byKey.text)
    assertEquals(kit.readPayment(paymentId, ticket = Some(ticket)).text, byKey.text)
    val asked = kit.merchant.received(FakeMerchant.Read).size

    val other = kit.issueKey()
    val missing = kit.readPayment("no-such-payment", key = Some(other))
    val theirs  = kit.readPayment(paymentId, key = Some(other))
    assertEquals((theirs.status, theirs.text), (missing.status, missing.text))
    assertEquals(theirs.status, 404)
    assertEquals(kit.readPayment(paymentId, ticket = Some(session(other))).status, 404)
    assertEquals(kit.readPayment(paymentId).status, 401)
    assertEquals(kit.merchant.received(FakeMerchant.Read).size, asked)
  }

  extension [A](xs: Vector[A]) private def loneElement: A = { assertEquals(xs.size, 1, xs.toString); xs.head }
