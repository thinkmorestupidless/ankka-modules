package io.eitheror.casino.rg

import java.time.Duration
import scala.util.Try

/**
 * What the service is told at start: the brand, and the waits the stories name. The brand's own minimum
 * session limit and maximum break are not settings: an operator changes them (`BrandSettingsEntity`, R13).
 * There are no secrets: every caller is in the project, admitted by its certificate.
 */
final case class Settings(
    brand: String = "goldvibe",
    loosenDelay: Duration = Duration.ofHours(24),
    returnDelay: Duration = Duration.ofHours(24),
    syncRetry: Duration = Duration.ofSeconds(30)
)

object Settings:

  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    val d = Settings()
    for
      brand  <- env.get("CASINO_BRAND").map(_.trim).filter(_.nonEmpty)
                  .toRight("CASINO_BRAND: not set; a brand service is deployed by the modules tool")
      loosen <- duration(env, "RG_LOOSEN_DELAY", d.loosenDelay)
      ret    <- duration(env, "RG_RETURN_DELAY", d.returnDelay)
      sync   <- duration(env, "RG_SYNC_RETRY", d.syncRetry)
    yield Settings(brand, loosen, ret, sync)

  private def duration(env: Map[String, String], name: String, default: Duration): Either[String, Duration] =
    env.get(name).fold(Right(default))(v => parseDuration(v).toRight(s"$name: '$v' is not a duration such as 30s, 24h or 500ms"))

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
