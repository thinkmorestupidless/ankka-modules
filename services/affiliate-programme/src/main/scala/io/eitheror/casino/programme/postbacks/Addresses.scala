package io.eitheror.casino.programme.postbacks

import java.net.{Inet4Address, Inet6Address, InetAddress, URI}
import scala.util.Try

/**
 * Which addresses the programme calls for an affiliate: a postback is an address the affiliate sets,
 * so it must not reach the cluster's own (loopback, private, link-local, CGNAT or the metadata
 * service). Checked when it is written, by its host, and when it is sent, by what the host resolves to.
 */
object Addresses:
  /** The reason an address is refused, or nothing. `plainHttp` admits http and any host: a local run and the suites. */
  def refusal(url: String, plainHttp: Boolean): Option[String] =
    // A template's placeholders (`{click_id}`) are not a URI's characters; what they stand for is.
    Try(URI.create(url.trim.replaceAll("\\{[^}]*\\}", "x"))).toOption match
      case None => Some("not an address")
      case Some(u) =>
        val scheme = Option(u.getScheme).map(_.toLowerCase)
        val host   = Option(u.getHost).map(_.toLowerCase.stripPrefix("[").stripSuffix("]"))
        if plainHttp then Option.when(!scheme.exists(Set("http", "https")) || host.isEmpty)("not an http address")
        else if !scheme.contains("https") then Some("not https")
        else if host.isEmpty then Some("no host")
        else if host.exists(h => h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal"))
        then Some("an internal host")
        else literal(host.get).filter(internal).map(_ => "an internal address")

  /** The reason a send is refused, from what the host resolves to now (a name may change after it was written). */
  def resolvedRefusal(url: String, plainHttp: Boolean): Option[String] =
    if plainHttp then None
    else
      refusal(url, plainHttp).orElse {
        val host = URI.create(url.trim.replaceAll("\\{[^}]*\\}", "x")).getHost
        Try(InetAddress.getAllByName(host).toVector).toOption match
          case None                                   => Some("the host does not resolve")
          case Some(addresses) if addresses.exists(internal) => Some("the host resolves to an internal address")
          case _                                      => None
      }

  /** An address written as one (`10.0.0.1`, `::1`), never a name: a name is not resolved at write time. */
  private def literal(host: String): Option[InetAddress] =
    val looksLiteral = host.forall(c => c.isDigit || c == '.') || host.contains(':')
    Option.when(looksLiteral)(Try(InetAddress.getByName(host)).toOption).flatten

  def internal(a: InetAddress): Boolean =
    a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress || (a match
      case v4: Inet4Address =>
        val b = v4.getAddress.map(_ & 0xff)
        (b(0) == 100 && (b(1) & 0xc0) == 64) || b(0) == 0 || (b(0) == 169 && b(1) == 254)
      case v6: Inet6Address =>
        val b = v6.getAddress.map(_ & 0xff)
        (b(0) & 0xfe) == 0xfc || isMapped(b) && internal(InetAddress.getByAddress(b.drop(12).map(_.toByte)))
      case _ => false)

  private def isMapped(b: Array[Int]): Boolean = b.take(10).forall(_ == 0) && b(10) == 0xff && b(11) == 0xff
