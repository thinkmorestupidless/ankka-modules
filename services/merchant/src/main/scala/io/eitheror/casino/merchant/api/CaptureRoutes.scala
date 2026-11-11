package io.eitheror.casino.merchant.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.contract.facts.DisputeOutcome
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.api.Answers.*
import io.eitheror.casino.merchant.cashier.Commands
import io.eitheror.casino.merchant.deposits.*
import io.eitheror.casino.merchant.payments.*
import io.eitheror.casino.merchant.security.{Hmac, Secrets}

import scala.util.Try

/**
 * The gateway's capture and fail (FR-018, FR-019, FR-021, FR-025, FR-026; contracts/gateway.md), in
 * the original's order: the payment is read first, so an unknown one is acknowledged and ignored; the
 * provider's signature is checked over the raw body before the workflow is touched; a variance that
 * cannot be trusted refuses the capture; and the capture is acknowledged only once the brand's wallet
 * has answered, else `503 OUTCOME_PENDING` keeps it undelivered at the gateway.
 *
 * An acknowledgement is the original's `200 {"ok": true}` whatever it acknowledges (`completeOutcome`),
 * and a variance it cannot trust its `503 {"error": <the reason>}`.
 */
final class CaptureRoutes(ctx: Ctx):

  private val acknowledged = ok(Json.obj("ok" -> true))

  private def payment(id: String) = Try(ctx.components.forEventSourcedEntity(EntityId(id)).call(Payment.get).invoke())

  def capture(paymentId: String, raw: String, signature: Option[String], timestamp: Option[String]): Respond[Bytes] =
    payment(paymentId).fold(e => refuse(503, "payment_unavailable", e.getMessage), p =>
      if !p.exists then acknowledged
      else
        val o = p.opened.get
        webhookSecret(o.subBrandId, o.psp) match
          case Left(why) => refuse(503, "auth_requirement_unavailable", "Could not determine signature requirement; retry this callback")
          case Right(secret) =>
            Hmac.verify(raw, signature, timestamp, secret, ctx.now()) match
              case Hmac.Outcome.Refused(status, error, message) => refuse(status, error, message)
              case Hmac.Outcome.Unavailable(error, message)     => refuse(503, error, message)
              case _ => accepted(paymentId, p, raw)
    )

  /** The credential's `webhookSecret`, none when the provider has no credential with one, or the store's failure. */
  private def webhookSecret(subBrandId: String, provider: String): Either[String, Option[String]] =
    ctx.subBrand(subBrandId).usableCredential(provider) match
      case None    => Right(None)
      case Some(c) => ctx.secrets.credential(c.id).map(_.get(Secrets.WebhookSecret)).left.map(_.reason)

  private def accepted(paymentId: String, p: PaymentState, raw: String): Respond[Bytes] =
    val body = Json.parseObject(raw).getOrElse(Json.obj())
    val o    = p.opened.get
    val checked = for
      variance <- Variance.fromFields(body.text)
      credited <- Variance.credited(o.amount, Currency.unsafe(o.currency), variance)
    yield CaptureInput(variance, credited, ctx.now())
    checked match
      case Left(reason) =>
        // Refused, not acknowledged: the gateway keeps the outcome undelivered for an operator (FR-026).
        refuse(503, reason)
      case Right(input) =>
        if p.status == Status.Failed || p.status == Status.Expired then acknowledged
        else
          val flow = ctx.components.forWorkflow(EntityId(paymentId))
          flow.call(DepositWorkflow.capture).invoke(input): Unit
          // A failure or an expiry that won the race is final: the capture is acknowledged and changes nothing (Edge cases).
          val ended = Set(DepositFlow.Failed, DepositFlow.Expired)
          Commands.await(flow.call(DepositWorkflow.status).invoke())(f => DepositFlow.CaptureSettled(f.status) || ended(f.status)) match
            case Some(_) => acknowledged
            case None    => refuse(503, "OUTCOME_PENDING", "The wallet has not answered yet; deliver the capture again")

  /**
   * A dispute the provider reported (spec 015 R11): `outcome` `dispute_opened`, with the amount and
   * currency (the deposit's where the gateway names none) and a reason; or `dispute_closed`, with
   * `disputeOutcome` `won` or `lost` and the amount and currency where the provider gave them. Recorded on
   * the payment whatever its status, and reported to the brand by the outcome consumer (FR-022). An
   * unknown payment is acknowledged and ignored, as a failure is (FR-019); a close with any other outcome is
   * refused `400 invalid_outcome`, so the gateway records it refused rather than the brand's wallet
   * refusing it for ever. An amount that is given and not positive is refused `400 INVALID_REQUEST`, as a
   * currency that is not one is, here rather than at the wallet's fact writer.
   */
  def dispute(paymentId: String, raw: String): Respond[Bytes] =
    payment(paymentId).fold(e => refuse(503, "payment_unavailable", e.getMessage), p =>
      if !p.exists then acknowledged
      else
        val b = Json.parseObject(raw).getOrElse(Json.obj())
        val o = p.opened.get
        def currency(c: Option[String])(f: Option[String] => Respond[Bytes]) =
          c match
            case Some(code) if Currency.from(code).isLeft => refuse(400, "INVALID_REQUEST", s"'$code' is not a currency")
            case _                                        => f(c)
        def amount(f: Option[Long] => Respond[Bytes]) =
          (b.has("amount"), b.long("amount")) match
            case (true, Some(a)) if a > 0 => f(Some(a))
            case (true, _)                => refuse(400, "INVALID_REQUEST", s"a dispute's amount is a positive number of minor units, not '${b.get("amount")}'")
            case (false, _)               => f(None)
        b.text("outcome") match
          case Some(CaptureRoutes.DisputeOpened) =>
            amount { a =>
              currency(b.text("currency").orElse(Some(o.currency))) { c =>
                val cmd = RecordDisputeOpened(a.getOrElse(o.amount), c.get, b.text("reason"), ctx.now())
                ctx.components.forEventSourcedEntity(EntityId(paymentId)).call(Payment.disputeOpened).invoke(cmd): Unit
                acknowledged
              }
            }
          case Some(CaptureRoutes.DisputeClosed) =>
            b.text("disputeOutcome") match
              case Some(v) if DisputeOutcome.values(v) =>
                amount { a =>
                  currency(b.text("currency")) { c =>
                    val cmd = RecordDisputeClosed(v, a, c, ctx.now())
                    ctx.components.forEventSourcedEntity(EntityId(paymentId)).call(Payment.disputeClosed).invoke(cmd): Unit
                    acknowledged
                  }
                }
              case other => refuse(400, "invalid_outcome", s"a closed dispute's disputeOutcome is won or lost, not '${other.getOrElse("")}'")
          case other => refuse(400, "INVALID_REQUEST", s"'${other.getOrElse("")}' is not a dispute outcome")
    )

  def fail(paymentId: String, raw: String): Respond[Bytes] =
    payment(paymentId).fold(e => refuse(503, "payment_unavailable", e.getMessage), p =>
      if !p.exists then acknowledged
      else if p.terminal then acknowledged
      else
        val b        = Json.parseObject(raw).getOrElse(Json.obj())
        val received = Received(b.text("receivedCryptoAmount"), b.text("receivedConvertAmount"), b.text("receivedConvertCurrency"))
        val input    = FailInput(b.text("failureCode"), b.text("failureReason").getOrElse(FailureReason.Unknown),
          Option.when(received != Received())(received), ctx.now())
        val flow = ctx.components.forWorkflow(EntityId(paymentId))
        flow.call(DepositWorkflow.fail).invoke(input): Unit
        Commands.await(flow.call(DepositWorkflow.status).invoke())(f => f.status != DepositFlow.Failing) match
          case Some(_) => acknowledged
          case None    => refuse(503, "OUTCOME_PENDING", "The failure is still being recorded; deliver it again")
    )

object CaptureRoutes:
  /** The outcome words of a dispute, as the brand's wallet's outcome route takes them (spec 002 FR-022a). */
  val DisputeOpened = "dispute_opened"
  val DisputeClosed = "dispute_closed"
