package io.eitheror.casino.payouts.port

import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.port.WireTranslation.*

/** contracts/wire.md, row by row: each recorded request as this service takes it, and its answer read back. */
class WireTranslationSuite extends munit.FunSuite:
  private val ctx = Context("goldvibe", ids = Map("orig-r" -> "id-r", "orig-d" -> "id-d"), key = () => "k-1")
  private def body(text: String) = Json.parse(text)
  private def tr(method: String, path: String, b: Option[String] = None) =
    WireTranslation.request(method, path, b.flatMap(body), ctx).getOrElse(fail(s"$method $path is not a row"))

  test("amounts: × 100 for EUR, GBP and CAD; more places than the currency is a failure, never a rounding"):
    assertEquals((minor(BigDecimal("12.34"), "EUR"), minor(BigDecimal("5"), "GBP"), minor(BigDecimal("0.1"), "CAD")), (1234L, 500L, 10L))
    intercept[AssertionError](minor(BigDecimal("1.234"), "EUR"))
    assertEquals(major(1234L, "EUR"), BigDecimal("12.34"))

  test("a request: the same path and names, the amount in minor units, the wallet's currency when absent, a fresh key"):
    val r = tr("POST", "/v1/casino/brands/goldvibe/players/p-1/withdrawal-requests",
      Some("""{"amount":"25.50","terminalId":"t-1","confirmForfeit":true}"""))
    assertEquals((r.row, r.path), (Row.Request, "/v1/casino/brands/goldvibe/players/p-1/withdrawal-requests"))
    val b = r.body.get
    assertEquals((b.long("amount"), b.text("currency"), b.text("key"), b.text("terminalId"), b.bool("confirmForfeit")),
      (Some(2550L), Some("EUR"), Some("k-1"), Some("t-1"), Some(true)))

  test("a request's answer: its id the original's, its amounts back in decimals"):
    val r        = tr("POST", "/v1/casino/brands/goldvibe/players/p-1/withdrawal-requests", Some("""{"amount":"25.50","currency":"EUR"}"""))
    val observed = body("""{"requestId":"id-r","status":"PENDING_REVIEW","amount":2550,"currency":"EUR","forfeitedAmount":200,"createdAt":"x"}""")
    assertEquals(captured(r, observed.get), Some("id-r"))
    val recorded = body("""{"requestId":"orig-r","status":"PENDING_REVIEW","amount":25.50,"forfeitedAmount":2.00}""")
    val (status, out) = answer(r, 201, observed, recorded, Some("orig-r"))
    assertEquals((status, out.flatMap(_.text("requestId")), out.flatMap(_.text("status"))), (201, Some("orig-r"), Some("PENDING_REVIEW")))
    assertEquals(out.map(o => BigDecimal(o.get("amount").decimalValue)), Some(BigDecimal("25.50")))
    assertEquals(out.map(o => BigDecimal(o.get("forfeitedAmount").decimalValue)), Some(BigDecimal("2")))
    assertEquals(out.flatMap(_.text("createdAt")), None)

  test("a refusal's limit figures back in decimals"):
    val r = tr("POST", "/v1/casino/brands/goldvibe/players/p-1/withdrawal-requests", Some("""{"amount":"10","currency":"EUR"}"""))
    val observed = body("""{"error":"WITHDRAWAL_EXCEEDS_WEEKLY_LIMIT","message":"m","weeklyLimit":100000,"weeklyUsed":95000,"weeklyRemaining":5000,"currency":"EUR"}""")
    val (status, out) = answer(r, 422, observed, None)
    assertEquals((status, out.map(_.get("weeklyRemaining").decimalValue.toPlainString)), (422, Some("50")))

  test("the player's list compares only the pending rows"):
    val r        = tr("GET", "/v1/casino/brands/goldvibe/players/p-1/withdrawal-requests")
    val observed = body("""{"requests":[{"requestId":"a","status":"PENDING_REVIEW","amount":100},{"requestId":"b","status":"COMPLETED","amount":200}]}""")
    val (_, out) = answer(r, 200, observed, None)
    assertEquals(out.map(_.objects("requests").flatMap(_.text("requestId"))), Some(Vector("a")))
    assertEquals(recordedShape(Row.Requests, observed.get).objects("requests").size, 1)

  test("a cancel names this service's id"):
    assertEquals(tr("DELETE", "/v1/casino/brands/goldvibe/players/p-1/withdrawal-requests/orig-r").path,
      "/v1/casino/brands/goldvibe/players/p-1/withdrawal-requests/id-r")

  test("terminals: the currency the wallet's when missing; compared by id and order"):
    assertEquals(tr("GET", "/v1/casino/brands/goldvibe/players/p-1/withdrawal-terminals").path,
      "/v1/casino/brands/goldvibe/players/p-1/withdrawal-terminals?currency=EUR")
    val r        = tr("GET", "/v1/casino/brands/goldvibe/players/p-1/withdrawal-terminals?currency=CAD")
    val observed = body("""{"terminals":[{"id":"t-2","name":"Interac"},{"id":"t-1","name":"Bank"}]}""")
    val recorded = body("""{"terminals":[{"id":"t-2","name":"Interac e-Transfer","scheme":"INTERAC"},{"id":"t-1"}]}""")
    assertEquals(answer(r, 200, observed, recorded)._2.map(Json.render), recorded.map(recordedShape(Row.Terminals, _)).map(Json.render))

  test("destinations: the IBAN compared by its last four, on both sides"):
    val r        = tr("GET", "/v1/casino/brands/goldvibe/players/p-1/payout-destinations")
    val observed = body("""{"destinations":[{"id":"id-d","ibanLastFour":"3000","isDefault":true}]}""")
    val recorded = body("""{"destinations":[{"id":"orig-d","iban":"DE89 3704 0044 0532 0130 00","isDefault":true}]}""")
    val (_, out) = answer(r, 200, observed, recorded)
    assertEquals(out.map(_.objects("destinations").head.text("iban")), Some(Some("3000")))
    assertEquals(recordedShape(Row.Destinations, recorded.get).objects("destinations").head.text("iban"), Some("3000"))
    assertEquals(tr("POST", "/v1/casino/brands/goldvibe/players/p-1/payout-destinations", Some("""{"iban":"x"}""")).row, Row.SaveDestination)

  test("set-default is a PUT of the destination; a delete's 204 is the original's 200"):
    val set = tr("PUT", "/v1/casino/brands/goldvibe/players/p-1/payout-destinations/orig-d/set-default")
    assertEquals((set.row, set.path, set.body.flatMap(_.bool("setAsDefault"))),
      (Row.SetDefault, "/v1/casino/brands/goldvibe/players/p-1/payout-destinations/id-d", Some(true)))
    val del = tr("DELETE", "/v1/casino/brands/goldvibe/players/p-1/payout-destinations/orig-d")
    assertEquals((del.row, del.path), (Row.DeleteDestination, "/v1/casino/brands/goldvibe/players/p-1/payout-destinations/id-d"))
    assertEquals(answer(del, 204, None, body("""{"ok":true}"""))._1, 200)

  test("the queue: the same path and query; rows compared by id, status and amount"):
    val r = tr("GET", "/v1/casino/brands/goldvibe/withdrawal-requests?status=ALL&page=2")
    assertEquals(r.path, "/v1/casino/brands/goldvibe/withdrawal-requests?status=ALL&page=2")
    val observed = body("""{"requests":[{"requestId":"id-r","status":"APPROVED","amount":2550,"playerId":"p-1"}],"total":1}""")
    val (_, out) = answer(r, 200, observed, None)
    assertEquals(out.map(o => Json.render(o.objects("requests").head)), Some("""{"requestId":"id-r","status":"APPROVED","amount":25.5}"""))

  test("each decision names this service's id"):
    for action <- Vector("approve", "reject", "confirm-payout", "mark-payout-failed") do
      val r = tr("POST", s"/v1/casino/brands/goldvibe/withdrawal-requests/orig-r/$action", Some("""{"reason":"x"}"""))
      assertEquals((r.row, r.path), (Row.Decision, s"/v1/casino/brands/goldvibe/withdrawal-requests/id-r/$action"))

  test("withdrawal-history is the same route"):
    assertEquals(tr("GET", "/v1/casino/brands/goldvibe/players/p-1/withdrawal-history").row, Row.History)

  test("the config: the EUR limits per currency in minor units, the turnover fields dropped, and back"):
    val r = tr("PUT", "/v1/casino/brands/goldvibe/withdrawal-config",
      Some("""{"weeklyWithdrawalLimitEur":1000,"monthlyWithdrawalLimitEur":"2500.50","withdrawalTurnoverMultiplier":3,"withdrawalTurnoverDefinition":"gross_wagered"}"""))
    assertEquals(r.body.map(Json.render), Some("""{"limits":{"EUR":{"weekly":100000,"monthly":250050}}}"""))
    val (_, out) = answer(r, 200, body("""{"limits":{"EUR":{"weekly":100000,"monthly":null}}}"""), None)
    assertEquals(out.map(Json.render), Some("""{"weeklyWithdrawalLimitEur":1000}"""))

  test("the outcome: the internal route as payments/psp-gateway, the secret dropped"):
    val r = tr("POST", "/internal/v1/casino/withdrawal-requests/orig-r/outcome", Some("""{"outcome":"completed","providerReference":"uf-1"}"""))
    assertEquals((r.row, r.path, r.caller), (Row.Outcome, "/v1/casino/brands/goldvibe/withdrawals/id-r/outcome", Caller.Gateway))

  test("a route the table does not name is not this service's"):
    assertEquals(WireTranslation.request("GET", "/v1/casino/brands/goldvibe/wallets/p-1", None, ctx), None)
