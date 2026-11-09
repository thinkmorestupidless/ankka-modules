package io.eitheror.casino.audit

import java.net.URI
import java.time.{Duration, Instant}
import scala.util.Try

/**
 * What the service is told at start (data-model.md, "Settings"): the brands whose sources it reads,
 * the installation's retention, the consumers' generation and where a raised one re-reads from, the
 * bound on skew, the largest page, and the realm it grants roles in. The registry key is not a
 * setting: it lives in the service's secret store (research R5).
 */
final case class Settings(
    brands: Vector[String] = Vector.empty,
    project: String = "reasoner",
    retentionYears: Int = 7,
    readGeneration: Int = 1,
    /** The restore point a raised generation re-reads from (research R14); required above generation 1. */
    readFrom: Option[Instant] = None,
    skewBound: Duration = Duration.ofMinutes(5),
    pageLimit: Int = 1000,
    keycloakUrl: Option[String] = None,
    keycloakRealm: String = "eitheror",
    keycloakAdminUsername: Option[String] = None,
    keycloakAdminPassword: Option[String] = None
)

object Settings:
  private val Slug = "[a-z0-9-]{1,40}".r

  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    val d = Settings()
    def opt(name: String) = env.get(name).map(_.trim).filter(_.nonEmpty)
    def positive(name: String, default: Int) =
      opt(name).fold(Right(default))(v => v.toIntOption.filter(_ > 0).toRight(s"$name: '$v' is not a whole number above 0"))
    for
      brands     <- brandsOf(opt("AUDIT_BRANDS").getOrElse(""))
      years      <- positive("AUDIT_RETENTION_YEARS", d.retentionYears)
      generation <- positive("AUDIT_READ_GENERATION", d.readGeneration)
      from       <- opt("AUDIT_READ_FROM").fold(Right(None))(v =>
                      Try(Instant.parse(v)).toOption.map(Some(_)).toRight(s"AUDIT_READ_FROM: '$v' is not an instant such as 2026-11-09T00:00:00Z"))
      _          <- Either.cond(generation == 1 || from.isDefined, (),
                      s"AUDIT_READ_FROM: required when AUDIT_READ_GENERATION is $generation; it is the restore point the consumers re-read from")
      skew       <- opt("AUDIT_SKEW_BOUND").fold(Right(d.skewBound))(v =>
                      parseDuration(v).toRight(s"AUDIT_SKEW_BOUND: '$v' is not a duration such as 5m or 30s"))
      page       <- positive("AUDIT_PAGE_LIMIT", d.pageLimit)
      kc         <- opt("KEYCLOAK_URL").fold(Right(None))(u => url("KEYCLOAK_URL", u).map(Some(_)))
    yield Settings(brands, opt("CASINO_PROJECT").getOrElse(d.project), years, generation, from, skew, page, kc,
      opt("KEYCLOAK_REALM").getOrElse(d.keycloakRealm), opt("KEYCLOAK_ADMIN_USERNAME"), opt("KEYCLOAK_ADMIN_PASSWORD"))

  /** `AUDIT_BRANDS`: comma-separated slugs, each `[a-z0-9-]`; empty when no brand runs `core` yet. */
  def brandsOf(value: String): Either[String, Vector[String]] =
    val slugs = value.split(',').map(_.trim).filter(_.nonEmpty).toVector
    slugs.find(s => !Slug.matches(s)) match
      case Some(bad) => Left(s"AUDIT_BRANDS: '$bad' is not a brand slug ([a-z0-9-])")
      case None      => Right(slugs.distinct)

  private def url(name: String, value: String): Either[String, String] =
    Try(URI.create(value)).toOption.filter(u => Set("http", "https").contains(u.getScheme) && u.getHost != null)
      .map(_ => value.stripSuffix("/")).toRight(s"$name: '$value' is not an http or https address")

  private val DurationPattern = """(\d+)\s*(ms|s|m|h)""".r

  def parseDuration(text: String): Option[Duration] = text.trim match
    case DurationPattern(n, unit) =>
      n.toLongOption.filter(_ > 0).map(v => unit match
        case "ms" => Duration.ofMillis(v)
        case "s"  => Duration.ofSeconds(v)
        case "m"  => Duration.ofMinutes(v)
        case _    => Duration.ofHours(v))
    case _ => None
