package io.eitheror.casino.pspgateway.payouts

import io.eitheror.casino.pspgateway.Json
import io.eitheror.casino.pspgateway.deposits.Answer
import io.eitheror.casino.pspgateway.merchant.{MerchantClient, Terminal}
import io.eitheror.casino.pspgateway.providers.{Catalogue, Operation}
import io.eitheror.casino.pspgateway.sessions.Provider

/**
 * A brand's withdrawal terminals (FR-009a, contracts/payouts.md): `merchant`'s brand listing, those with
 * `supportsWithdrawal`, each with its coins and what the catalogue knows of its provider. A currency
 * narrows them to the providers that pay out in it; `payouts` applies its own eligibility rule.
 */
final class Terminals(merchant: MerchantClient):
  def apply(brand: String, currency: Option[String]): Answer =
    merchant.brandTerminals(brand) match
      case Left(_) => Answer.refusal(503, "unavailable", Some("merchant could not be read"))
      case Right(listed) =>
        val rows = listed.filter(_.supportsWithdrawal).filter(t => currency.forall(Terminals.paysOutIn(t, _))).map(Terminals.row)
        Answer(200, Json.obj("terminals" -> Json.arr(rows*)))

object Terminals:
  def paysOutIn(t: Terminal, currency: String): Boolean =
    Provider.from(t.gateway).forall { p =>
      io.eitheror.casino.contract.money.Currency.from(currency.toUpperCase).toOption
        .forall(c => Catalogue.check(p, Operation.Payout, t.scheme, c).left.forall(_.error != "unsupported_currency"))
    }

  def row(t: Terminal): com.fasterxml.jackson.databind.node.ObjectNode =
    val entry = Provider.from(t.gateway).map(Catalogue(_))
    // The original's `supportedCurrencies(terminal)` (casino-service WithdrawalTerminalEligibility.scala:177):
    // comma-joined, trimmed, upper-cased, without blanks or repeats; empty is no restriction.
    val coins = t.properties.get("supportedCurrencies").toVector.flatMap(_.split(',')).map(_.trim.toUpperCase)
      .filter(_.nonEmpty).distinct
    Json.obj("id" -> t.id, "subBrandId" -> t.subBrandId, "name" -> t.name, "scheme" -> t.scheme, "gateway" -> t.gateway,
      "active" -> t.status.equalsIgnoreCase("active"), "supportsWithdrawal" -> t.supportsWithdrawal,
      "requiresPayoutDestination" -> t.requiresPayoutDestination, "coins" -> Json.arr(coins*),
      "hasStatus" -> entry.exists(_.hasStatus), "reviewWindow" -> entry.flatMap(_.reviewWindow).map(_.toString))
