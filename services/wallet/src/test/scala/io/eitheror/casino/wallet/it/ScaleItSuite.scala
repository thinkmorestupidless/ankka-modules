package io.eitheror.casino.wallet.it

import com.thinkmorestupidless.ankka.core.Serializer
import io.eitheror.casino.wallet.entity.*

import com.thinkmorestupidless.ankka.http.Caller

import java.time.Instant
import java.util.concurrent.{ConcurrentLinkedQueue, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

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

  /**
   * SC-002: 500 reserve, release, settle and resolution payment commands a second across 50 wallets
   * for 20 seconds, over HTTP. Each wallet runs a cycle of the four, one command every 100 ms: reserve
   * 100 for an order, release 20 of it, settle 50 of it to the next wallet with the 30 left released,
   * and take a payment of 10 from the pool account of a market of its own, which it seeded.
   */
  test("SC-002: reserve, release, settle and payment at 500 a second over 50 wallets, median under 20 ms and p99 under 100 ms".tag(
    if perf then new munit.Tag("perf") else munit.Ignore)) {
    val exchange = Caller.Service(Harness.Project, "exchange")
    val wallets  = (0 until 50).map(i => s"sc2-$i")
    def market(w: String) = s"sc2-m-$w" // a market per wallet: the load is across wallets, not on one pool
    wallets.foreach { w =>
      testKit.componentClient.forEventSourcedEntity(WalletEntity.id(w, "EUR")).call(WalletEntity.openMigrated)
        .invoke(OpenMigrated("open", 10_000_000L, 0, Vector.empty, 0, 0, 0, t0)): Unit
      val (status, body) = send("POST", s"/v1/casino/brands/$brand/accounts/pool/${market(w)}/splits",
        Some(s"""{"key":"seed-$w","currency":"EUR","player":"$w","amount":100000}"""), as = exchange)
      assertEquals(status, 200, body)
    }
    val seconds = 20
    val period  = 100L // ms between one wallet's commands: 50 wallets at 10 a second each
    val times   = ConcurrentLinkedQueue[(String, Double)]()
    val refused = ConcurrentLinkedQueue[String]()
    val pool    = Executors.newFixedThreadPool(wallets.size)
    val start   = System.nanoTime()
    wallets.zipWithIndex.foreach { (w, i) =>
      pool.execute { () =>
        val next = wallets((i + 1) % wallets.size)
        (0 until (seconds * 1000 / period).toInt).foreach { n =>
          val due  = start + n * period * 1000000L
          val wait = (due - System.nanoTime()) / 1000000L
          if wait > 0 then Thread.sleep(wait)
          val order = s"$w-o${n / 4}"
          val (kind, path, body) = n % 4 match
            case 0 => ("reserve", s"${wallet(w)}/reservations", s"""{"key":"$order","amount":100}""")
            case 1 => ("release", s"${wallet(w)}/reservations/release", s"""{"orderId":"$order","sequence":1,"amount":20}""")
            case 2 => ("settle", s"/v1/casino/brands/$brand/settlements",
                s"""{"tradeId":"$order-t","currency":"EUR","buyer":"$w","orderId":"$order","seller":"$next","amount":50,"unused":30}""")
            case _ => ("payment", s"/v1/casino/brands/$brand/accounts/pool/${market(w)}/payments",
                s"""{"positionId":"$order","currency":"EUR","player":"$w","amount":10}""")
          val sent = System.nanoTime()
          val (status, reply) = send("POST", path, Some(body), as = exchange)
          times.add((kind, (System.nanoTime() - sent) / 1000000.0))
          if status != 200 then refused.add(s"$kind $order: $status $reply")
        }
      }
    }
    pool.shutdown()
    assert(pool.awaitTermination(seconds + 60L, TimeUnit.SECONDS), "the load did not finish")
    val elapsed = (System.nanoTime() - start) / 1e9
    assert(refused.isEmpty, refused.asScala.take(5).mkString("\n"))
    def figures(all: Vector[Double]) =
      val sorted = all.sorted
      (sorted(sorted.size / 2), sorted((sorted.size * 99) / 100))
    val all = times.asScala.toVector
    all.groupBy(_._1).toVector.sortBy(_._1).foreach { (kind, ts) =>
      val (m, p) = figures(ts.map(_._2))
      println(f"SC-002 $kind%-8s ${ts.size}%,6d commands: median $m%.1f ms, p99 $p%.1f ms")
    }
    val (median, p99) = figures(all.map(_._2))
    println(f"SC-002 all ${all.size}%,d commands in $elapsed%.1f s (${all.size / elapsed}%.0f a second): median $median%.1f ms, p99 $p99%.1f ms")
    assert(all.size / elapsed >= 450, f"the load ran at ${all.size / elapsed}%.0f a second, short of 500")
    assert(median < 20 && p99 < 100, s"median $median ms, p99 $p99 ms")
  }
