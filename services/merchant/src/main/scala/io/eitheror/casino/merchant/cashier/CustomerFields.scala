package io.eitheror.casino.merchant.cashier

import io.eitheror.casino.contract.personal.Personal

/**
 * The customer fields a payment provider requires for a deposit (FR-013, FR-032; the original's
 * `CustomerDetails`), each a `Personal` value held under the player's subject. They live on the
 * session and the payment's customer record only, never in an event (research R12).
 */
final case class CustomerFields(
    email: Option[Personal[String]] = None,
    firstName: Option[Personal[String]] = None,
    lastName: Option[Personal[String]] = None,
    phone: Option[Personal[String]] = None,
    mobile: Option[Personal[String]] = None,
    province: Option[Personal[String]] = None,
    ip: Option[Personal[String]] = None,
    country: Option[Personal[String]] = None,
    city: Option[Personal[String]] = None,
    address: Option[Personal[String]] = None,
    postCode: Option[Personal[String]] = None,
    state: Option[Personal[String]] = None
):
  /** The player's entries override the session's, field by field (FR-015). */
  def overriddenBy(o: CustomerFields): CustomerFields =
    CustomerFields(o.email.orElse(email), o.firstName.orElse(firstName), o.lastName.orElse(lastName), o.phone.orElse(phone),
      o.mobile.orElse(mobile), o.province.orElse(province), o.ip.orElse(ip), o.country.orElse(country), o.city.orElse(city),
      o.address.orElse(address), o.postCode.orElse(postCode), o.state.orElse(state))

  /** The fields by the wire names the original's cashier and gateway use. */
  def wire: Vector[(String, String)] =
    Vector("customerEmail" -> email, "customerFirstName" -> firstName, "customerLastName" -> lastName, "customerPhone" -> phone,
      "customerMobile" -> mobile, "customerProvince" -> province, "customerIp" -> ip, "customerCountry" -> country,
      "customerCity" -> city, "customerAddress" -> address, "customerPostCode" -> postCode, "customerState" -> state)
      .collect { case (k, Some(v)) => k -> v.reveal }

object CustomerFields:
  /** Reads the fields from a body by their wire names; an empty value is absent. */
  def from(field: String => Option[String]): CustomerFields =
    def f(name: String) = field(name).map(_.trim).filter(_.nonEmpty).map(Personal(_))
    CustomerFields(f("customerEmail"), f("customerFirstName"), f("customerLastName"), f("customerPhone"), f("customerMobile"),
      f("customerProvince"), f("customerIp"), f("customerCountry"), f("customerCity"), f("customerAddress"), f("customerPostCode"),
      f("customerState"))
