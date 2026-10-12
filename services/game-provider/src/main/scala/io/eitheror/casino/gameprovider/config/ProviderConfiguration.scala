package io.eitheror.casino.gameprovider.config

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import com.thinkmorestupidless.ankka.core.Codecs
import io.eitheror.casino.gameprovider.provider.st8.St8Signature

import java.time.Instant
import scala.util.Try

/**
 * A brand's configuration for one game provider (FR-026, research R4): one document in the service's
 * own secret store under `provider/<name>`. `operatorPrivateKey` is base64 PKCS#8 and is never answered
 * on a read; exactly one of `providerPublicKey` (base64 DER) and `providerKeyDocumentUrl` is set, the
 * latter only on an installation that takes no real money.
 */
final case class ProviderConfiguration(
    apiUrl: String,
    site: String,
    operatorPrivateKey: String,
    providerPublicKey: Option[String] = None,
    providerKeyDocumentUrl: Option[String] = None,
    jurisdiction: String,
    lobbyUrl: String,
    depositUrl: String,
    walletUrl: Option[String] = None,
    updatedAt: Option[Instant] = None
):
  /** What an operator reads back: everything but the private key. */
  def view(provider: String): ProviderConfigurationView =
    ProviderConfigurationView(provider, apiUrl, site, providerPublicKey, providerKeyDocumentUrl, jurisdiction, lobbyUrl,
      depositUrl, walletUrl, updatedAt)

  /** The refusal a write is answered with, or none (contracts/routes.md, operator). */
  def problem(realMoney: Boolean): Option[String] =
    if Seq(apiUrl, site, operatorPrivateKey, jurisdiction, lobbyUrl, depositUrl).exists(_.trim.isEmpty) then
      Some("apiUrl, site, operatorPrivateKey, jurisdiction, lobbyUrl and depositUrl are required")
    else if providerPublicKey.isDefined == providerKeyDocumentUrl.isDefined then
      Some("exactly one of providerPublicKey and providerKeyDocumentUrl")
    else if realMoney && providerKeyDocumentUrl.isDefined then
      Some("real_money_brand: a brand that takes real money holds the provider's public key itself")
    else if St8Signature.privateKey(operatorPrivateKey).isLeft then Some("operatorPrivateKey is not an EC private key in PKCS#8")
    else if providerPublicKey.exists(k => St8Signature.publicKey(k).isLeft) then Some("providerPublicKey is not an EC public key")
    else None

final case class ProviderConfigurationView(
    provider: String,
    apiUrl: String,
    site: String,
    providerPublicKey: Option[String],
    providerKeyDocumentUrl: Option[String],
    jurisdiction: String,
    lobbyUrl: String,
    depositUrl: String,
    walletUrl: Option[String],
    updatedAt: Option[Instant]
)

object ProviderConfiguration:
  given JsonValueCodec[ProviderConfiguration]     = Codecs.make[ProviderConfiguration]
  given JsonValueCodec[ProviderConfigurationView] = Codecs.make[ProviderConfigurationView]

  def secretName(provider: String): String = s"provider/$provider"

  /** The secret listing the providers configured, since a secret store cannot list its entries. */
  val IndexName = "provider/_index"

  def decode(text: String): Option[ProviderConfiguration] = Try(readFromString[ProviderConfiguration](text)).toOption
  def encode(c: ProviderConfiguration): String           = writeToString(c)
