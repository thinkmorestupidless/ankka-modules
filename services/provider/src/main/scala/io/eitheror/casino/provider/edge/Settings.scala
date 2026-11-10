package io.eitheror.casino.provider.edge

import io.eitheror.casino.provider.core.Schedule
import io.eitheror.casino.provider.st8.St8Protocol

import java.net.URI
import scala.concurrent.duration.*
import scala.util.Try

/**
 * The house provider's settings (spec 026, `contract/keys.md` "The house provider's settings"), read
 * once at start. A malformed value is refused naming the setting, and the service does not start.
 *
 * The staff issuer is the modules tool's to render (`ANKKA_AUTH_STAFF_*`); it is read here only to
 * refuse a start whose issuer is not an address, and is absent on a developer's machine, where the
 * operator routes refuse every token.
 */
final case class Settings(
    externalUrl: URI,
    retentionDays: Int,
    schedule: Schedule,
    localCa: Option[String],
    staffIssuer: Option[URI],
    /** The installation's reporting zone (`CASINO_REPORTING_ZONE`): a day of the export is a calendar day here. */
    reportingZone: java.time.ZoneId = java.time.ZoneOffset.UTC
)

object Settings:

  /** How long a send waits for a brand when its entry says nothing (the simulator's default). */
  val DefaultAnswerWait: FiniteDuration = 5.seconds

  val DefaultRetentionDays = 90

  val DefaultSchedule = """{"intervals":[{"every":"30s","for":"10m"},{"every":"10m"}],"cap":"8h"}"""

  def fromEnvironment(env: Map[String, String] = sys.env): Either[String, Settings] =
    def value(name: String): Option[String] = env.get(name).map(_.trim).filter(_.nonEmpty)
    def address(name: String, text: String): Either[String, URI] =
      Try(URI(text)).toOption.filter(u => Set("http", "https").contains(u.getScheme) && u.getHost != null)
        .toRight(s"$name '$text' is not an http(s) address")
    for
      external <- value("PROVIDER_EXTERNAL_URL").toRight("PROVIDER_EXTERNAL_URL is required: the address brands and players reach")
        .flatMap(address("PROVIDER_EXTERNAL_URL", _))
      days <- value("PROVIDER_RETENTION_DAYS").fold[Either[String, Int]](Right(DefaultRetentionDays)) { d =>
        d.toIntOption.filter(_ > 0).toRight(s"PROVIDER_RETENTION_DAYS '$d' is not a positive number of days")
      }
      schedule <- Schedule.fromJson(value("PROVIDER_RETRY_SCHEDULE").getOrElse(DefaultSchedule), DefaultAnswerWait, St8Protocol.defaultRetryOn)
        .left.map(e => s"PROVIDER_RETRY_SCHEDULE: $e")
      // PEM, or base64 of PEM as a secrets file carries it on one line (the library's Sender reads both).
      ca <- value("PROVIDER_LOCAL_CA").fold[Either[String, Option[String]]](Right(None)) { text =>
        val pem = if text.contains("-----BEGIN") then text else Try(String(java.util.Base64.getMimeDecoder.decode(text), "UTF-8")).getOrElse(text)
        Either.cond(pem.contains("-----BEGIN CERTIFICATE-----"), Some(pem), "PROVIDER_LOCAL_CA is not a PEM certificate")
      }
      issuer <- value("ANKKA_AUTH_STAFF_ISSUER").fold[Either[String, Option[URI]]](Right(None))(i => address("ANKKA_AUTH_STAFF_ISSUER", i).map(Some(_)))
      zone <- value("CASINO_REPORTING_ZONE").fold[Either[String, java.time.ZoneId]](Right(java.time.ZoneOffset.UTC)) { z =>
        Try(java.time.ZoneId.of(z)).toOption.toRight(s"CASINO_REPORTING_ZONE '$z' is not a time zone such as UTC or America/Sao_Paulo")
      }
    yield Settings(external, days, schedule, ca, issuer, zone)
