package io.eitheror.casino.programme

import java.net.URI
import java.time.Duration
import scala.util.Try

/**
 * What the service is told at start (research R18): the brands whose facts it consumes, the two sites
 * its emails link to, Customer.io's default region, and the realm it provisions in. No secret is a
 * setting except the realm administrator's, which the manifest takes from the project secret
 * `keycloak-operators-admin`; the rest live in the service's store.
 */
final case class Settings(
    brands: Vector[String] = Vector.empty,
    project: String = "programme",
    portalBaseUrl: String = "http://localhost:3013",
    backofficeBaseUrl: String = "http://localhost:3014",
    customerIoRegion: String = "eu",
    keycloakUrl: Option[String] = None,
    keycloakRealm: String = "eitheror",
    keycloakAdminUsername: Option[String] = None,
    keycloakAdminPassword: Option[String] = None,
    outboundTimeout: Duration = Duration.ofSeconds(10),
    /** An http postback or webhook address admitted: for a local run and the suites only. */
    plainHttp: Boolean = false,
    /** `attribution`'s address in this installation: its brands' key documents (FR-026). */
    attributionBaseUrl: Option[String] = None
)

object Settings:
  private val Slug = "[a-z0-9-]{1,40}".r

  /** The settings from the environment, refused naming the variable when one cannot be read. */
  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    val d = Settings()
    def opt(name: String) = env.get(name).map(_.trim).filter(_.nonEmpty)
    for
      brands  <- brandsOf(opt("PROGRAMME_BRANDS").getOrElse(""))
      portal  <- url("AFFILIATE_PORTAL_BASE_URL", opt("AFFILIATE_PORTAL_BASE_URL").getOrElse(d.portalBaseUrl))
      office  <- url("AFFILIATE_BACKOFFICE_BASE_URL", opt("AFFILIATE_BACKOFFICE_BASE_URL").getOrElse(d.backofficeBaseUrl))
      region  <- opt("CUSTOMERIO_REGION_DEFAULT").getOrElse(d.customerIoRegion) match
                   case r @ ("eu" | "us") => Right(r)
                   case r                 => Left(s"CUSTOMERIO_REGION_DEFAULT: '$r' is eu or us")
      kc      <- opt("KEYCLOAK_URL").fold(Right(None))(u => url("KEYCLOAK_URL", u).map(Some(_)))
      attr    <- opt("ATTRIBUTION_BASE_URL").fold(Right(None))(u => url("ATTRIBUTION_BASE_URL", u).map(Some(_)))
      timeout <- opt("OUTBOUND_TIMEOUT").fold(Right(d.outboundTimeout))(v =>
                   parseDuration(v).toRight(s"OUTBOUND_TIMEOUT: '$v' is not a duration such as 10s or 500ms"))
    yield Settings(brands, opt("CASINO_PROJECT").getOrElse(d.project), portal, office, region, kc,
      opt("KEYCLOAK_REALM").getOrElse(d.keycloakRealm), opt("KEYCLOAK_ADMIN_USERNAME"), opt("KEYCLOAK_ADMIN_PASSWORD"),
      timeout, opt("PLAIN_HTTP").exists(_.equalsIgnoreCase("true")), attr)

  /** `PROGRAMME_BRANDS`: comma-separated slugs, each `[a-z0-9-]`; empty when only hosted brands report here. */
  def brandsOf(value: String): Either[String, Vector[String]] =
    val slugs = value.split(',').map(_.trim).filter(_.nonEmpty).toVector
    slugs.find(s => !Slug.matches(s)) match
      case Some(bad) => Left(s"PROGRAMME_BRANDS: '$bad' is not a brand slug ([a-z0-9-])")
      case None      => Right(slugs.distinct)

  private def url(name: String, value: String): Either[String, String] =
    Try(URI.create(value)).toOption.filter(u => Set("http", "https").contains(u.getScheme) && u.getHost != null)
      .map(_ => value.stripSuffix("/")).toRight(s"$name: '$value' is not an http or https address")

  private val DurationPattern = """(\d+)\s*(ms|s|m)""".r

  def parseDuration(text: String): Option[Duration] = text.trim match
    case DurationPattern(n, unit) =>
      n.toLongOption.filter(_ > 0).map(v => unit match
        case "ms" => Duration.ofMillis(v)
        case "s"  => Duration.ofSeconds(v)
        case _    => Duration.ofMinutes(v))
    case _ => None
