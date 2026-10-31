package io.eitheror.casino.payouts.clients

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.contract.money.Currency
import io.eitheror.casino.payouts.{Json, Settings}
import io.eitheror.casino.payouts.Json.*
import io.eitheror.casino.payouts.request.{Preview, Refusal, Refusals}

/** What the hold moved (the wallet's `MoneyReply`): the amount paid after a cap's void, and the forfeit. */
final case class Held(paidAmount: Long, voidedAmount: Long, clawbackAmount: Long, bonusForfeited: Boolean, txnId: Option[String])

/** A money step's answer (R9): done, refused by the wallet with its code, or not answered at all. */
enum Money[+A]:
  case Done(value: A)
  case Refused(status: Int, code: String, message: String)
  case Unreachable(reason: String)

/**
 * The brand's wallet, for the four money steps and the preview (contracts/routes.md, *Calls out*). Each
 * step is keyed `<request id>/<step>` but the hold, whose key is the request id: the wallet takes it as
 * the request's id (`WalletEntity.scala:525`).
 */
trait Wallet:
  def preview(player: String, currency: Currency, amount: Long): Either[Refusal, Preview]
  def hold(player: String, currency: Currency, requestId: String, amount: Long, confirmed: Boolean): Money[Held]
  def release(player: String, currency: Currency, requestId: String, outcome: String): Money[Unit]
  def confirm(player: String, currency: Currency, requestId: String): Money[Unit]
  def reverse(player: String, currency: Currency, requestId: String): Money[Unit]

object Wallet:
  def http(services: ServiceClients, settings: Settings): Wallet = Http(Neighbour(services, settings.wallet), settings.brand)

  /** A release or confirm the wallet already made answers `REQUEST_NOT_PENDING`: the step is done (R9). */
  val AlreadyDone = "REQUEST_NOT_PENDING"

  final class Http(wallet: Neighbour, brand: String) extends Wallet:
    private def base(player: String, currency: Currency) =
      s"/v1/casino/brands/${Neighbour.segment(brand)}/wallets/${Neighbour.segment(player)}/${currency.code}/holds"

    def preview(player: String, currency: Currency, amount: Long): Either[Refusal, Preview] =
      wallet.get(s"${base(player, currency)}/preview?amount=$amount") match
        case Reply.Answered(200, Some(b), _) =>
          Right(Preview(b.text("refusal"), b.bool("confirmationRequired").getOrElse(false), b.long("bonusBalance").getOrElse(0L),
            b.long("clawback").getOrElse(0L), b.long("totalForfeit").getOrElse(0L), b.long("mostWithdrawable").getOrElse(0L)))
        case Reply.Answered(404, _, _) => Left(Refusals.WalletNotFound)
        case _                         => Left(Refusals.Unavailable)

    def hold(player: String, currency: Currency, requestId: String, amount: Long, confirmed: Boolean): Money[Held] =
      money(wallet.post(base(player, currency), Json.obj("key" -> requestId, "amount" -> amount, "playerConfirmed" -> confirmed))) { b =>
        Held(b.long("paidAmount").getOrElse(amount), b.long("voidedAmount").getOrElse(0L), b.long("clawbackAmount").getOrElse(0L),
          b.strings("forfeited").nonEmpty, b.text("txnId"))
      }

    def release(player: String, currency: Currency, requestId: String, outcome: String): Money[Unit] =
      step(wallet.post(s"${base(player, currency)}/release",
        Json.obj("key" -> s"$requestId/release", "requestId" -> requestId, "outcome" -> outcome)))

    def confirm(player: String, currency: Currency, requestId: String): Money[Unit] =
      step(wallet.post(s"${base(player, currency)}/confirm", Json.obj("key" -> s"$requestId/confirm", "requestId" -> requestId)))

    /** A reverse of a reversed request answers `idempotent: true`, which is done. */
    def reverse(player: String, currency: Currency, requestId: String): Money[Unit] =
      step(wallet.post(s"${base(player, currency)}/reverse", Json.obj("key" -> s"$requestId/reverse", "requestId" -> requestId)))

    private def step(reply: Reply): Money[Unit] = money(reply)(_ => ()) match
      case Money.Refused(_, AlreadyDone, _) => Money.Done(())
      case other                            => other

    private def money[A](reply: Reply)(read: JsonNode => A): Money[A] = reply match
      case Reply.Answered(s, Some(b), _) if s / 100 == 2 => Money.Done(read(b))
      case Reply.Answered(s, _, _) if s / 100 == 2        => Money.Done(read(Json.obj()))
      case Reply.Answered(s, Some(b), _) if s / 100 == 4 && b.text("error").isDefined =>
        Money.Refused(s, b.text("error").get, b.textOr("message", ""))
      case Reply.Answered(s, _, _) => Money.Unreachable(s"wallet answered $s")
      case Reply.Unreachable(why)  => Money.Unreachable(why)
