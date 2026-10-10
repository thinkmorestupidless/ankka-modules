package io.eitheror.casino.wallet.entity

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

import java.time.Instant

/** The money path end to end on one wallet, through the entity's own serializers. */
class WalletBasicsSuite extends munit.FunSuite:

  private val t0 = Instant.parse("2026-10-09T10:00:00Z")

  private def deposit(key: String, amount: Long, at: Instant = t0) =
    Deposit(key, amount, "dreamzpay", "t1", "dreamzpay", "card", TurnoverTerms(0, "gross_wagered"), at)

  private def bet(key: String, round: String, amount: Long) =
    PlaceBet(key, round, "slot-a", "s1", "pragmatic", "slots", amount, allowNegative = false, BetSwitches(), t0)

  test("a deposit, a bet and a win move real balance 10000 → 9000 → 11500") {
    val kit = EventSourcedTestKit.of(WalletEntity, "p1/EUR")
    assertEquals(kit.call(WalletEntity.deposit)(deposit("pay-1", 10000)).replyValue.realBalance, 10000L)
    val placed = kit.call(WalletEntity.placeBet)(bet("tx-1", "r1", 1000))
    assertEquals((placed.replyValue.realBalance, placed.replyValue.walletType), (9000L, Some("real")))
    assertEquals(kit.call(WalletEntity.creditWin)(CreditWin("tx-2", "r1", 2500, t0)).replyValue.realBalance, 11500L)
    assertEquals(kit.currentState.openBets, Vector.empty)
  }

  test("a repeat under the same key answers the first outcome and persists nothing") {
    val kit = EventSourcedTestKit.of(WalletEntity, "p2/EUR")
    kit.call(WalletEntity.deposit)(deposit("pay-1", 10000))
    val first = kit.call(WalletEntity.placeBet)(bet("tx-1", "r1", 1000))
    val again = kit.call(WalletEntity.placeBet)(bet("tx-1", "r1", 1000))
    assertEquals(again.replyValue, first.replyValue)
    assert(!again.persisted)
    assertEquals(kit.currentState.realBalance, 9000L)
  }

  test("the same key carrying another amount is KEY_REUSED, naming the first, and moves nothing") {
    val kit = EventSourcedTestKit.of(WalletEntity, "p3/EUR")
    kit.call(WalletEntity.deposit)(deposit("pay-1", 10000))
    kit.call(WalletEntity.placeBet)(bet("tx-1", "r1", 1000))
    val reused = kit.call(WalletEntity.placeBet)(bet("tx-1", "r1", 2000))
    assertEquals(reused.replyValue.code, Some("KEY_REUSED"))
    assertEquals(reused.replyValue.message, Some("Key tx-1 was used by a place-bet of 1000"))
    assertEquals(kit.currentState.realBalance, 9000L)
    assert(reused.events.forall(_.isInstanceOf[WalletEvent.CommandRefused]))
  }

  test("a refusal under a new key is recorded and its repeat is refused the same way") {
    val kit = EventSourcedTestKit.of(WalletEntity, "p4/EUR")
    kit.call(WalletEntity.deposit)(deposit("pay-1", 500))
    val refused = kit.call(WalletEntity.placeBet)(bet("tx-1", "r1", 1000))
    assertEquals(refused.replyValue.code, Some("INSUFFICIENT_FUNDS"))
    assertEquals(refused.replyValue.message, Some("Insufficient funds in real and bonus wallets"))
    val again = kit.call(WalletEntity.placeBet)(bet("tx-1", "r1", 1000))
    assertEquals(again.replyValue, refused.replyValue)
    assert(!again.persisted)
    assertEquals(kit.currentState.openBets, Vector.empty)
  }

  test("a bet on a wallet never created is WALLET_NOT_FOUND") {
    val kit = EventSourcedTestKit.of(WalletEntity, "p5/EUR")
    assertEquals(kit.call(WalletEntity.placeBet)(bet("tx-1", "r1", 0)).replyValue.code, Some("WALLET_NOT_FOUND"))
  }
