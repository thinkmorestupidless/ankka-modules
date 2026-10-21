package io.eitheror.casino.merchant.terminals.catalogue

/**
 * A property a terminal of a gateway's scheme must carry (the original's `RequiredProperty`), and,
 * new, whether it is a secret (FR-009, research R9): a secret goes to the service's secret store, and
 * a listing shows only that it is set, with a masked preview.
 */
final case class RequiredProperty(key: String, label: String, required: Boolean, multiline: Boolean = false, secret: Boolean = false)

final case class GatewayScheme(pspId: String, category: String, requiredProperties: Vector[RequiredProperty])

final case class GatewayCatalog(gateway: String, schemes: Vector[GatewayScheme])

/**
 * The static gateway catalogue the payments backoffice's Create Terminal form is driven by, as the
 * original serves it (`MerchantRoutes.gatewayCatalog`), for the eight gateways spec 005 keeps:
 * ConnecX is dropped with its adapter.
 */
object Catalogue:

  /** The property keys that are secrets, wherever they appear (research R9). */
  val SecretKeys: Set[String] = Set(
    "secureKey", "signKey", "apiKey", "secretKey", "withdrawalApiKey", "pass", "password", "passphrase",
    "accessToken", "securityToken", "privateKey", "signingSecret", "webhookSecret"
  )

  private def p(key: String, label: String, required: Boolean = true, multiline: Boolean = false) =
    RequiredProperty(key, label, required, multiline, SecretKeys.contains(key))

  private val dreamzpay = Vector(p("memberId", "Member ID"), p("totype", "To Type"), p("secureKey", "Secure Key"), p("terminalId", "Terminal ID"))

  private def unity(scheme: String, label: String) = Vector(p("signKey", "Sign Key"), p(s"checkoutId.$scheme", s"Checkout ID ($label)"))

  private val cozeni = Vector(p("merchantId", "Merchant ID"), p("apiKey", "API Key"), p("accountId", "Account ID"),
    p("supportedCurrencies", "Supported Currencies"))

  val gateways: Map[String, GatewayCatalog] = Map(
    "DREAMZPAY" -> GatewayCatalog("DREAMZPAY", Vector(
      GatewayScheme("VISA", "Card", dreamzpay),
      GatewayScheme("MASTERCARD", "Card", dreamzpay),
      GatewayScheme("BANK_TRANSFER", "Bank Transfer", dreamzpay)
    )),
    "UNITYFINANCE" -> GatewayCatalog("UNITYFINANCE", Vector(
      GatewayScheme("VISA", "Card", unity("VISA", "Visa")),
      GatewayScheme("MASTERCARD", "Card", unity("MASTERCARD", "Mastercard")),
      GatewayScheme("APPLE_PAY", "Wallet", unity("APPLE_PAY", "Apple Pay")),
      GatewayScheme("GOOGLE_PAY", "Wallet", unity("GOOGLE_PAY", "Google Pay")),
      GatewayScheme("MBWAY", "Mobile Payment", unity("MBWAY", "MBway")),
      GatewayScheme("OPEN_BANKING", "Open Banking", Vector(
        p("signKey", "Sign Key"),
        p("checkoutId.OPEN_BANKING", "Checkout ID — Deposits", required = false),
        p("userId", "Withdrawal User ID"),
        p("withdrawalApiKey", "Withdrawal API Key"),
        p("accountId", "Withdrawal Account ID"),
        p("purseId", "Withdrawal Purse ID")
      )),
      GatewayScheme("SKRILL", "Wallet", unity("SKRILL", "Skrill")),
      GatewayScheme("NETELLER", "Wallet", unity("NETELLER", "Neteller")),
      GatewayScheme("PAYSAFECARD", "Voucher", unity("PAYSAFECARD", "Paysafecard")),
      GatewayScheme("RAPID", "Wallet", unity("RAPID", "Rapid"))
    )),
    "T365" -> GatewayCatalog("T365", Vector(
      GatewayScheme("OPEN_BANKING", "Open Banking", Vector(p("apiKey", "API Key"), p("secretKey", "Secret Key"), p("midId", "MID ID")))
    )),
    "COZENI" -> GatewayCatalog("COZENI", Vector(GatewayScheme("CRYPTO", "Crypto", cozeni))),
    "MORPHOSIS" -> GatewayCatalog("MORPHOSIS", Vector(
      GatewayScheme("CRYPTO", "Crypto", Vector(p("user", "API Username"), p("pass", "API Password"), p("supportedCurrencies", "Supported Currencies")))
    )),
    "EUPAYMENTZ" -> GatewayCatalog("EUPAYMENTZ", Vector(
      GatewayScheme("VISA", "Card", Vector(p("accountId", "Account ID"), p("password", "Password"), p("passphrase", "Passphrase"))),
      GatewayScheme("MASTERCARD", "Card", Vector(p("accountId", "Account ID"), p("password", "Password"), p("passphrase", "Passphrase")))
    )),
    "GIGADAT" -> GatewayCatalog("GIGADAT", Vector(
      GatewayScheme("INTERAC_ETRANSFER", "Bank Transfer", Vector(p("campaignId", "Campaign ID"), p("accessToken", "Access Token"),
        p("securityToken", "Security Token"), p("siteUrl", "Site URL"), p("environment", "Environment")))
    )),
    "BOUNCEMONEY" -> GatewayCatalog("BOUNCEMONEY", Vector(
      GatewayScheme("ONRAMP_VISA", "Fiat-to-Crypto", Vector(p("keyId", "Key ID"),
        p("privateKey", "Ed25519 Private Key (PEM)", multiline = true), p("signingSecret", "Webhook Signing Secret")))
    ))
  )

  /** A gateway's catalogue; an unknown gateway is an empty one, as the original answers. */
  def of(gateway: String): GatewayCatalog = gateways.getOrElse(gateway.toUpperCase, GatewayCatalog(gateway, Vector.empty))

  private def scheme(gateway: String, scheme: String): Option[GatewayScheme] =
    gateways.get(gateway.toUpperCase).flatMap(_.schemes.find(_.pspId.equalsIgnoreCase(scheme)))

  /** The required keys a gateway's scheme declares; none for a scheme the catalogue does not know. */
  def requiredKeys(gateway: String, schemeId: String): Vector[String] =
    scheme(gateway, schemeId).toVector.flatMap(_.requiredProperties.filter(_.required).map(_.key))

  /** Whether a property of a terminal is a secret: the scheme's declaration, else the known secret keys. */
  def isSecret(gateway: String, schemeId: String, key: String): Boolean =
    scheme(gateway, schemeId).flatMap(_.requiredProperties.find(_.key == key)).map(_.secret).getOrElse(SecretKeys.contains(key))

  /** Splits a terminal's properties into those kept on the entity and the secrets kept in the store. */
  def split(gateway: String, schemeId: String, properties: Map[String, String]): (Map[String, String], Map[String, String]) =
    properties.partition((k, _) => !isSecret(gateway, schemeId, k))

  /**
   * The first few characters of a stored value, the rest masked at a fixed width (the original's
   * `maskedPreview`): enough to recognise it, never enough to use it, and the length is not disclosed.
   */
  def preview(value: String): String = value.take(math.min(4, value.length / 2)) + "********"
