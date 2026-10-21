package io.eitheror.casino.merchant.wallet

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.gateway.{Http, Reply}

/** The credit the deposit workflow asks of the brand's wallet (contracts/wallet.md; spec 002 FR-022). */
final case class Credit(brand: String, player: String, currency: String, paymentId: String, amount: Long, terminal: String,
    psp: String, scheme: String, paymentReference: String)

/** What the wallet did with a credit. */
enum CreditAnswer:
  case Credited(txnId: Option[String])
  /** Not answered, a 5xx, `OUTCOME_PENDING` or `RG_UNAVAILABLE`: ask again under the same payment id. */
  case Retry(answer: String)
  /** A refusal the wallet states as final: no such wallet, a currency mismatch, or responsible gambling's. */
  case Final(code: String, message: String, details: Map[String, String])

/** An outcome other than the capture, reported to the brand (FR-022; spec 002 FR-022a). */
final case class OutcomeReport(brand: String, paymentId: String, outcome: String, playerId: String, paymentProvider: String,
    terminal: Option[String], amount: Option[Long], currency: Option[String], code: Option[String], reason: Option[String])

/** The seam between the deposit and the brand's wallet (research R5); `FakeWallet` serves it in the suites. */
trait WalletClient:
  def credit(c: Credit): CreditAnswer
  /** Whether the brand recorded it: `Right(first)` when acknowledged, `Left(why)` to be retried. */
  def report(r: OutcomeReport): Either[String, Boolean]

object WalletClient:
  /** The refusals spec 002 states as final for a deposit (`contracts/wire.md`). */
  val FinalCodes: Set[String] = Set("WALLET_NOT_FOUND", "CURRENCY_MISMATCH", "EXCLUDED", "COOLING_OFF", "OVER_LIMIT")

  /** The brand's wallet, in the brand's project, over HTTP as `payments/merchant` under the brand's grant. */
  def http(services: ServiceClients): WalletClient = HttpWallet(services)

  def classify(reply: Reply): CreditAnswer = reply match
    case Reply.Unreachable(reason)                     => CreditAnswer.Retry(reason)
    case Reply.Answered(s, body, _) if s / 100 == 2   => CreditAnswer.Credited(body.flatMap(_.text("txnId")))
    case Reply.Answered(s, body, text) =>
      val code = body.flatMap(_.text("error")).getOrElse(s"HTTP_$s")
      if FinalCodes(code) then CreditAnswer.Final(code, body.flatMap(_.text("message")).getOrElse(""), details(body))
      else CreditAnswer.Retry(s"$s $code: ${body.flatMap(_.text("message")).getOrElse(text.take(200))}")

  /** A refusal's named amounts and times (`OVER_LIMIT`'s `remaining`, `COOLING_OFF`'s `endsAt`), as text. */
  private def details(body: Option[JsonNode]): Map[String, String] =
    body.flatMap(b => Option(b.get("details"))).filter(_.isObject).map { d =>
      import scala.jdk.CollectionConverters.*
      d.properties.asScala.toVector.collect { case e if e.getValue.isValueNode => e.getKey -> e.getValue.asText }.toMap
    }.getOrElse(Map.empty)

private final class HttpWallet(services: ServiceClients) extends WalletClient:
  private def wallet(brand: String) = Http(services, "wallet", Some(brand))

  def credit(c: Credit): CreditAnswer =
    val path = s"/v1/casino/brands/${Http.segment(c.brand)}/wallets/${Http.segment(c.player)}/${Http.segment(c.currency)}/deposits"
    WalletClient.classify(wallet(c.brand).post(path, Json.obj("paymentId" -> c.paymentId, "amount" -> c.amount, "terminal" -> c.terminal,
      "psp" -> c.psp, "scheme" -> c.scheme, "paymentProvider" -> c.psp, "paymentReference" -> c.paymentReference)))

  def report(r: OutcomeReport): Either[String, Boolean] =
    val path = s"/v1/casino/brands/${Http.segment(r.brand)}/payments/${Http.segment(r.paymentId)}/outcome"
    wallet(r.brand).post(path, Json.obj("outcome" -> r.outcome, "playerId" -> r.playerId, "paymentProvider" -> r.paymentProvider,
      "terminal" -> r.terminal, "amount" -> r.amount, "currency" -> r.currency, "code" -> r.code, "reason" -> r.reason)) match
      case Reply.Answered(s, body, _) if s / 100 == 2 => Right(body.flatMap(_.bool("firstRecorded")).getOrElse(true))
      case Reply.Answered(s, _, text)                 => Left(s"the wallet answered $s: ${text.take(200)}")
      case Reply.Unreachable(reason)                  => Left(reason)
