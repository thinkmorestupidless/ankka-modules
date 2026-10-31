package io.eitheror.casino.payouts.it

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.contract.facts.*
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.clients.Gateway
import io.eitheror.casino.payouts.player.*
import io.eitheror.casino.payouts.terminals.{Refresh, TerminalCatalogue}
import io.eitheror.casino.payouts.workflow.WithdrawalWorkflow

import java.util.UUID

/**
 * US2 over the whole service (R4, R10): the gates in the original's order, the wallet's preview and hold
 * refusals in the original's statuses, a limit read by the request after it, and the config routes with
 * their audit.
 */
class GatesSuite extends munit.FunSuite with LogCapturing:
  private lazy val kit = SharedKit.kit
  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")
  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    val bank = FakeGateway.terminal("t-bank", "unityfinance", destination = true)
    kit.gateway.terminals("EUR", bank)
    kit.components.forKeyValueEntity(EntityId(kit.slug)).call(TerminalCatalogue.refresh)
      .invoke(Refresh("EUR", Vector(Gateway.terminal(bank).get), kit.now())): Unit

  private def record(p: String) = kit.components.forEventSourcedEntity(EntityId(p))

  private def player(kyc: Kyc = Kyc.Verified): (String, String) =
    val p = s"gt-${UUID.randomUUID().toString.take(8)}"
    record(p).call(PlayerPayouts.seeNames).invoke(SeeNames(Personal("Ana"), Personal("Silva"), kit.now())): Unit
    record(p).call(PlayerPayouts.seeKyc).invoke(SeeKyc(kyc, kit.now())): Unit
    val d = s"d-$p"
    record(p).call(PlayerPayouts.saveDestination).invoke(SaveDestination(d, "Ana", "Silva", "DE89370400440532013000", "main", true, kit.now())): Unit
    (p, d)

  private def ask(p: String, d: String, amount: Long = 5000L, key: String = UUID.randomUUID().toString, extra: Seq[(String, Any)] = Nil): Reply =
    kit.json("POST", kit.playerPath(s"/$p/withdrawal-requests"),
      Json.obj((Seq[(String, Any)]("amount" -> amount, "currency" -> "EUR", "terminalId" -> "t-bank", "destinationId" -> d, "key" -> key) ++ extra)*),
      kit.asPlayer(p))

  private def refused(r: Reply, status: Int, code: String): Unit = assertEquals((r.status, r.str("error")), (status, code), r.text)

  private def config(method: String, body: Option[String] = None, roles: String = s"brand_${kit.slug},brand_admin", subject: String = "op-admin") =
    kit.request(method, s"/v1/casino/brands/${kit.slug}/withdrawal-config", body, kit.asOperator(subject, roles))

  private def previews = kit.wallet.received.count(_.path.endsWith("/holds/preview"))

  // ── the order ────────────────────────────────────────────────────────────────────────────────

  test("identity is checked before the wallet is asked anything"):
    val (p, d) = player(Kyc.Pending)
    kit.wallet.previewFor(p, FakeWallet.preview(refusal = Some("INSUFFICIENT_FUNDS")))
    refused(ask(p, d), 402, "WITHDRAWAL_BLOCKED_KYC")
    assertEquals(previews, 0)

  test("the preview's refusals, answered 200 in its body, in the original's statuses"):
    val cases = Vector("INSUFFICIENT_FUNDS" -> 402, "WITHDRAWAL_BLOCKED_TURNOVER" -> 402, "WITHDRAWAL_REQUEST_ALREADY_PENDING" -> 409,
      "SOMETHING_NEW" -> 409)
    cases.foreach { (code, status) =>
      val (p, d) = player()
      kit.wallet.previewFor(p, FakeWallet.preview(refusal = Some(code)))
      refused(ask(p, d), status, code)
    }
    assertEquals(kit.wallet.calls("").size, 0)

  test("the preview's insufficient funds comes before a limit; the limit before the forfeit's refusals"):
    val (p, d) = player()
    put(s"""{"limits":{"EUR":{"weekly":1000}}}""")
    kit.wallet.previewFor(p, FakeWallet.preview(refusal = Some("INSUFFICIENT_FUNDS")))
    refused(ask(p, d), 402, "INSUFFICIENT_FUNDS")
    kit.wallet.previewFor(p, FakeWallet.preview(refusal = Some("WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT"), confirmationRequired = true))
    refused(ask(p, d), 402, "WITHDRAWAL_BLOCKED_WEEKLY_LIMIT")
    put("""{"limits":{}}""")
    refused(ask(p, d), 422, "WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT")
    kit.wallet.previewFor(p, FakeWallet.preview(confirmationRequired = true, bonusBalance = 100L))
    refused(ask(p, d), 402, "WITHDRAWAL_REQUIRES_BONUS_FORFEIT_CONFIRMATION")
    refused(ask(p, d, extra = Seq("terminalId" -> "t-none", "confirmForfeit" -> true)), 422, "TERMINAL_NOT_ELIGIBLE")
    assertEquals(kit.wallet.calls("").size, 0)

  test("the hold's 409 refusals in the original's statuses, turnover as 402; nothing is opened"):
    val cases = Vector("INSUFFICIENT_FUNDS" -> 402, "WITHDRAWAL_BLOCKED_TURNOVER" -> 402, "WITHDRAWAL_REQUEST_ALREADY_PENDING" -> 409,
      "WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT" -> 422)
    cases.foreach { (code, status) =>
      val (p, d) = player()
      val key    = UUID.randomUUID().toString
      val id     = WithdrawalWorkflow.requestId(p, key)
      kit.wallet.holdFor(id, FakeWallet.refusal(code))
      refused(ask(p, d, key = key), status, code)
      assert(!kit.components.forEventSourcedEntity(EntityId(id)).call(io.eitheror.casino.payouts.request.WithdrawalRequest.get).invoke().opened)
    }

  // ── the limits ───────────────────────────────────────────────────────────────────────────────

  private def put(body: String): Reply =
    val r = config("PUT", Some(body))
    assertEquals(r.status, 200, r.text)
    r

  test("a limit counts a request made a second before (read-your-writes)"):
    put("""{"limits":{"EUR":{"weekly":10000}}}""")
    val (p, d) = player()
    assertEquals(ask(p, d, 6000L).status, 201)
    kit.advance(java.time.Duration.ofSeconds(1))
    val r = ask(p, d, 6000L)
    refused(r, 402, "WITHDRAWAL_BLOCKED_WEEKLY_LIMIT")
    assertEquals((r.json.long("used"), r.json.long("remaining")), (Some(6000L), Some(4000L)))

  test("the config routes: read, set, null for unset, admins only"):
    val set = put("""{"limits":{"eur":{"weekly":100000,"monthly":null},"GBP":{"monthly":250000}}}""")
    val expected = Json.obj("limits" -> Json.obj(
      "EUR" -> Json.obj("weekly" -> 100000L, "monthly" -> null),
      "GBP" -> Json.obj("weekly" -> null, "monthly" -> 250000L)))
    assertEquals(Json.render(set.json), Json.render(expected))
    assertEquals(Json.render(config("GET").json), Json.render(expected))
    val finance = config("GET", roles = s"brand_${kit.slug},brand_finance")
    assertEquals((finance.status, finance.str("error")), (403, "forbidden"))
    assertEquals(config("PUT", Some("""{"limits":{}}"""), roles = s"brand_${kit.slug},brand_finance").status, 403)
    assertEquals(config("GET", roles = "brand_other,brand_admin").status, 403)

  test("a negative or decimal limit, or a currency that is not a code, is refused naming the field; nothing is stored"):
    put("""{"limits":{"EUR":{"weekly":100000}}}""")
    Vector(
      """{"limits":{"EUR":{"weekly":-1}}}"""     -> "limits.EUR.weekly",
      """{"limits":{"EUR":{"monthly":10.5}}}"""  -> "limits.EUR.monthly",
      """{"limits":{"EUR":{"weekly":"lots"}}}""" -> "limits.EUR.weekly",
      """{"limits":{"EURO":{"weekly":1}}}"""     -> "limits.EURO",
      """{"limits":{"EUR":5}}"""                 -> "limits.EUR",
      """{"weekly":5}"""                         -> "limits"
    ).foreach { (body, field) =>
      val r = config("PUT", Some(body))
      assertEquals((r.status, r.str("error"), r.str("field")), (400, "INVALID_REQUEST", field), body)
    }
    assertEquals(config("GET").json.get("limits").get("EUR").long("weekly"), Some(100000L))

  test("each change of the limits is in platform.audit with the operator, before and after"):
    val subject = s"op-${UUID.randomUUID().toString.take(6)}"
    assertEquals(config("PUT", Some("""{"limits":{"EUR":{"weekly":50000}}}"""), subject = subject).status, 200)
    assertEquals(config("PUT", Some("""{"limits":{"EUR":{"weekly":70000}}}"""), subject = subject).status, 200)
    val records = kit.eventually("audited") {
      Some(kit.facts(PlatformAudit).collect { case r: AuditRecord if r.actorId == subject => r }).filter(_.size == 2)
    }
    assertEquals(records.map(r => (r.action, r.subjectId, r.changes.map(c => (c.field, c.before, c.after)))), Vector(
      ("payouts.limits_set", kit.slug, Vector(("limits.EUR.weekly", None, Some("50000")))),
      ("payouts.limits_set", kit.slug, Vector(("limits.EUR.weekly", Some("50000"), Some("70000"))))))
