package io.eitheror.casino.payouts.workflow

import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.request.{Method, Payee}
import io.eitheror.casino.payouts.terminals.Gateways

/**
 * What an approved request's terminal is, as held on the request: enough to decide the payout without
 * reading the listing again.
 */
final case class PayoutTerminal(id: String, subBrandId: String, gateway: String, scheme: String, supportsWithdrawal: Boolean)

/**
 * The method and its details as the gateway's payout takes them (005 contracts/payouts.md). The details
 * name the player and are sent, never stored or logged (FR-022).
 */
final case class Payout(subBrandId: String, method: String, details: Map[String, Personal[String]], customerId: String)

/**
 * Automated or manual, as the original's `resolveApprovalOutcome` (`WithdrawalRoutes.scala:1586-2282`;
 * R7). Automated when the terminal supports withdrawal, has a sub-brand, is one of the four gateways, and
 * the request carries what that gateway needs; otherwise awaiting manual payout, with the reason. Nothing
 * has reached a provider on the manual path, so it is always safe.
 */
object Approval:
  enum Decision:
    case Automated(payout: Payout)
    case Manual(reason: String)

  /** `names` and `email` are the player's, from `casino.players`. */
  def decide(terminal: PayoutTerminal, payee: Payee, playerId: String, names: Option[(Personal[String], Personal[String])],
      email: Option[Personal[String]]): Decision =
    import Decision.*
    def present(p: Option[Personal[String]]) = p.filter(v => !Personal.reveal(v).isBlank)
    val gateway = Gateways.of(terminal.gateway)
    if !terminal.supportsWithdrawal then Manual("terminal_not_withdrawal")
    else if terminal.subBrandId.isBlank then Manual("no_sub_brand")
    else if !Gateways.automated.contains(gateway) then Manual("no_automated_payout")
    else
      def automated(method: String, details: (String, Option[Personal[String]])*) =
        Automated(Payout(terminal.subBrandId, method, details.collect { case (k, Some(v)) => k -> v }.toMap, playerId))
      gateway match
        case Gateways.UnityFinance =>
          (payee.holder, present(payee.iban), present(email)) match
            case (Some(h), Some(iban), Some(mail)) =>
              automated(terminal.scheme, "firstName" -> Some(h.givenName), "lastName" -> Some(h.familyName),
                "iban" -> Some(iban), "email" -> Some(mail))
            case (None, _, _) | (_, None, _) => Manual("destination_missing")
            case _                           => Manual("email_missing")
        case Gateways.Cozeni | Gateways.Morphosis =>
          present(payee.walletAddress) match
            case None => Manual("wallet_address_missing")
            case Some(address) =>
              val coin = payee.coin.filter(_.nonEmpty).getOrElse(terminal.scheme)
              automated(coin, "coin" -> Some(Personal(coin)), "address" -> Some(address))
        case _ =>
          val (first, last) = names.map((g, f) => (Some(g), Some(f))).getOrElse((None, None))
          payee.method match
            case None => Manual("payout_method_missing")
            case Some(Method.Eto) =>
              present(payee.mobile) match
                case None => Manual("mobile_missing")
                case mobile =>
                  automated(Method.Eto.wire, "mobile" -> mobile, "email" -> present(email), "firstName" -> first,
                    "lastName" -> last)
            case Some(Method.Ach) =>
              (present(payee.account), present(payee.institution), present(payee.transit)) match
                case (a @ Some(_), i @ Some(_), t @ Some(_)) =>
                  automated(Method.Ach.wire, "account" -> a, "institution" -> i, "transit" -> t, "firstName" -> first,
                    "lastName" -> last)
                case _ => Manual("bank_fields_missing")
