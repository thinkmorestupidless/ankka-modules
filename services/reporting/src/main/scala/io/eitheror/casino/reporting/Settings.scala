package io.eitheror.casino.reporting

import java.time.{Duration, ZoneId}
import scala.util.Try

/**
 * What the service is told at start (research R7, R8, R12, R14): the brand and its project, whether it takes
 * real money, the reporting zone its days are counted in (attribution's), the modules it runs beside, the
 * grace a day waits after its end before it closes (attribution's `CLOSE_GRACE`, so the two close at the same
 * instant), and the generation every intake and day entity id begins with. What an operator changes is the
 * brand's `ReportingSettings`, not a setting here.
 */
final case class Settings(
    brand: String,
    project: String = "",
    realMoney: Boolean = false,
    reportingZone: ZoneId = ZoneId.of("UTC"),
    modules: Set[String] = Set.empty,
    closeGrace: Duration = Duration.ofMinutes(15),
    generation: Int = 1
):
  /** Whether the casino module runs in this project, so `casino.rounds` and `casino.rewards` exist to be read (R12). */
  def casino: Boolean = modules.contains("casino")

  /** Whether the predictions module runs, so `markets.events` and `exchange.trades` exist and its reports are served (FR-032). */
  def predictions: Boolean = modules.contains("predictions")

object Settings:

  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    val d = Settings("")
    for
      brand      <- env.get("CASINO_BRAND").map(_.trim).filter(_.nonEmpty)
                      .toRight("CASINO_BRAND is not set: a brand service is deployed by the modules tool")
      realMoney  <- boolean(env, "CASINO_REAL_MONEY", d.realMoney)
      zone       <- zoneOf(env, d.reportingZone)
      grace      <- duration(env, "CLOSE_GRACE", d.closeGrace)
      generation <- generationOf(env, d.generation)
    yield Settings(
      brand,
      env.get("CASINO_PROJECT").map(_.trim).filter(_.nonEmpty).getOrElse(brand),
      realMoney,
      zone,
      env.get("CASINO_MODULES").map(_.split(',').map(_.trim).filter(_.nonEmpty).toSet).getOrElse(Set.empty),
      grace,
      generation)

  /** Attribution's reading of the zone (`services/attribution/.../Settings.scala`), so both count the same day. */
  private def zoneOf(env: Map[String, String], default: ZoneId): Either[String, ZoneId] =
    env.get("CASINO_REPORTING_ZONE").map(_.trim).filter(_.nonEmpty).fold(Right(default))(v =>
      Try(ZoneId.of(v)).toOption.toRight(s"CASINO_REPORTING_ZONE: '$v' is not a time zone such as UTC or Africa/Johannesburg"))

  private def boolean(env: Map[String, String], name: String, default: Boolean): Either[String, Boolean] =
    env.get(name).map(_.trim.toLowerCase).filter(_.nonEmpty).fold(Right(default)) {
      case "true"  => Right(true)
      case "false" => Right(false)
      case v       => Left(s"$name: '$v' is not true or false")
    }

  private def generationOf(env: Map[String, String], default: Int): Either[String, Int] =
    env.get("REPORTING_GENERATION").map(_.trim).filter(_.nonEmpty).fold(Right(default))(v =>
      v.toIntOption.filter(_ >= 1).toRight(s"REPORTING_GENERATION: '$v' is not a whole number of 1 or more"))

  private def duration(env: Map[String, String], name: String, default: Duration): Either[String, Duration] =
    env.get(name).fold(Right(default))(v =>
      parseDuration(v).toRight(s"$name: '$v' is not a duration such as 10s, 15m or 500ms"))

  private val Pattern = """(\d+)\s*(ms|s|m|h|d)""".r

  /** Attribution's duration syntax, so the manifest's one literal reads the same in both. */
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
