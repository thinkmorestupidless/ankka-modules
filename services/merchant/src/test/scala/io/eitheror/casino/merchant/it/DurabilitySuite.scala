package io.eitheror.casino.merchant.it

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import io.eitheror.casino.merchant.deposits.DepositFlow
import io.eitheror.casino.merchant.payments.Status

import scala.concurrent.duration.*

/** What a restart cannot lose: a session, a payment, and a credit waiting for the wallet. */
class DurabilitySuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout: Duration = 300.seconds
  private var kit: Kit = null
  override def beforeAll(): Unit = kit = Kit()
  override def afterAll(): Unit  = if kit != null then kit.stop()

  test("a credit waiting for the wallet survives a restart and credits once when the wallet returns") {
    val r  = kit.subBrand()
    val id = kit.payment(r)
    kit.wallet.down = true
    assertEquals(kit.capture(id).status, 503)
    kit.eventually("waiting")(Option(kit.flow(id)).filter(_.status == DepositFlow.CreditWaiting))
    kit.testKit.restartService()
    kit.wallet.down = false
    kit.eventually("credited after the restart", 60.seconds)(Option(kit.flow(id)).filter(_.status == DepositFlow.Credited))
    assertEquals(kit.wallet.depositCount("acme", kit.paymentState(id).opened.get.playerId, "EUR"), 1)
  }

  test("a session and a payment awaiting their outcome survive a restart") {
    val r     = kit.subBrand()
    val s     = kit.session(r)
    val token = s.str("session_token")
    kit.testKit.restartService()
    val p = kit.submit(token)
    assertEquals(p.status, 200, p.text)
    val id = p.str("paymentId")
    kit.testKit.restartService()
    assertEquals(kit.paymentState(id).status, Status.AwaitingRedirect)
    assertEquals(kit.capture(id).status, 200)
  }
