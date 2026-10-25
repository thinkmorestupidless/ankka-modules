package io.eitheror.casino.wallet.accounts

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.wallet.accounts.AccountEvent.*

import java.time.Instant

/** data-model.md's AccountEntity: its owner, its floor, the credit line and the operator's adjustment (spec 022 US3). */
class AccountEntitySuite extends munit.FunSuite:

  private val t0       = Instant.parse("2026-10-25T10:00:00Z")
  private val exchange = "service:goldvibe/exchange"
  private val clearing = "service:pool-north/clearing"
  private type Kit = EventSourcedTestKit[AccountEntity, AccountState, AccountEvent]

  private def pool(): Kit = EventSourcedTestKit.of(AccountEntity, "pool/m1/EUR")
  private def clearingAccount(): Kit = EventSourcedTestKit.of(AccountEntity, "clearing/north/EUR")

  private def credit(kit: Kit, key: String, amount: Long, owner: String = exchange) =
    kit.call(AccountEntity.credit)(Credit(key, owner, amount, "split", "ada", key, t0)).replyValue
  private def debit(kit: Kit, key: String, amount: Long, owner: String = exchange) =
    kit.call(AccountEntity.debit)(Debit(key, owner, amount, "merge", "ada", key, t0)).replyValue

  test("a pool account is claimed by its first mover and refuses a second owner NOT_OWNER") {
    val kit = pool()
    assert(kit.call(AccountEntity.claim)(Claim(exchange, t0)).replyValue.accepted)
    assertEquals(kit.currentState.owner, exchange)
    assert(kit.call(AccountEntity.claim)(Claim(exchange, t0)).events.isEmpty, "the owner's claim again writes nothing")
    val other = kit.call(AccountEntity.claim)(Claim("service:goldvibe/rewards", t0))
    assertEquals(other.replyValue.code, Some("NOT_OWNER"))
    assertEquals(other.replyValue.details, Map("owner" -> exchange))
    assertEquals(debit(kit, "d-1", 1, owner = "service:goldvibe/rewards").code, Some("NOT_OWNER"))
    assertEquals(credit(kit, "c-1", 1, owner = "service:goldvibe/rewards").code, Some("NOT_OWNER"))
  }

  test("a move on an account no one has claimed is ACCOUNT_NOT_FOUND") {
    assertEquals(debit(pool(), "d-1", 100).code, Some("ACCOUNT_NOT_FOUND"))
    assertEquals(credit(pool(), "c-1", 100).code, Some("ACCOUNT_NOT_FOUND"))
  }

  test("a pool debit below zero is INSUFFICIENT_FUNDS naming the amount, the balance and the floor") {
    val kit = pool()
    kit.call(AccountEntity.claim)(Claim(exchange, t0))
    credit(kit, "c-1", 300)
    val o = debit(kit, "d-1", 301)
    assertEquals(o.code, Some("INSUFFICIENT_FUNDS"))
    assertEquals(o.details, Map("amount" -> "301", "balance" -> "300", "floor" -> "0"))
    assert(debit(kit, "d-2", 300).accepted)
    assertEquals(kit.currentState.balance, 0L)
  }

  test("a clearing account with a line of 500 takes a debit to -500 and refuses the next unit CREDIT_LINE_REACHED") {
    val kit = clearingAccount()
    assert(kit.call(AccountEntity.setCreditLine)(SetCreditLine(500, clearing, t0)).replyValue.accepted)
    assert(debit(kit, "d-1", 500, owner = clearing).accepted)
    assertEquals(kit.currentState.balance, -500L)
    val o = debit(kit, "d-2", 1, owner = clearing)
    assertEquals(o.code, Some("CREDIT_LINE_REACHED"))
    assertEquals(o.details, Map("amount" -> "1", "balance" -> "-500", "floor" -> "-500"))
  }

  test("set-credit-line on an absent account creates it under the setter: claimed, then the line set") {
    val kit = clearingAccount()
    val r   = kit.call(AccountEntity.setCreditLine)(SetCreditLine(500, clearing, t0))
    assertEquals(r.events.map(_.getClass.getSimpleName), Vector("AccountClaimed", "CreditLineSet"))
    assertEquals((kit.currentState.owner, kit.currentState.creditLine, kit.currentState.floor), (clearing, 500L, -500L))
    assertEquals(kit.call(AccountEntity.setCreditLine)(SetCreditLine(500, "service:other/clearing", t0)).replyValue.code, Some("NOT_OWNER"))
  }

  test("set-credit-line on a pool account is NOT_CLEARING; below what the account owes, CREDIT_LINE_BELOW_BALANCE") {
    assertEquals(pool().call(AccountEntity.setCreditLine)(SetCreditLine(500, exchange, t0)).replyValue.code, Some("NOT_CLEARING"))
    val kit = clearingAccount()
    kit.call(AccountEntity.setCreditLine)(SetCreditLine(500, clearing, t0))
    debit(kit, "d-1", 400, owner = clearing)
    val o = kit.call(AccountEntity.setCreditLine)(SetCreditLine(300, clearing, t0)).replyValue
    assertEquals(o.code, Some("CREDIT_LINE_BELOW_BALANCE"))
    assert(kit.call(AccountEntity.setCreditLine)(SetCreditLine(400, clearing, t0)).replyValue.accepted)
  }

  test("an adjustment without a reason is REASON_REQUIRED, past the floor DEBIT_EXCEEDS_BALANCE, of zero a no-op") {
    val kit = pool()
    kit.call(AccountEntity.claim)(Claim(exchange, t0))
    credit(kit, "c-1", 300)
    def adjust(key: String, amount: Long, reason: String) =
      kit.call(AccountEntity.adjust)(AdjustAccount(key, amount, reason, "correction", "op-1", t0))
    assertEquals(adjust("a-1", 100, " ").replyValue.code, Some("REASON_REQUIRED"))
    val past = adjust("a-2", -301, "net settled").replyValue
    assertEquals(past.code, Some("DEBIT_EXCEEDS_BALANCE"))
    assertEquals(past.details, Map("amount" -> "-301", "balance" -> "300"))
    val zero = adjust("a-3", 0, "nothing")
    assert(zero.replyValue.accepted && zero.events.isEmpty)
    val ok = adjust("a-4", -100, "net settled")
    assert(ok.replyValue.accepted)
    val e = ok.events.head.asInstanceOf[AccountAdjusted]
    assertEquals((e.balanceAfter, e.category, e.actorId, e.reason), (200L, "correction", "op-1", "net settled"))
  }

  test("every keyed command repeated answers the first outcome; changed under its key, KEY_REUSED") {
    val kit = pool()
    kit.call(AccountEntity.claim)(Claim(exchange, t0))
    val first  = credit(kit, "c-1", 300)
    val before = kit.allEvents.size
    assertEquals(credit(kit, "c-1", 300), first)
    assertEquals(kit.allEvents.size, before)
    assertEquals(credit(kit, "c-1", 400).code, Some("KEY_REUSED"))
    val refused = debit(kit, "d-1", 9999)
    assertEquals(debit(kit, "d-1", 9999), refused)
    assertEquals(debit(kit, "d-1", 1).code, Some("KEY_REUSED"))
    val adjusted = kit.call(AccountEntity.adjust)(AdjustAccount("a-1", 50, "goodwill", "goodwill", "op-1", t0)).replyValue
    assertEquals(kit.call(AccountEntity.adjust)(AdjustAccount("a-1", 50, "goodwill", "goodwill", "op-1", t0)).replyValue, adjusted)
    assertEquals(kit.call(AccountEntity.adjust)(AdjustAccount("a-1", 60, "goodwill", "goodwill", "op-1", t0)).replyValue.code, Some("KEY_REUSED"))
    assertEquals(kit.currentState.balance, 350L)
  }

  test("the balance query reports an absent account as not found, and an owned one with its floor") {
    assert(!pool().call(AccountEntity.balance).replyValue.found)
    val kit = clearingAccount()
    kit.call(AccountEntity.setCreditLine)(SetCreditLine(500, clearing, t0))
    val b = kit.call(AccountEntity.balance).replyValue
    assertEquals((b.found, b.balance, b.creditLine, b.floor, b.owner, b.currency), (true, 0L, 500L, -500L, clearing, "EUR"))
  }
