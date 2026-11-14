package io.eitheror.casino.publicapi

import java.net.URI
import java.time.Duration
import scala.util.Try

/**
 * What the service is told at start (spec 016 plan, research R9, R11): the brand and its project, the address the
 * contract's `servers` shows (`PUBLIC_API_HOST`, without `/v1`), the budget each `/v1` request runs under, and how long
 * the games catalogue read from `game-provider` is kept. What an operator changes is a key, not a setting here.
 */
final case class Settings(
    brand: String,
    project: String = "",
    host: Option[URI] = None,
    budget: Duration = Duration.ofSeconds(5),
    catalogueFor: Duration = Duration.ofMinutes(5)
):
  /** The contract's `servers[0].url`: the host with `/v1`, or the relative `/v1` where none is named. */
  def serverUrl: String = host.fold("/v1")(h => h.toString.stripSuffix("/") + "/v1")

object Settings:

  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    val d = Settings("")
    for
      brand   <- text(env, "CASINO_BRAND").toRight("CASINO_BRAND is not set: a brand service is deployed by the modules tool")
      host    <- hostOf(env)
      budget  <- duration(env, "PUBLIC_API_BUDGET", d.budget)
      cache   <- duration(env, "PUBLIC_API_CATALOGUE_FOR", d.catalogueFor)
    yield Settings(brand, text(env, "CASINO_PROJECT").getOrElse(brand), host, budget, cache)

  private def text(env: Map[String, String], name: String): Option[String] = env.get(name).map(_.trim).filter(_.nonEmpty)

  private def hostOf(env: Map[String, String]): Either[String, Option[URI]] =
    text(env, "PUBLIC_API_HOST").fold(Right(None)) { v =>
      Try(URI.create(v)).toOption
        .filter(u => (u.getScheme == "https" || u.getScheme == "http") && u.getHost != null && Option(u.getRawQuery).isEmpty)
        .map(Some(_))
        .toRight(s"PUBLIC_API_HOST: '$v' is not an address such as https://api.example.com")
    }

  private def duration(env: Map[String, String], name: String, default: Duration): Either[String, Duration] =
    text(env, name).fold(Right(default))(v => parseDuration(v).toRight(s"$name: '$v' is not a duration such as 5s, 5m or 500ms"))

  private val Pattern = """(\d+)\s*(ms|s|m|h)""".r

  def parseDuration(text: String): Option[Duration] =
    text.trim match
      case Pattern(n, unit) =>
        n.toLongOption.filter(_ > 0).map { v =>
          unit match
            case "ms" => Duration.ofMillis(v)
            case "s"  => Duration.ofSeconds(v)
            case "m"  => Duration.ofMinutes(v)
            case _    => Duration.ofHours(v)
        }
      case _ => None
