package io.eitheror.casino.wallet.entity

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.wallet.funding.Pools

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

  // ── spec 025: the pools a wallet opens with ─────────────────────────────

  private def opening(key: String, real: Long, pools: Option[(Long, Long)]) =
    OpenMigrated(key, real, 0L, Vector.empty, 0L, 0, 0L, t0, depositPool = pools.map(_._1), convertedPool = pools.map(_._2))

  test("open-migrated with pools opens with them, and its repeat answers the same pools") {
    val kit   = EventSourcedTestKit.of(WalletEntity, "m1/EUR")
    val first = kit.call(WalletEntity.openMigrated)(opening("open", 10000, Some((6000L, 4000L))))
    assertEquals((first.replyValue.depositPool, first.replyValue.convertedPool), (Some(6000L), Some(4000L)))
    assertEquals(kit.currentState.pools, Pools(6000, 4000))
    val again = kit.call(WalletEntity.openMigrated)(opening("open", 10000, Some((6000L, 4000L))))
    assertEquals(again.replyValue, first.replyValue)
    assert(!again.persisted)
  }

  test("open-migrated without pools opens the whole real balance converted") {
    val kit = EventSourcedTestKit.of(WalletEntity, "m2/EUR")
    kit.call(WalletEntity.openMigrated)(opening("open", 10000, None)): Unit
    assertEquals(kit.currentState.pools, Pools(0, 10000))
  }

  test("open-migrated carries a negative converted pool as it is") {
    val kit = EventSourcedTestKit.of(WalletEntity, "m3/EUR")
    kit.call(WalletEntity.openMigrated)(opening("open", -100, Some((200L, -300L)))): Unit
    assertEquals((kit.currentState.realBalance, kit.currentState.pools), (-100L, Pools(200, -300)))
  }

  test("open-migrated with pools that do not sum to real balance is POOLS_INVARIANT and opens nothing") {
    val kit     = EventSourcedTestKit.of(WalletEntity, "m4/EUR")
    val refused = kit.call(WalletEntity.openMigrated)(opening("open", 10000, Some((6000L, 3000L))))
    assertEquals(refused.replyValue.code, Some("POOLS_INVARIANT"))
    assert(!kit.currentState.created)
    val half = kit.call(WalletEntity.openMigrated)(OpenMigrated("open-2", 10000, 0L, Vector.empty, 0L, 0, 0L, t0, depositPool = Some(10000L)))
    assertEquals(half.replyValue.code, Some("POOLS_INVARIANT"))
  }
