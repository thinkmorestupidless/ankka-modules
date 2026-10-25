package io.eitheror.casino.players.port

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

/**
 * The one documented translation between the original's player routes and this service's
 * (contracts/wire.md), applied in one place: a recorded request, its placeholders already filled, becomes
 * this service's, and this service's answer is read back in the recording's terms before `Replay`
 * compares. Everything the tables do not name is replayed as recorded.
 */
object WireTranslation:

  /** What a path needs that it does not carry: the brand, and the player the replay signed in as. */
  final case class Context(brand: String, player: Option[String])

  private val Me          = """/v1/casino/players/([^/]+)/me/(activity-history|recently-played|favourites)(.*)""".r
  private val Emails      = """/v1/casino/players/([^/]+)/emails""".r
  private val EmailChange = """/v1/casino/players/([^/]+)/email-change""".r
  private val Sessions    = """/v1/casino/sessions/([^/?]+)""".r
  private val Operator    = """/v1/casino/([^/]+)/players/([^/]+)/(suspend|reinstate|vip-tier|pot-vip|reset-password|date-of-birth|clear-suspect-flag|sessions/.+)""".r
  private val BrandConfig = """/v1/casino/([^/]+)/brand-config""".r
  private val BrandScoped = """/v1/casino/brands/([^/]+)/(merge-tags|messages/preview)""".r
  private val NotABrand   = Set("players", "sessions", "backoffice", "brands", "geo")

  /** The recorded path (with its query) as this service serves it. */
  def path(method: String, recorded: String, ctx: Context): String =
    val (p, query) = recorded.split('?') match
      case Array(p)    => (p, None)
      case Array(p, q) => (p, Some(q))
      case parts       => (parts.head, Some(parts.tail.mkString("?")))
    def withQuery(path: String) = query.fold(path)(q => s"$path?$q")
    def player = ctx.player.getOrElse(sys.error(s"$method $recorded needs the player the replay signed in as"))
    p match
      case Me(brand, route, rest)                     => withQuery(s"/v1/casino/players/$brand/$player/$route$rest")
      case Emails(brand)                              => withQuery(s"/v1/casino/players/$brand/$player/emails")
      case EmailChange(brand)                         => withQuery(s"/v1/casino/players/$brand/$player/email-change")
      case Sessions(brand) if query.exists(_.startsWith("playerId=")) =>
        s"/v1/casino/sessions/$brand/players/${URLDecoder.decode(query.get.stripPrefix("playerId=").takeWhile(_ != '&'), UTF_8)}"
      case Operator(brand, id, rest) if !NotABrand(brand) => withQuery(s"/v1/casino/backoffice/$brand/players/$id/$rest")
      case BrandConfig(brand) if !NotABrand(brand)      => withQuery(s"/v1/casino/backoffice/$brand/brand-config")
      case BrandScoped(brand, route)                    => withQuery(s"/v1/casino/backoffice/$brand/$route")
      case "/internal/keycloak-webhook"                 => s"/internal/realm/${ctx.brand}/webhook"
      case _                                            => recorded

  /** The recorded body as this service takes it: a registration's `trackingContext.btag` as `btag`. */
  def body(path: String, recorded: Option[JsonNode]): Option[JsonNode] =
    recorded.map {
      case o: ObjectNode if path.endsWith("/register") && o.has("trackingContext") && !o.has("btag") =>
        val copy = o.deepCopy()
        Option(o.get("trackingContext")).flatMap(t => Option(t.get("btag"))).foreach(b => copy.set[JsonNode]("btag", b))
        copy.remove("trackingContext")
        copy
      case other => other
    }

  /**
   * This service's answer in the recording's terms: an object keeps only the fields the original
   * answered (this service may say more, `message` beside `error` for one, never less), recursively; an
   * array is compared element by element on the same rule. Anything else is as observed.
   */
  def answer(recorded: Option[JsonNode], observed: Option[JsonNode]): Option[JsonNode] =
    (recorded, observed) match
      case (Some(r), Some(o)) => Some(narrow(r, o))
      case _                  => observed

  private def narrow(recorded: JsonNode, observed: JsonNode): JsonNode =
    (recorded, observed) match
      case (r: ObjectNode, o: ObjectNode) =>
        val out = o.objectNode()
        r.fieldNames.asScala.foreach(f => Option(o.get(f)).foreach(v => out.set[JsonNode](f, narrow(r.get(f), v))))
        out
      case (r, o) if r.isArray && o.isArray =>
        val out = o.deepCopy[JsonNode]().asInstanceOf[com.fasterxml.jackson.databind.node.ArrayNode]
        out.removeAll()
        o.elements.asScala.zipWithIndex.foreach((v, i) => out.add(if i < r.size then narrow(r.get(i), v) else v))
        out
      case (_, o) => o

  /** A recorded answer that was the original's plain-text `400` naming the three fields (a listed divergence). */
  def plainMissingFields(recorded: Option[JsonNode]): Boolean =
    recorded.exists(n => n.isTextual && n.asText().contains("email, password, and currency are required"))
