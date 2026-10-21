package io.eitheror.casino.pspgateway.providers

import io.eitheror.casino.contract.money.{Amounts, Currency}
import io.eitheror.casino.pspgateway.sessions.Provider

import java.time.Duration

/** What a provider offers. */
enum Operation:
  case Deposit, Payout

/** Why the catalogue refuses a request before any provider is called (contracts/merchant.md §1). */
enum Unsupported(val error: String, val message: String):
  case Method(provider: Provider, method: String, operation: Operation)
      extends Unsupported("unsupported_method", s"${provider.wire} does not offer $method for ${operation.toString.toLowerCase}s")
  case CurrencyOf(provider: Provider, currency: Currency, operation: Operation)
      extends Unsupported("unsupported_currency", s"${provider.wire} does not take ${currency.code} for ${operation.toString.toLowerCase}s")
  case NotOffered(provider: Provider, operation: Operation)
      extends Unsupported("unsupported_method", s"${provider.wire} does not offer ${operation.toString.toLowerCase}s")

/**
 * Per provider, what the gateway knows without asking it (FR-006, FR-009a, R10): the operations, the
 * methods and currencies each takes, the minimum, whether a payout's status can be read, the payout
 * review window, the credential keys the original reads, the base URL settings and the public paths.
 */
final case class Entry(
    provider: Provider,
    operations: Set[Operation],
    /** The deposit methods the provider maps, where it maps a closed set; none where any is passed through. */
    depositMethods: Option[Set[String]] = None,
    /** Currencies a deposit may be in; none for any. */
    depositCurrencies: Option[Currency => Boolean] = None,
    payoutCurrencies: Option[Currency => Boolean] = None,
    /** The smallest deposit in minor units, with the player's message (BounceMoney). */
    minimum: Option[(Long, String)] = None,
    hasStatus: Boolean = false,
    reviewWindow: Option[Duration] = None,
    /** The keys the original reads from a credential (for the leak suites; `.{METHOD}` keys by prefix). */
    credentialNames: Set[String],
    /** Credential keys suffixed `.{METHOD}`. */
    perMethodNames: Set[String] = Set.empty,
    depositUrl: String,
    payoutUrl: Option[String] = None
):
  def offers(operation: Operation): Boolean = operations.contains(operation)

object Catalogue:
  private def only(code: String): Currency => Boolean = _.code == code
  private val twoDecimals: Currency => Boolean = c => Amounts.exponent(c) == 2

  val entries: Map[Provider, Entry] = Vector(
    Entry(Provider.DreamzPay, Set(Operation.Deposit),
      credentialNames = Set("memberId", "totype", "secureKey", "terminalId"), perMethodNames = Set("terminalId"),
      depositUrl = "DREAMZPAY_BASE_URL"),
    Entry(Provider.UnityFinance, Set(Operation.Deposit, Operation.Payout),
      // `unityfinance/adapter.go:232-262`: the ten methods, with the legacy `CARD_` names and the test card.
      depositMethods = Some(Set("APPLE_PAY", "GOOGLE_PAY", "OPEN_BANKING", "VISA", "CARD_VISA", "MASTERCARD",
        "CARD_MASTERCARD", "CARD_TEST", "MBWAY", "SKRILL", "NETELLER", "PAYSAFECARD", "RAPID")),
      hasStatus = true,
      credentialNames = Set("signKey", "checkoutId", "userId", "withdrawalApiKey", "accountId", "purseId"),
      perMethodNames = Set("checkoutId"),
      depositUrl = "UNITYFINANCE_PAY_URL", payoutUrl = Some("UNITYFINANCE_API_URL")),
    Entry(Provider.T365, Set(Operation.Deposit),
      // `t365/adapter.go:191-228`: the 16 codes.
      depositMethods = Some(Set("BANK_TRANSFER", "WALLETS", "QR_CODE", "VIRTUAL_ACCOUNT", "UPI", "FPX", "DUITNOW",
        "TRUEMONEY", "MOMOPAY", "MOBILE_MONEY", "CASH", "PIX", "OPEN_BANKING", "CRYPTO_BEP20", "CRYPTO_ERC20",
        "CRYPTO_TRC20")),
      credentialNames = Set("apiKey", "midId", "secretKey"),
      depositUrl = "T365_BASE_URL"),
    Entry(Provider.Cozeni, Set(Operation.Deposit, Operation.Payout),
      reviewWindow = Some(Duration.ofHours(24)),
      credentialNames = Set("accountId", "apiKey", "merchantId"),
      depositUrl = "COZENI_BASE_URL", payoutUrl = Some("COZENI_BASE_URL")),
    Entry(Provider.EuPaymentz, Set(Operation.Deposit),
      credentialNames = Set("accountId", "passphrase", "password"),
      depositUrl = "EUPAYMENTZ_BASE_URL"),
    Entry(Provider.Gigadat, Set(Operation.Deposit, Operation.Payout),
      // `gigadat/adapter.go:129`, `handler/gigadat.go:218`: Interac is CAD only, both ways.
      depositCurrencies = Some(only("CAD")), payoutCurrencies = Some(only("CAD")),
      reviewWindow = Some(Duration.ofDays(5)),
      credentialNames = Set("accessToken", "campaignId", "environment", "securityToken", "siteUrl"),
      depositUrl = "GIGADAT_BASE_URL", payoutUrl = Some("GIGADAT_BASE_URL")),
    Entry(Provider.BounceMoney, Set(Operation.Deposit),
      // `bouncemoney/adapter.go:102-104`: 15.00 in any fiat currency.
      minimum = Some(1500L -> "minimum deposit amount is 15.00"),
      credentialNames = Set("keyId", "paymentMethodId", "privateKey", "signingSecret"),
      depositUrl = "BOUNCEMONEY_BASE_URL"),
    Entry(Provider.Digitarium, Set(Operation.Deposit),
      // `digitarium/adapter.go:37-46,127`: the amount is sent as a decimal of hundredths.
      depositCurrencies = Some(twoDecimals),
      credentialNames = Set("apiKey", "environment", "signingKey"),
      depositUrl = "DIGITARIUM_SANDBOX_URL"),
    Entry(Provider.Morphosis, Set(Operation.Deposit, Operation.Payout),
      // `morphosis/adapter.go:26`: payouts are USD only.
      payoutCurrencies = Some(only("USD")),
      reviewWindow = Some(Duration.ofDays(5)),
      credentialNames = Set("account", "pass", "user"),
      depositUrl = "MORPHOSIS_BASE_URL", payoutUrl = Some("MORPHOSIS_BASE_URL"))
  ).map(e => e.provider -> e).toMap

  def apply(provider: Provider): Entry = entries(provider)

  /** Whether the provider takes this method in this currency for the operation; the refusal names both. */
  def check(provider: Provider, operation: Operation, method: String, currency: Currency): Either[Unsupported, Unit] =
    val e = apply(provider)
    val currencies = operation match
      case Operation.Deposit => e.depositCurrencies
      case Operation.Payout  => e.payoutCurrencies
    if !e.offers(operation) then Left(Unsupported.NotOffered(provider, operation))
    else if operation == Operation.Deposit && e.depositMethods.exists(!_.contains(method)) then
      Left(Unsupported.Method(provider, method, operation))
    else if currencies.exists(!_(currency)) then Left(Unsupported.CurrencyOf(provider, currency, operation))
    else Right(())

  /**
   * The keys a deposit cannot be initiated without, per provider as its adapter reads them (a
   * `.{METHOD}` key by the method). A credential lacking one is not usable: the initiate answers
   * `404 no_credentials` naming the provider and calls none (contracts/merchant.md §1).
   */
  def requiredForDeposit(provider: Provider, method: String): Vector[String] = provider match
    case Provider.DreamzPay    => Vector("memberId", "totype", "secureKey")
    case Provider.UnityFinance => Vector("signKey", s"checkoutId.${method.trim.toUpperCase}")
    case Provider.T365         => Vector("apiKey", "secretKey", "midId")
    case Provider.Cozeni       => Vector("accountId", "apiKey", "merchantId")
    case Provider.EuPaymentz   => Vector("accountId", "password", "passphrase")
    case Provider.Gigadat      => io.eitheror.casino.pspgateway.providers.gigadat.Gigadat.required
    case Provider.BounceMoney  => Vector("keyId", "privateKey")
    case Provider.Digitarium   => Vector("apiKey")
    case Provider.Morphosis    => Vector("user", "pass")

  /** The required keys a credential lacks, by name. */
  def lacking(provider: Provider, method: String, credential: Credential): Vector[String] =
    requiredForDeposit(provider, method).filter(credential.get(_).isEmpty)

  /** The player's refusal when a deposit is below the provider's minimum (contracts/merchant.md §1). */
  def belowMinimum(provider: Provider, amount: Long): Option[String] =
    apply(provider).minimum.collect { case (min, message) if amount < min => message }

  /**
   * The setting naming the provider's base URL for an operation (R10). Digitarium's depends on the
   * credential's `environment` (`digitarium/adapter.go:25-34`): `production` selects the production URL.
   */
  def baseUrlSetting(provider: Provider, operation: Operation, credential: Credential): Option[String] =
    val e = apply(provider)
    operation match
      case Operation.Deposit if provider == Provider.Digitarium =>
        Some(if credential.get("environment").contains("production") then "DIGITARIUM_PRODUCTION_URL" else "DIGITARIUM_SANDBOX_URL")
      case Operation.Deposit => Some(e.depositUrl)
      case Operation.Payout  => e.payoutUrl

  /** Every base URL setting the installation may carry (data-model.md *Settings*). */
  val baseUrlSettings: Vector[String] = Vector(
    "DREAMZPAY_BASE_URL", "UNITYFINANCE_PAY_URL", "UNITYFINANCE_API_URL", "T365_BASE_URL", "COZENI_BASE_URL",
    "EUPAYMENTZ_BASE_URL", "GIGADAT_BASE_URL", "BOUNCEMONEY_BASE_URL", "MORPHOSIS_BASE_URL",
    "DIGITARIUM_SANDBOX_URL", "DIGITARIUM_PRODUCTION_URL"
  )

  /** Every base URL setting a provider may use, for either operation and environment. */
  def settingsOf(provider: Provider): Vector[String] =
    val e = apply(provider)
    val extra = if provider == Provider.Digitarium then Vector("DIGITARIUM_PRODUCTION_URL") else Vector.empty
    (Vector(e.depositUrl) ++ e.payoutUrl ++ extra).distinct

  /** The webhook path for a deposit (contracts/routes.md); Morphosis's carries the session's token. */
  def webhookPath(provider: Provider, generated: Map[String, String]): String = provider match
    case Provider.Morphosis => s"/v1/webhooks/morphosis/deposit/${generated.getOrElse("token", "")}"
    case p                  => s"/v1/webhooks/${p.path}"

  def payoutWebhookPath(provider: Provider, generated: Map[String, String]): String = provider match
    case Provider.Morphosis    => s"/v1/webhooks/morphosis/withdrawal/${generated.getOrElse("token", "")}"
    case Provider.Gigadat      => "/v1/webhooks/gigadat"
    case p                     => s"/v1/webhooks/${p.path}/withdrawal"

  /**
   * Where the player's browser returns (contracts/routes.md). Unity Finance's is brand-neutral
   * (`main.go:170-172`); the crypto providers have no return.
   */
  def returnPath(provider: Provider, reference: String): String = provider match
    case Provider.UnityFinance => s"/v1/psp/checkout/$reference/return"
    case p                     => s"/v1/psp/checkout/${p.path}/$reference/return"
