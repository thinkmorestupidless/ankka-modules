package io.eitheror.casino.merchant.deposits

import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, LogCapturing}
import io.eitheror.casino.merchant.payments.*
import io.eitheror.casino.merchant.wallet.*

import java.time.Instant
import scala.collection.mutable

/** FR-022: the initiated outcome reaches the brand from the payment's events; a brandless payment reports nothing. */
class OutcomeReporterSuite extends munit.FunSuite with LogCapturing:

  private val at = Instant.parse("2026-10-12T10:00:00Z")
  private def opened(brand: Option[String]) = Opened("sb-1", brand, "acme:p1", "p1", 5000, "EUR", "DREAMZPAY", "VISA", "main", "tx",
    "psp-1", "https://gw", None, at, at.plusSeconds(1800))

  private val reports = mutable.ArrayBuffer.empty[OutcomeReport]
  @volatile private var acknowledge = true

  override def beforeEach(context: BeforeEach): Unit =
    reports.clear(); acknowledge = true
    DepositRuntime.wallet = _ => new WalletClient:
      def credit(c: Credit): CreditAnswer = CreditAnswer.Retry("unused")
      def report(r: OutcomeReport): Either[String, Boolean] =
        if acknowledge then { reports += r; Right(true) } else Left("down")

  override def afterAll(): Unit = DepositRuntime.wallet = WalletClient.http

  test("an initiated payment is reported keyed by the payment and the outcome, with the provider, amount and currency") {
    ConsumerTestKit.of(OutcomeReporter).onMessage(PaymentEvent.Initiated(opened(Some("acme")), Status.AwaitingRedirect), subject = "pay-1", sequenceNumber = 1): Unit
    assertEquals(reports.toVector, Vector(OutcomeReport("acme", "pay-1", "initiated", "p1", "DREAMZPAY", Some("main"), Some(5000L), Some("EUR"), None, None)))
  }

  test("a payment of a sub-brand that names no brand reports nothing") {
    ConsumerTestKit.of(OutcomeReporter).onMessage(PaymentEvent.Initiated(opened(None), Status.AwaitingRedirect), subject = "pay-2", sequenceNumber = 1): Unit
    assertEquals(reports.size, 0)
  }

  test("a report the wallet does not acknowledge fails the handler, so the event is delivered again") {
    acknowledge = false
    intercept[Throwable](ConsumerTestKit.of(OutcomeReporter).onMessage(PaymentEvent.Initiated(opened(Some("acme")), Status.AwaitingRedirect),
      subject = "pay-3", sequenceNumber = 1))
  }
