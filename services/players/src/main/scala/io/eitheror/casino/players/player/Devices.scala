package io.eitheror.casino.players.player

import io.eitheror.casino.contract.personal.Personal

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/**
 * What a log-in's user agent says of its device (spec 030 P8): the original's `UserAgentParser`, its rules
 * and its words. The user agent itself is never kept, only these families.
 */
final case class DeviceFamily(deviceType: String, platform: String, browser: String)

object Devices:
  val Unknown = "Unknown"

  /** The most devices a player keeps; past it the one seen longest ago goes. */
  val MaxDevices: Int = 50

  def family(userAgent: Option[String]): DeviceFamily =
    userAgent.map(_.trim).filter(_.nonEmpty).fold(DeviceFamily(Unknown, Unknown, Unknown))(ua =>
      DeviceFamily(deviceType(ua), platform(ua), browser(ua)))

  private def deviceType(ua: String): String =
    if ua.contains("Tablet") || ua.contains("iPad") then "Tablet"
    else if ua.contains("Mobile") || ua.contains("Android") then "Mobile"
    else "Desktop"

  private def platform(ua: String): String =
    if ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod") then "iOS"
    else if ua.contains("Android") then "Android"
    else if ua.contains("Windows") then "Windows"
    else if ua.contains("Mac OS X") || ua.contains("Macintosh") then "macOS"
    else if ua.contains("Linux") then "Linux"
    else Unknown

  // Every iOS browser carries "Safari" and names itself with its own token; Samsung Internet carries "Chrome".
  private def browser(ua: String): String =
    if ua.contains("Edg/") || ua.contains("Edge/") || ua.contains("EdgiOS") then "Edge"
    else if ua.contains("Firefox") || ua.contains("FxiOS") then "Firefox"
    else if ua.contains("OPR") || ua.contains("Opera") then "Opera"
    else if ua.contains("SamsungBrowser") then "Samsung Internet"
    else if ua.contains("Chrome") || ua.contains("CriOS") then "Chrome"
    else if ua.contains("Safari") then "Safari"
    else Unknown

  /**
   * The device a log-in came from: the digest of the fingerprint the client sent (never the fingerprint), or,
   * with none, its family, so two log-ins of one kind of device without a fingerprint are one device.
   */
  def key(fingerprint: Option[String], f: DeviceFamily): String =
    fingerprint.map(_.trim).filter(_.nonEmpty) match
      case Some(fp) => "fp:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fp.getBytes(UTF_8)))
      case None     => s"ua:${f.deviceType}/${f.platform}/${f.browser}"

  def seen(fingerprint: Option[String], userAgent: Option[String], country: Option[String], at: Instant): SeeDevice =
    val f = family(userAgent)
    SeeDevice(key(fingerprint, f), f.platform, f.browser, f.deviceType, country.map(_.trim.toUpperCase(java.util.Locale.ROOT)).filter(_.nonEmpty), at)

  /** The devices after one more log-in: a known one's last sighting moves, a new one is added, at most [[MaxDevices]]. */
  def after(devices: Vector[SeenDevice], e: PlayerEvent.DeviceSeen): Vector[SeenDevice] =
    val i = devices.indexWhere(_.key.reveal == e.key.reveal)
    val next =
      if i >= 0 then devices.updated(i, devices(i).copy(lastSeenAt = if e.at.isAfter(devices(i).lastSeenAt) then e.at else devices(i).lastSeenAt))
      else devices :+ SeenDevice(e.key, e.platform, e.browser, e.deviceType, e.country, e.at, e.at)
    // a new device past the most kept drops the one seen longest ago
    if next.size <= MaxDevices then next else next.sortBy(_.lastSeenAt).takeRight(MaxDevices)
