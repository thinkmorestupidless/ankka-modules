package io.eitheror.casino.merchant.features

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}
import io.eitheror.casino.merchant.it.{Kit, Ready, Reply, SharedKit}
import io.eitheror.casino.merchant.security.Secrets

import java.time.Duration as JDuration
import scala.concurrent.duration.*

/**
 * A feature file of features/payments bound against the whole service (constitution VII): `ingest`'s
 * commands and the gateway's capture and fail through their routes by identity, an operator's through
 * a token, and every id a scenario names its own, since the database outlives scenarios.
 */
abstract class MerchantFeatures(file: String) extends GherkinSuite(s"../../features/payments/$file") with LogCapturing:
  override val munitTimeout: Duration = 300.seconds

  protected def kit: Kit = SharedKit.kit
  protected var ready: Ready     = null
  protected var paymentId        = ""
  protected var token            = ""
  protected var last: Reply      = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    kit.reset()
    Secrets.outage = None
    ready = null; paymentId = ""; token = ""; last = null

  override def afterEach(context: AfterEach): Unit =
    Secrets.outage = None
    super.afterEach(context)

  protected def shortDeadline(d: FiniteDuration = 1500.millis): Unit =
    kit.rewire(kit.settings.copy(paymentDeadline = JDuration.ofMillis(d.toMillis)))

  protected def paymentAwaiting(r: Ready = ready, amount: Long = 5000): Unit =
    val s = kit.session(r, amount)
    assertEquals(s.status, 200, s.text)
    token = s.str("session_token")
    val p = kit.submit(token)
    assertEquals(p.status, 200, p.text)
    paymentId = p.str("paymentId")

  protected def player: String = kit.paymentState(paymentId).opened.get.playerId
  protected def brand: String  = ready.brand.getOrElse("")
