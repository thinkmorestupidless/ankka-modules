package io.eitheror.casino.exchange.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.predictions.exchange.*
import io.eitheror.casino.exchange.Json
import io.eitheror.casino.exchange.retry.{RetryState, SettlementRetry}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * The settlement retry (T028, T029) over Postgres with the backoff at half a second: a refused
 * settlement is retried under its key until the wallet moves it, once, with a record at the refusal
 * and at the end; an operator with the markets permission abandons one, and no other operator can.
 */
class RetryItSuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  private def kit = SharedKit.kit
  override def beforeEach(context: BeforeEach): Unit = kit.reset()

  private def book() =
    val m = s"retry-${UUID.randomUUID().toString.take(8)}"
    assertEquals(kit.open(m, Vector("yes", "no"), "EUR").status, 200)
    m

  private def direct(m: String, trade: String) =
    kit.wallet.real.put("ada", 1000L); kit.wallet.real.put("bob", 0L)
    kit.wallet.reservations.put(s"$m/o-$trade", "ada" -> 600L)
    val legs = Vector(Leg(OwnerId("ada"), OrderId(s"o-$trade"), OutcomeId("yes"), Side.Buy, 600, 0, 0, 0),
      Leg(OwnerId("bob"), OrderId(s"s-$trade"), OutcomeId("yes"), Side.Sell, 0, 600, 0, 0))
    kit.exchange.ledger.settle(MarketId(m), TradeId(trade), Through.Direct, legs)

  /** A retry runs under its call's key, which names the market (research R4), with `~` for `/`. */
  private def state(key: String): RetryState =
    kit.components.forWorkflow(com.thinkmorestupidless.ankka.core.EntityId(key.replace('/', '~'))).call(SettlementRetry.get).invoke()

  test("a refused settlement is retried under its key until the wallet moves it, once") {
    val m = book(); val trade = s"t-${UUID.randomUUID().toString.take(6)}"; val key = s"$m/$trade"
    kit.wallet.script.put("settlements", List(404 -> """{"error":"WALLET_NOT_FOUND"}""", 404 -> """{"error":"WALLET_NOT_FOUND"}"""))
    assertEquals(direct(m, trade), com.thinkmorestupidless.predictions.exchange.components.LedgerAnswer.Refused("WALLET_NOT_FOUND"))
    kit.eventually("the retry succeeded")(Option.when(state(key).status == SettlementRetry.Succeeded)(()))
    assertEquals(state(key).attempts, 3)
    assertEquals(kit.wallet.calls("settlements").map(_._1).distinct, Vector(key))
    assertEquals(kit.wallet.real.get("bob"), 600L)
    val records = kit.eventually("both records") {
      val mine = kit.audit.filter(_.subjectId == key)
      Option.when(mine.size >= 2)(mine)
    }
    assertEquals(records.map(_.action), Vector("exchange.settlement_refused", "exchange.settlement_succeeded"))
    assertEquals(records.head.reasonCode, Some("WALLET_NOT_FOUND"))
  }

  test("an operator with the markets permission abandons a retry; another operator is refused") {
    val m = book(); val trade = s"t-${UUID.randomUUID().toString.take(6)}"; val key = s"$m/$trade"
    kit.wallet.script.put("settlements", List.fill(1000)(409 -> """{"error":"RESERVATION_SHORT"}"""))
    direct(m, trade): Unit
    val body = Json.obj("abandon" -> true, "reason" -> "settled by hand")
    val refused = kit.json("POST", kit.operatorPath(s"/retries/${key.replace('/', '~')}"), body, kit.asOperator("op-2", s"brand_${kit.slug}"))
    assertEquals(refused.status, 403, refused.text)
    val r = kit.json("POST", kit.operatorPath(s"/retries/${key.replace('/', '~')}"), body, kit.asOperator())
    assertEquals(r.status, 200, r.text)
    assertEquals(r.str("status"), "abandoned")
    val records = kit.eventually("the abandoning recorded") {
      val mine = kit.audit.filter(_.subjectId == key)
      Option.when(mine.exists(_.action == "exchange.settlement_abandoned"))(mine)
    }
    val abandoned = records.find(_.action == "exchange.settlement_abandoned").get
    assertEquals((abandoned.actorKind, abandoned.actorId), ("operator", "op-1"))
    assert(!abandoned.changes.exists(_.after.contains("settled by hand")), abandoned.changes.toString)
    val attempts = state(key).attempts
    Thread.sleep(1500)
    assertEquals(state(key).attempts, attempts)
  }
