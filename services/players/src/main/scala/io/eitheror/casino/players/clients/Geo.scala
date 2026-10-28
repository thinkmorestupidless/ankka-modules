package io.eitheror.casino.players.clients

import io.eitheror.casino.players.Json
import io.eitheror.casino.players.Json.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import scala.util.Try

/** The public geo lookup's answer (FR-006): both `None` when the lookup fails. */
final case class GeoAnswer(country: Option[String], dialCode: Option[String])

/**
 * The caller's country from its network address, as the original's `GeoLookupService`: the built-in
 * addresses, private and loopback addresses unresolved, else ip-api.com, cached six hours per address
 * (an unresolved one too). Any failure is `None`, never an error. Unlike the original, the address is
 * never logged.
 */
trait Geo:
  def lookup(address: Option[String]): GeoAnswer

object Geo:
  val CacheMillis: Long = 6L * 60 * 60 * 1000

  /** The original's `resolveClientIp`: `x-test-ip`, the first of `x-forwarded-for`, `x-real-ip`. */
  def callerAddress(header: String => Option[String]): Option[String] =
    header("x-test-ip")
      .orElse(header("x-forwarded-for").map(_.split(",").head.trim).filter(_.nonEmpty))
      .orElse(header("x-real-ip"))

  val builtin: Map[String, String] = Map(
    "81.2.69.144" -> "GB", "81.2.69.145" -> "GB", "81.2.69.160" -> "GB", "196.25.255.1" -> "ZA", "149.101.100.0" -> "US")

  def privateOrLoopback(ip: String): Boolean =
    ip == "127.0.0.1" || ip == "::1" || ip.startsWith("localhost") || ip.startsWith("10.") || ip.startsWith("192.168.") ||
      (ip.startsWith("172.") && Try(ip.split("\\.")(1).toInt).toOption.exists(n => n >= 16 && n <= 31))

  /** ISO 3166-1 alpha-2 to its E.164 dial prefix: the original's table, all 128 rows. */
  val dialCodes: Map[String, String] = Map(
    "AD" -> "+376", "AE" -> "+971", "AF" -> "+93", "AG" -> "+1", "AL" -> "+355", "AM" -> "+374", "AO" -> "+244", "AR" -> "+54",
    "AT" -> "+43", "AU" -> "+61", "AZ" -> "+994", "BA" -> "+387", "BB" -> "+1", "BD" -> "+880", "BE" -> "+32", "BF" -> "+226",
    "BG" -> "+359", "BH" -> "+973", "BJ" -> "+229", "BN" -> "+673", "BO" -> "+591", "BR" -> "+55", "BS" -> "+1", "BW" -> "+267",
    "CA" -> "+1", "CH" -> "+41", "CL" -> "+56", "CM" -> "+237", "CN" -> "+86", "CO" -> "+57", "CR" -> "+506", "CY" -> "+357",
    "CZ" -> "+420", "DE" -> "+49", "DK" -> "+45", "DO" -> "+1", "DZ" -> "+213", "EC" -> "+593", "EE" -> "+372", "EG" -> "+20",
    "ES" -> "+34", "ET" -> "+251", "FI" -> "+358", "FJ" -> "+679", "FR" -> "+33", "GA" -> "+241", "GB" -> "+44", "GG" -> "+44",
    "GH" -> "+233", "GR" -> "+30", "GT" -> "+502", "HK" -> "+852", "HN" -> "+504", "HR" -> "+385", "HU" -> "+36", "ID" -> "+62",
    "IE" -> "+353", "IL" -> "+972", "IM" -> "+44", "IN" -> "+91", "IQ" -> "+964", "IS" -> "+354", "IT" -> "+39", "JE" -> "+44",
    "JM" -> "+1", "JO" -> "+962", "JP" -> "+81", "KE" -> "+254", "KH" -> "+855", "KR" -> "+82", "KW" -> "+965", "KZ" -> "+7",
    "LB" -> "+961", "LK" -> "+94", "LT" -> "+370", "LU" -> "+352", "LV" -> "+371", "MA" -> "+212", "MD" -> "+373", "ME" -> "+382",
    "MK" -> "+389", "MT" -> "+356", "MU" -> "+230", "MX" -> "+52", "MY" -> "+60", "MZ" -> "+258", "NA" -> "+264", "NG" -> "+234",
    "NI" -> "+505", "NL" -> "+31", "NO" -> "+47", "NP" -> "+977", "NZ" -> "+64", "OM" -> "+968", "PA" -> "+507", "PE" -> "+51",
    "PH" -> "+63", "PK" -> "+92", "PL" -> "+48", "PR" -> "+1", "PT" -> "+351", "PY" -> "+595", "QA" -> "+974", "RO" -> "+40",
    "RS" -> "+381", "SA" -> "+966", "SE" -> "+46", "SG" -> "+65", "SI" -> "+386", "SK" -> "+421", "SN" -> "+221", "TH" -> "+66",
    "TN" -> "+216", "TR" -> "+90", "TT" -> "+1", "TW" -> "+886", "TZ" -> "+255", "UA" -> "+380", "UG" -> "+256", "US" -> "+1",
    "UY" -> "+598", "UZ" -> "+998", "VE" -> "+58", "VI" -> "+1", "VN" -> "+84", "ZA" -> "+27", "ZM" -> "+260", "ZW" -> "+263"
  )

  def dialCode(country: String): Option[String] = dialCodes.get(country.toUpperCase)

  def http(base: String = "http://ip-api.com", bound: Duration = Duration.ofSeconds(2),
      now: () => Long = () => System.currentTimeMillis): Geo = IpApi(base.stripSuffix("/"), bound, now)

private final class IpApi(base: String, bound: Duration, now: () => Long) extends Geo:
  private val http  = HttpClient.newBuilder().connectTimeout(bound).build()
  private val cache = ConcurrentHashMap[String, (Long, Option[String])]()

  def lookup(address: Option[String]): GeoAnswer =
    val country = address.map(_.trim).filter(_.nonEmpty).flatMap { ip =>
      Geo.builtin.get(ip).orElse(if Geo.privateOrLoopback(ip) then None else cached(ip))
    }
    GeoAnswer(country, country.flatMap(Geo.dialCode))

  private def cached(ip: String): Option[String] =
    Option(cache.get(ip)).filter(_._1 > now()).map(_._2).getOrElse {
      val found = fetch(ip)
      found.foreach(c => cache.put(ip, (now() + Geo.CacheMillis, c)))
      found.flatten
    }

  /** `Some(country or none)` when ip-api answered; `None` when it could not be asked, so nothing is cached. */
  private def fetch(ip: String): Option[Option[String]] =
    Try {
      val req = HttpRequest.newBuilder(URI.create(s"$base/json/${Neighbour.segment(ip)}?fields=status,countryCode")).timeout(bound).GET().build()
      val r   = http.send(req, HttpResponse.BodyHandlers.ofString())
      Json.parse(r.body).map(b => if b.text("status").contains("success") then b.text("countryCode") else None)
    }.toOption.flatten
