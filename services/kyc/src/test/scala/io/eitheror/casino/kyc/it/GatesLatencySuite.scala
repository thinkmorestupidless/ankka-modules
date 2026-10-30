package io.eitheror.casino.kyc.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.kyc.Json

import java.util.UUID

/**
 * SC-003 and SC-006: each gate answers 1,000 asks with a p99 under 50 ms, calling nothing outside the
 * service (neither `players` nor the store hears of it), and a policy change is seen by the very next ask.
 */
class GatesLatencySuite extends munit.FunSuite with LogCapturing:
  override def munitTimeout = scala.concurrent.duration.Duration(300, "s")

  private lazy val kit = SharedKit.kit
  private var player   = ""

  private var store: CountingStore = null

  override def beforeEach(context: BeforeEach): Unit =
    store = CountingStore(kit.store)
    kit.reset(store)
    player = s"p-${UUID.randomUUID().toString.take(8)}"

  override def afterEach(context: AfterEach): Unit = kit.reset()

  private def profile(name: String): Unit =
    assertEquals(kit.json("POST", kit.operatorPath("/policy/profile"), Json.obj("profile" -> name), kit.asOperator()).status, 200)

  private def depositCheck(): Reply =
    kit.json("POST", kit.servicePath("/deposit-check"), Json.obj("playerId" -> player, "amount" -> 5000L, "currency" -> "EUR"), kit.asService("players"))

  private def playCheck(): Reply = kit.request("GET", kit.servicePath(s"/$player/play-check"), None, kit.asService("game-provider"))

  /** The p99 of `n` asks, in milliseconds, after a warm-up; every ask answered 200. */
  private def p99(n: Int)(ask: () => Reply): Double =
    (1 to 100).foreach(_ => ask(): Unit)
    val millis = Vector.fill(n) {
      val start = System.nanoTime()
      val r     = ask()
      val took  = (System.nanoTime() - start) / 1e6
      assertEquals(r.status, 200, r.text)
      took
    }.sorted
    millis((n * 99) / 100 - 1)

  test("the deposit check answers 1,000 asks with a p99 under 50 ms and calls nothing outside the service"):
    profile("MGA")
    val p = p99(1000)(() => depositCheck())
    assert(p < 50, s"p99 $p ms")
    assertEquals((kit.players.calls, store.calls), (Vector.empty, Vector.empty))

  test("the play check answers 1,000 asks with a p99 under 50 ms and calls nothing outside the service"):
    profile("UKGC")
    val p = p99(1000)(() => playCheck())
    assert(p < 50, s"p99 $p ms")
    assertEquals((kit.players.calls, store.calls), (Vector.empty, Vector.empty))

  test("a policy change is seen by the next ask"):
    profile("CURACAO")
    assertEquals(playCheck().json.get("allowed").asBoolean, true)
    profile("UKGC")
    assertEquals(playCheck().json.get("allowed").asBoolean, false)
    assertEquals(depositCheck().str("code"), "DEPOSIT_BLOCKED_KYC")
    profile("CURACAO")
    assertEquals(playCheck().json.get("allowed").asBoolean, true)
    assertEquals(depositCheck().json.get("allowed").asBoolean, true)
