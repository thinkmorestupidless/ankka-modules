package io.eitheror.casino.ingest.api

import com.thinkmorestupidless.ankka.http.openapi.Description

/**
 * What the ticket's routes take and answer, for their descriptions (spec 031,
 * contracts/descriptions.md). Ingest passes `merchant`'s answers through as it sent them (FR-011),
 * so these mirror merchant's `Commands.submit` and `PaymentRead.json`, and ingest's own
 * `Answers.refusal`; they exist only to give the document their shapes. A field merchant writes for
 * some payments only (a capture, a failure, a crypto payment) is optional.
 */
private[api] object Described:

  /** Ingest's refusal and merchant's alike: the code, and whichever of the rest the refusal names. */
  final case class Refusal(error: String, message: Option[String], field: Option[String], transactionId: Option[String],
      retryAfterSeconds: Option[Long])

  /** The submit's body: the terminal chosen, the coin for a crypto terminal, and any customer detail entered. */
  final case class Submit(selectedTerminalEntityId: String, cryptoCurrency: Option[String],
      customerFirstName: Option[String], customerLastName: Option[String], customerPhone: Option[String],
      customerMobile: Option[String], customerProvince: Option[String], customerAddress: Option[String],
      customerCity: Option[String], customerPostCode: Option[String], customerState: Option[String])

  /** merchant's answer to a submit once the gateway has initiated the payment. */
  final case class Submitted(paymentId: String, status: String, paymentAddress: Option[String], cryptoAmount: Option[String],
      cryptoCurrency: Option[String], validUntil: Option[String])

  /**
   * merchant's payment read (`PaymentRead.json`), relayed. Ingest vouches for the status only: the
   * rest are merchant's to send, and a client reading more than the status (the SDK reads the status,
   * the redirect and the crypto fields) takes each as it comes.
   */
  final case class Payment(
      status: String,
      merchantId: Option[String],
      customerRef: Option[String],
      amount: Option[String],
      amountCents: Option[Long],
      currency: Option[String],
      psp: Option[String],
      scheme: Option[String],
      terminalId: Option[String],
      createdAt: Option[String],
      expiresAt: Option[String],
      pspTransactionId: Option[String],
      redirectUrl: Option[String],
      capturedAt: Option[String],
      failedAt: Option[String],
      expiredAt: Option[String],
      failureCode: Option[String],
      failureReason: Option[String],
      waitingReason: Option[String],
      originalAmountCents: Option[Long],
      originalAmount: Option[String],
      variance: Option[String],
      receivedCryptoAmount: Option[String],
      receivedConvertAmount: Option[String],
      receivedConvertCurrency: Option[String],
      paymentAddress: Option[String],
      cryptoAmount: Option[String],
      cryptoCurrency: Option[String],
      validUntil: Option[String]
  )

  val refusals: Description = Description.none.refusalShape[Refusal]
