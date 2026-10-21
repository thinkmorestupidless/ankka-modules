package io.eitheror.casino.merchant.terminals

import com.thinkmorestupidless.ankka.core.ErrorCode
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.merchant.hierarchy.Actor

import java.time.Instant

/** FR-007 at the entity, with the original's `TerminalDeleteRoutesSpec` rule. */
class TerminalSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-10-12T10:00:00Z")
  private val op = Actor.operator("op-1")
  private val deposits = Capability(supportsDeposit = true, supportsWithdrawal = false, requiresPayoutDestination = false)

  private def created(id: String = "t-1") =
    val kit = EventSourcedTestKit.of(Terminal, id)
    kit.call(Terminal.create)(CreateTerminal("sb-1", "dreamzpay", "VISA", "Visa", Vector("GB"), Map("memberId" -> "m"), Vector("secureKey"),
      deposits, op, at)): Unit
    kit

  test("a terminal is created inactive, taking deposits and not withdrawals") {
    val s = created().currentState
    assertEquals((s.status, s.supportsDeposit, s.supportsWithdrawal, s.gateway), ("INACTIVE", true, false, "DREAMZPAY"))
    assertEquals(s.propertyKeys, Vector("memberId", "secureKey"))
  }

  test("properties change with the secret keys named, never their values") {
    val kit = created()
    val r   = kit.call(Terminal.setProperties)(SetProperties(Map("memberId" -> "m2", "supportedCurrencies" -> "EUR,GBP"), Vector("secureKey"), op, at))
    assertEquals(r.events, Vector(TerminalEvent.PropertiesChanged(Map("memberId" -> "m2", "supportedCurrencies" -> "EUR,GBP"), Vector("secureKey"), op, at)))
  }

  test("it can be activated, disabled and given a new capability") {
    val kit = created()
    kit.call(Terminal.activate)(TerminalChange(op, at)): Unit
    assert(kit.currentState.active)
    assert(!kit.call(Terminal.activate)(TerminalChange(op, at)).persisted)
    kit.call(Terminal.disable)(TerminalChange(op, at)): Unit
    assert(!kit.currentState.active)
    kit.call(Terminal.setCapability)(SetCapability(Capability(true, true, true), op, at)): Unit
    assert(kit.currentState.supportsWithdrawal && kit.currentState.requiresPayoutDestination)
  }

  test("a deleted terminal cannot be resurrected or changed") {
    val kit = created()
    kit.call(Terminal.delete)(TerminalChange(op, at)): Unit
    assertEquals(kit.call(Terminal.activate)(TerminalChange(op, at)).error.code, ErrorCode.NotFound)
    val again = kit.call(Terminal.create)(CreateTerminal("sb-1", "DREAMZPAY", "VISA", "Visa", Vector.empty, Map.empty, Vector.empty, deposits, op, at))
    assertEquals(again.error.code, ErrorCode.Conflict)
  }
