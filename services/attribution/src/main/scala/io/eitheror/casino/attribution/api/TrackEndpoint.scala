package io.eitheror.casino.attribution.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.attribution.{TagOutcome, Wiring}
import io.eitheror.casino.attribution.AttributionService
import io.eitheror.casino.attribution.api.Wire.{*, given}
import io.eitheror.casino.attribution.clicks.{Click, ClickRecord}
import io.eitheror.casino.attribution.tag.TagCheck
import io.eitheror.casino.contract.access.Routes
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets.UTF_8
import java.util.{HexFormat, UUID}
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The landing (contracts/routes.md *Public*; research R5): the original's path and shapes. One click per
 * click id, the click id the link carried answered back (minted only when none came), the tag verified
 * against what the brand holds, the address kept only as a salted digest. No cookie is set or read, so a
 * visitor who gave no consent is recorded like any other (FR-006). Nothing is logged but the click id and
 * the outcome.
 */
final class TrackEndpoint(api: Api) extends HttpEndpoint("/v1/track"):
  val acl: Acl = Acl.DenyAll

  withAcl(Routes.public) {
    postBody("/click") { (body: String) => click(body) }
  }

  private def click(body: String): Respond[Bytes] =
    Wire.read[ClickRequest](if body.trim.isEmpty then "{}" else body) match
      case Left(_) => answer(400, ClickError("the body is not a click"))
      case Right(c) =>
        c.btag.map(_.trim).filter(_.nonEmpty) match
          case None => answer(400, ClickError("btag is required"))
          case Some(tag) =>
            val id  = c.clickId.map(_.trim).filter(_.nonEmpty).getOrElse(UUID.randomUUID().toString)
            val now = api.now()
            val sources = TagCheck.live(api.components, () => Wiring.networks.get()())
            val first = TagCheck.decide(tag, sources, None)
            val outcome = first match
              case TagOutcome.Refused(_) if TagCheck.fetchKeysFor(tag, api.components, Wiring.fetch.get(), now) =>
                TagCheck.decide(tag, sources, None)
              case other => other
            val named   = outcome match
              case TagOutcome.Valid(n, _, _) => Some(n)
              case _                         => None
            val refused = outcome match
              case TagOutcome.Refused(reason) => Some(reason)
              case _                          => None
            val record = ClickRecord(
              clickId = id,
              network = named.map(_.network),
              affiliate = named.map(_.affiliate),
              link = named.map(_.link),
              campaign = named.flatMap(_.campaign),
              subIds = named.fold(Vector.empty[String])(_.subIds),
              at = now,
              deviceType = Clicks.deviceType(c.deviceType, c.userAgent.orElse(header("user-agent"))),
              country = c.country.map(_.trim.toUpperCase).filter(_.nonEmpty)
                .orElse(header(api.settings.countryHeader).map(_.trim.toUpperCase).filter(Clicks.isCountry)),
              addressDigest = Clicks.firstAddress(header("x-forwarded-for")).orElse(c.ip.map(_.trim).filter(_.nonEmpty))
                .map(Clicks.digest(salt(), _)),
              utm = Clicks.utm(c),
              refused = refused
            )
            api.components.forKeyValueEntity(EntityId(id)).call(Click.record).invoke(record): Unit
            TrackEndpoint.log.info("click {}: {}", id, refused.getOrElse("valid"))
            refused match
              case None         => ok(ClickAnswer(ok = true, id))
              case Some(reason) => ok(ClickAnswer(ok = false, id, Some(reason)))

  private def header(name: String): Option[String] = request.header(name)

  /** The click salt, read from the store once per process (research R5); made at start when absent. */
  private def salt(): String =
    Option(TrackEndpoint.salt.get()).getOrElse {
      val s = api.secrets.get(AttributionService.ClickSalt).getOrElse(throw IllegalStateException("no click salt in the store"))
      TrackEndpoint.salt.compareAndSet(null, s): Unit
      s
    }

object TrackEndpoint:
  private val log  = LoggerFactory.getLogger(classOf[TrackEndpoint])
  private val salt = AtomicReference[String](null)

  /** Forgets the cached salt: for a suite that starts a second service in one JVM. */
  def forgetSalt(): Unit = salt.set(null)

/** The click's pure parts: the device, the country, the address digest and the campaign values. */
object Clicks:
  private val Country = "[A-Z]{2}".r

  def isCountry(value: String): Boolean = Country.matches(value.trim.toUpperCase) && value.trim.toUpperCase != "XX"

  /** The device the body names, else the user agent's: `mobile`, `tablet`, `desktop`, or `unknown`. */
  def deviceType(named: Option[String], userAgent: Option[String]): String =
    named.map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse {
      userAgent.map(_.toLowerCase) match
        case None                                                                   => "unknown"
        case Some(ua) if ua.contains("ipad") || ua.contains("tablet")              => "tablet"
        case Some(ua) if Seq("iphone", "android", "mobile", "ipod").exists(ua.contains) => "mobile"
        case Some(ua) if ua.trim.nonEmpty                                           => "desktop"
        case _                                                                      => "unknown"
    }

  /** The visitor's address: the first of `X-Forwarded-For`, the one the gateway saw. */
  def firstAddress(forwarded: Option[String]): Option[String] =
    forwarded.flatMap(_.split(',').headOption).map(_.trim).filter(_.nonEmpty)

  /** HMAC-SHA256 of the address under the click salt, hex: comparable, never reversible to the address. */
  def digest(salt: String, address: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(salt.getBytes(UTF_8), "HmacSHA256"))
    HexFormat.of().formatHex(mac.doFinal(address.getBytes(UTF_8)))

  def utm(c: Wire.ClickRequest): Map[String, String] =
    Seq("utm_source" -> c.utmSource, "utm_medium" -> c.utmMedium, "utm_campaign" -> c.utmCampaign, "utm_content" -> c.utmContent,
      "utm_term" -> c.utmTerm).collect { case (k, Some(v)) if v.trim.nonEmpty => k -> v.trim }.toMap
