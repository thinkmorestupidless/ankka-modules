package io.eitheror.casino.attribution

import java.time.{Duration, ZoneId}
import scala.util.Try

/**
 * What the service is told at start (data-model.md *Settings*): the brand, the reporting zone its days
 * are counted in, the modules it runs beside, the header naming a visitor's country, and the bounds on a
 * document fetch, a day's close and a webhook post. No secret is a setting: the keys live in the store.
 * `webhookPlainHttp` admits an http webhook address, for a local run and the suites only; a webhook is
 * https wherever a brand takes traffic.
 */
final case class Settings(
    brand: String,
    reportingZone: ZoneId = ZoneId.of("UTC"),
    modules: Set[String] = Set.empty,
    countryHeader: String = "CF-IPCountry",
    documentTimeout: Duration = Duration.ofSeconds(10),
    closeGrace: Duration = Duration.ofMinutes(15),
    webhookTimeout: Duration = Duration.ofSeconds(10),
    webhookPlainHttp: Boolean = false
):
  /** Whether the casino module runs in this project, so `casino.rewards` exists to be read (research R7). */
  def casino: Boolean = modules.contains("casino")

object Settings:

  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    val d = Settings("")
    for
      brand    <- env.get("CASINO_BRAND").map(_.trim).filter(_.nonEmpty)
                    .toRight("CASINO_BRAND is not set: a brand service is deployed by the modules tool")
      zone     <- zoneOf(env, d.reportingZone)
      timeout  <- duration(env, "DOCUMENT_TIMEOUT", d.documentTimeout)
      grace    <- duration(env, "CLOSE_GRACE", d.closeGrace)
      webhook  <- duration(env, "WEBHOOK_TIMEOUT", d.webhookTimeout)
    yield Settings(
      brand,
      zone,
      env.get("CASINO_MODULES").map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty),
      env.get("COUNTRY_HEADER").map(_.trim).filter(_.nonEmpty).getOrElse(d.countryHeader),
      timeout, grace, webhook,
      env.get("WEBHOOK_PLAIN_HTTP").exists(_.trim.equalsIgnoreCase("true")))

  private def zoneOf(env: Map[String, String], default: ZoneId): Either[String, ZoneId] =
    env.get("CASINO_REPORTING_ZONE").map(_.trim).filter(_.nonEmpty).fold(Right(default))(v =>
      Try(ZoneId.of(v)).toOption.toRight(s"CASINO_REPORTING_ZONE: '$v' is not a time zone such as UTC or Africa/Johannesburg"))

  private def duration(env: Map[String, String], name: String, default: Duration): Either[String, Duration] =
    env.get(name).fold(Right(default))(v =>
      parseDuration(v).toRight(s"$name: '$v' is not a duration such as 10s, 15m or 500ms"))

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
