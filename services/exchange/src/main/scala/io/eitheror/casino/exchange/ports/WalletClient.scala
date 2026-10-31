package io.eitheror.casino.exchange.ports

import com.thinkmorestupidless.ankka.sdk.ServiceClients
import com.thinkmorestupidless.predictions.exchange.components.LedgerAnswer
import io.eitheror.casino.exchange.Json
import io.eitheror.casino.exchange.Json.*

// The wallet's spec 022 routes (specs/022-wallet-primitives/contracts/routes.md) as the ledger port
// calls them; the shape of the client is game-provider's (…/clients/WalletClient.scala).

/** A wallet call that has no answer yet: unreachable, a 5xx or `OUTCOME_PENDING`. The caller retries. */
final class WalletPending(message: String) extends RuntimeException(message)

/**
 * The wallet's money routes for the exchange. Every call is keyed, so a repeat is answered as the
 * first was. A 2xx is `Done`; `INSUFFICIENT_FUNDS` is `Insufficient`; any other code is `Refused`
 * with it; no answer, a 5xx or `OUTCOME_PENDING` throws [[WalletPending]] (research R4).
 */
final class WalletClient(services: ServiceClients, brand: String):
  private val http = Http(services, "wallet")
  private val root = s"/v1/casino/brands/${Http.segment(brand)}"

  private def wallet(player: String, currency: String) = s"$root/wallets/${Http.segment(player)}/${Http.segment(currency)}"
  private def pool(market: String)                     = s"$root/accounts/pool/${Http.segment(market)}"

  def reserve(player: String, currency: String, key: String, amount: Long): LedgerAnswer =
    answer(http.post(s"${wallet(player, currency)}/reservations", Json.obj("key" -> key, "amount" -> amount)))

  def release(player: String, currency: String, order: String, sequence: Int, amount: Long): LedgerAnswer =
    answer(http.post(s"${wallet(player, currency)}/reservations/release",
      Json.obj("orderId" -> order, "sequence" -> sequence, "amount" -> amount)))

  def settle(trade: String, currency: String, buyer: String, order: String, seller: String, amount: Long, unused: Long): LedgerAnswer =
    answer(http.post(s"$root/settlements", Json.obj("tradeId" -> trade, "currency" -> currency, "buyer" -> buyer, "orderId" -> order,
      "seller" -> seller, "amount" -> amount, "unused" -> unused)))

  /** A buy leg of a split trade, charged from its order's reservation into the pool (022 FR-008a). */
  def split(market: String, key: String, currency: String, player: String, amount: Long, order: String, unused: Long): LedgerAnswer =
    answer(http.post(s"${pool(market)}/splits", Json.obj("key" -> key, "currency" -> currency, "player" -> player, "amount" -> amount,
      "orderId" -> order, "unused" -> unused)))

  def merge(market: String, key: String, currency: String, player: String, amount: Long): LedgerAnswer =
    answer(http.post(s"${pool(market)}/merges", Json.obj("key" -> key, "currency" -> currency, "player" -> player, "amount" -> amount)))

  def payment(market: String, position: String, currency: String, player: String, amount: Long): LedgerAnswer =
    answer(http.post(s"${pool(market)}/payments",
      Json.obj("positionId" -> position, "currency" -> currency, "player" -> player, "amount" -> amount)))

  private def answer(reply: Reply): LedgerAnswer = reply match
    case Reply.Unreachable(reason)                    => throw WalletPending(reason)
    case Reply.Answered(s, _, _) if s / 100 == 2      => LedgerAnswer.Done
    case Reply.Answered(s, body, text) =>
      val code = body.flatMap(_.text("error")).getOrElse(s"HTTP_$s")
      if s / 100 == 5 || code == "OUTCOME_PENDING" then throw WalletPending(s"the wallet answered $s $code: ${text.take(200)}")
      else if code == "INSUFFICIENT_FUNDS" then LedgerAnswer.Insufficient
      else LedgerAnswer.Refused(code)
