package io.eitheror.casino.wallet.it

import com.thinkmorestupidless.ankka.core.Serializer
import io.eitheror.casino.wallet.entity.*

import java.time.Instant

/**
 * SC-005's local form and SC-003: a wallet of many transactions survives losing every entity from
 * memory with its balances and its recent keys, its state stays bounded (R4, R17), and a provider
 * callback is timed on it.
 *
 * 1,000 transactions by default; 10,000 and the latency figures with `WALLET_PERF=1` (quickstart 6).
 * The figures are printed so they can be written into research.md R4 and quickstart.md.
 */
class ScaleItSuite extends Harness:

  private val perf = sys.env.get("WALLET_PERF").contains("1")
  private val size = if perf then 10000 else 1000
  private val t0   = Instant.parse("2026-10-09T10:00:00Z")

  test(s"a wallet of $size transactions keeps its balance and its recent keys across a restart; its state stays bounded") {
    val wallet = testKit.componentClient.forEventSourcedEntity(WalletEntity.id("scale", "EUR"))
    wallet.call(WalletEntity.deposit).invoke(Deposit("seed", Long.MaxValue / 4, "psp", "t1", "psp", "card", TurnoverTerms(0, "gross_wagered"), t0))
    (1 until size by 2).foreach { i =>
      wallet.call(WalletEntity.placeBet).invoke(PlaceBet(s"b-$i", s"r-$i", "slot", "s1", "prov", "slots", 100, false, BetSwitches(), t0)): Unit
      wallet.call(WalletEntity.creditWin).invoke(CreditWin(s"w-$i", s"r-$i", 50, t0)): Unit
    }
    val before = wallet.call(WalletEntity.state).invoke()
    val bytes  = Serializer.json[WalletState]("wallet.state")(using io.eitheror.casino.wallet.WalletCodecs.stored[WalletState]).toBytes(before).length
    println(f"wallet of $size%,d transactions: state (snapshot) ${bytes / 1024}%,d KiB, ${before.seen.seen.size}%,d keys held, ${bytes / before.seen.seen.size}%,d bytes a key")
    assertEquals(before.seen.seen.size, math.min(size + 1, WalletState.RecentKeys), "the key store is not bounded")
    assertEquals(before.keyOrder.size, before.seen.seen.size)
    assert(before.settledBets.size <= WalletState.SettledBound)
    assert(bytes < 1024 * 1024, s"the state of a $size-transaction wallet is $bytes bytes; it must stay under 1 MiB whatever the history")

    testKit.restartService()
    val started = System.nanoTime()
    // Small reads after the restart: a reply as large as the whole state is not what any route sends.
    // The client of the runtime that was stopped answers nothing: take the new runtime's.
    val recovered = testKit.componentClient.forEventSourcedEntity(WalletEntity.id("scale", "EUR"))
    val balance   = recovered.call(WalletEntity.balance).invoke()
    val activate  = (System.nanoTime() - started) / 1000000
    println(s"first read after the restart (activation from snapshot and journal): $activate ms")
    assertEquals(balance.realBalance, Long.MaxValue / 4 - (size / 2) * 50L)
    assertEquals(balance.realBalance, before.realBalance)
    // The keys are the seed, then b-1, w-1, …: the seed goes first, and b-1 is held while it is among the last RecentKeys.
    assertEquals(recovered.call(WalletEntity.outcomeOf).invoke("b-1").isDefined, size <= WalletState.RecentKeys,
      "the first bet's key is held exactly when it is among the recent ones")
    assertEquals(recovered.call(WalletEntity.outcomeOf).invoke("seed").isDefined, size < WalletState.RecentKeys)
    assert(recovered.call(WalletEntity.outcomeOf).invoke(s"w-${size - 1}").isDefined, "the last key was forgotten")
    val after = recovered.call(WalletEntity.state).invoke()
    assertEquals(after.seen.seen.size, before.seen.seen.size)
    assertEquals(after.keyOrder, before.keyOrder)
  }

  test("SC-003: a bet on that wallet, median under 50 ms and 99th percentile under 250 ms, with the may-bet call".tag(
    if perf then new munit.Tag("perf") else munit.Ignore)) {
    val times = (1 to 1000).map { i =>
      val started = System.nanoTime()
      val (status, _) = send("POST", s"${wallet("scale")}/bets", Some(s"""{"key":"p-$i","roundId":"p-$i","gameId":"slot","amount":100}"""))
      assertEquals(status, 200)
      (System.nanoTime() - started) / 1000000.0
    }.sorted
    val median = times(times.size / 2)
    val p99    = times((times.size * 99) / 100)
    println(f"bet latency over 1,000 bets: median $median%.1f ms, p99 $p99%.1f ms")
    assert(median < 50 && p99 < 250, s"median $median ms, p99 $p99 ms")
  }
