package io.eitheror.casino.merchant.payments

import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.contract.money.{Amounts, Currency}
import io.eitheror.casino.merchant.Json

import java.time.Instant

/**
 * The payment read (FR-017, contracts/commands.md): the status in the frontend's words, the amount as a
 * decimal string beside `amountCents`, and every field the original answers. A payment past its
 * deadline with no outcome reads `EXPIRED` with "Payment not completed" before any timer has fired.
 */
object PaymentRead:
  def json(s: PaymentState, now: Instant): ObjectNode =
    val o         = s.opened.get
    val lazyGone  = s.lazilyExpired(now)
    val status    = if lazyGone then "EXPIRED" else Status.toApi(s.status)
    val currency  = Currency.from(o.currency).toOption
    val decimal   = (m: Long) => currency.map(c => Amounts.render(m, c)).getOrElse(m.toString)
    val r = Json.obj(
      "merchantId" -> o.subBrandId, "customerRef" -> o.customerId, "status" -> status,
      "amount" -> decimal(s.amount), "amountCents" -> s.amount, "currency" -> o.currency, "psp" -> o.psp, "scheme" -> o.scheme,
      "terminalId" -> o.terminalId, "pspTransactionId" -> o.pspTransactionId,
      "redirectUrl" -> o.redirectUrl, "createdAt" -> o.createdAt.toString, "expiresAt" -> o.deadline.toString,
      "capturedAt" -> s.capturedAt.map(_.toString), "failedAt" -> s.failedAt.map(_.toString), "expiredAt" -> s.expiredAt.map(_.toString),
      "failureCode" -> s.failureCode,
      "failureReason" -> (if lazyGone then Some(FailureReason.Abandoned) else s.failureReason),
      "waitingReason" -> s.waitingReason,
      "originalAmountCents" -> s.originalAmount, "originalAmount" -> s.originalAmount.map(decimal),
      "variance" -> s.variance.map(_.kind),
      "receivedCryptoAmount" -> s.variance.flatMap(_.receivedCryptoAmount).orElse(s.received.flatMap(_.receivedCryptoAmount)),
      "receivedConvertAmount" -> s.variance.flatMap(_.receivedConvertAmount).orElse(s.received.flatMap(_.receivedConvertAmount)),
      "receivedConvertCurrency" -> s.variance.flatMap(_.receivedConvertCurrency).orElse(s.received.flatMap(_.receivedConvertCurrency))
    )
    o.crypto.foreach { c =>
      Json.set(r, "paymentAddress", c.paymentAddress); Json.set(r, "cryptoAmount", c.cryptoAmount)
      Json.set(r, "cryptoCurrency", c.cryptoCurrency); Json.set(r, "validUntil", c.validUntil)
    }
    r
