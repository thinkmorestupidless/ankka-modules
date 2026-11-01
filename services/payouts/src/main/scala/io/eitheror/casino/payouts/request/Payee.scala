package io.eitheror.casino.payouts.request

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.payouts.PayoutsCodecs

/** Gigadat's two methods (R4 2a): an Interac e-Transfer to a mobile number, or a bank transfer. */
enum Method(val wire: String):
  case Eto extends Method("ETO")
  case Ach extends Method("ACH")

object Method:
  def from(text: String): Option[Method] = values.find(_.wire.equalsIgnoreCase(text.trim))
  given JsonValueCodec[Method]          = PayoutsCodecs.wire(values, _.wire)

/** A destination's holder names, as the destination stood when the player asked. */
final case class Holder(givenName: Personal[String], familyName: Personal[String])

/**
 * Whom a request pays (data-model.md): copied onto the request when it is made, so a later deletion of
 * the destination changes nothing in flight. Everything that names a person or an account is `Personal`.
 */
final case class Payee(
    destinationId: Option[String] = None,
    holder: Option[Holder] = None,
    iban: Option[Personal[String]] = None,
    walletAddress: Option[Personal[String]] = None,
    coin: Option[String] = None,
    method: Option[Method] = None,
    mobile: Option[Personal[String]] = None,
    account: Option[Personal[String]] = None,
    institution: Option[Personal[String]] = None,
    transit: Option[Personal[String]] = None
):
  /** Every `Personal` value blanked; the destination's id, the coin and the method kept (R12). */
  def erased: Payee = Payee(destinationId = destinationId, coin = coin, method = method)
