package io.eitheror.casino.pspgateway

import io.eitheror.casino.pspgateway.providers.{Catalogue, Credential, Operation}
import io.eitheror.casino.pspgateway.sessions.Provider

import java.time.Duration
import scala.util.Try

/** The installation has no base URL for a provider (FR-020): `502 provider_not_configured`. */
final case class ProviderNotConfigured(provider: Provider)

/**
 * What the service is told at start (data-model.md *Settings*): its public address, each provider's
 * base URL (none has a default, so nothing reaches a provider the installation did not name), the
 * per-call bound, the re-drive cadence and the name of `merchant`. No secret is a setting: every
 * credential is read from `merchant` (research R7).
 */
final case class Settings(
    publicUrl: String,
    baseUrls: Map[String, String] = Map.empty,
    providerTimeout: Duration = Duration.ofSeconds(20),
    redriveInterval: Duration = Duration.ofMinutes(2),
    merchantService: String = "merchant",
    // The shared project the manifest deploys the gateway into: `/status` admits its services. The
    // integration kit runs unnamed, as `local`.
    project: String = "payments"
):
  /** The provider's base URL for an operation, without a trailing `/`. */
  def baseUrl(provider: Provider, operation: Operation, credential: Credential): Either[ProviderNotConfigured, String] =
    Catalogue.baseUrlSetting(provider, operation, credential)
      .flatMap(baseUrls.get)
      .map(_.trim.stripSuffix("/"))
      .filter(_.nonEmpty)
      .toRight(ProviderNotConfigured(provider))

  /** Whether the installation names any base URL for the provider (the status page's `configured`). */
  def configured(provider: Provider): Boolean =
    Catalogue.settingsOf(provider).exists(n => baseUrls.get(n).exists(_.trim.nonEmpty))

  /** An address on this service, for a provider or a browser. */
  def public(path: String): String = publicUrl.stripSuffix("/") + path

object Settings:

  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    val d = Settings("")
    for
      public   <- env.get("PUBLIC_URL").map(_.trim).filter(_.nonEmpty)
                    .toRight("PUBLIC_URL is required: the base of every address given to a provider")
      timeout  <- duration(env, "PROVIDER_TIMEOUT", d.providerTimeout)
      interval <- duration(env, "REDRIVE_INTERVAL", d.redriveInterval)
    yield Settings(
      public,
      Catalogue.baseUrlSettings.flatMap(n => env.get(n).map(_.trim).filter(_.nonEmpty).map(n -> _)).toMap,
      timeout,
      interval,
      env.get("MERCHANT_SERVICE").map(_.trim).filter(_.nonEmpty).getOrElse(d.merchantService)
    )

  private def duration(env: Map[String, String], name: String, default: Duration): Either[String, Duration] =
    env.get(name).fold(Right(default))(v =>
      parseDuration(v).toRight(s"$name: '$v' is not a duration such as 20s, 2m or 500ms"))

  private val Pattern = """(\d+)\s*(ms|s|m|h|d)""".r

  def parseDuration(text: String): Option[Duration] =
    text.trim match
      case Pattern(n, unit) =>
        Try(n.toLong).toOption.filter(_ > 0).map { v =>
          unit match
            case "ms" => Duration.ofMillis(v)
            case "s"  => Duration.ofSeconds(v)
            case "m"  => Duration.ofMinutes(v)
            case "h"  => Duration.ofHours(v)
            case _    => Duration.ofDays(v)
        }
      case _ => None
