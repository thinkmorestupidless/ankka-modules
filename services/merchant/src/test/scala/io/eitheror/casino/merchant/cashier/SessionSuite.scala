package io.eitheror.casino.merchant.cashier

import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.testkit.KeyValueEntityTestKit
import io.eitheror.casino.contract.personal.Personal

import java.time.Instant

/** FR-013 to FR-015 at the session: everything the submit needs, a deadline, and one payment per session. */
class SessionSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-10-12T10:00:00Z")
  private def state = SessionState("t-1", "sb-1", Some("acme"), "tx-1", 5000, "EUR", "GB", "acme:p1", "https://r",
    CustomerFields(email = Some(Personal("p@example.com"))), deadline = Some(at.plusSeconds(1800)))

  test("a session keeps the customer fields as personal values and expires with the payment's deadline") {
    val kit = KeyValueEntityTestKit.of(Session, "t-1")
    val r   = kit.call(Session.open)(OpenSession(state, at))
    assert(r.changed)
    assertEquals(kit.currentState.customer.email.map(_.reveal), Some("p@example.com"))
    assert(r.retention.isDefined, "the session expires")
    assertEquals(kit.call(Session.open)(OpenSession(state, at)).error.code, ErrorCode.Conflict)
  }

  test("the first claim wins and every later claim is answered with it; a release lets it be submitted again") {
    val kit = KeyValueEntityTestKit.of(Session, "t-1")
    kit.call(Session.open)(OpenSession(state, at)): Unit
    assertEquals(kit.call(Session.claim)("pay-1").replyValue, "pay-1")
    assertEquals(kit.call(Session.claim)("pay-2").replyValue, "pay-1")
    assertEquals(kit.call(Session.release)("pay-2").replyValue, false)
    assertEquals(kit.call(Session.release)("pay-1").replyValue, true)
    assertEquals(kit.call(Session.claim)("pay-3").replyValue, "pay-3")
  }

  test("a claim on no session is SESSION_NOT_FOUND") {
    assertEquals(KeyValueEntityTestKit.of(Session, "t-9").call(Session.claim)("pay-1").error.code, ErrorCode.NotFound)
  }

  test("the player's entries override the session's customer fields, field by field (FR-015)") {
    val merged = state.customer.overriddenBy(CustomerFields(city = Some(Personal("Leeds")), email = None))
    assertEquals((merged.email.map(_.reveal), merged.city.map(_.reveal)), (Some("p@example.com"), Some("Leeds")))
  }
