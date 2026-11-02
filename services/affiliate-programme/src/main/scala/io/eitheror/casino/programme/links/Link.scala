package io.eitheror.casino.programme.links

import com.thinkmorestupidless.ankka.core.{ComponentId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.security.SecureRandom
import java.time.Instant

/**
 * A tracking link (data-model.md *Link*): an affiliate's at a brand, its signed tag (FR-023) and, when
 * imported, the original's `btag` UUID, which the legacy tag document lists during the transition.
 */
final case class LinkState(
    id: String = "",
    affiliate: String = "",
    brand: String = "",
    campaign: Option[String] = None,
    landingPath: Option[String] = None,
    utm: Map[String, String] = Map.empty,
    subIds: Vector[String] = Vector.empty,
    tag: Option[String] = None,
    legacyBtag: Option[String] = None,
    createdBy: Option[String] = None,
    createdAt: Option[Instant] = None,
    revokedAt: Option[Instant] = None
):
  def exists: Boolean = id.nonEmpty
  def live: Boolean   = exists && revokedAt.isEmpty

final case class CreateLink(id: String, affiliate: String, brand: String, campaign: Option[String], landingPath: Option[String],
    utm: Map[String, String], subIds: Vector[String], tag: Option[String], legacyBtag: Option[String], by: String, at: Instant)

object LinkCodecs:
  given Serializer[LinkState]  = ProgrammeCodecs.serializer[LinkState]("link")
  given Serializer[CreateLink] = ProgrammeCodecs.serializer[CreateLink]("create-link")
  given Serializer[Instant]    = ProgrammeCodecs.serializer[Instant]("instant")

import LinkCodecs.given

final class Link(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[LinkState]:
  def emptyState: LinkState = LinkState()

  /** Made once; a repeat (an import run again) changes nothing. */
  def create(cmd: CreateLink): Effect[LinkState] =
    if currentState.exists then effects.reply(currentState)
    else
      effects.updateState(LinkState(cmd.id, cmd.affiliate, cmd.brand, cmd.campaign, cmd.landingPath, cmd.utm, cmd.subIds, cmd.tag,
        cmd.legacyBtag, Some(cmd.by), Some(cmd.at))).thenReplyState

  /** Listed on the revocation list from now on (FR-023). */
  def revoke(at: Instant): Effect[LinkState] =
    if !currentState.exists then effects.error("link_not_found", ErrorCode.NotFound)
    else if currentState.revokedAt.isDefined then effects.reply(currentState)
    else effects.updateState(currentState.copy(revokedAt = Some(at))).thenReplyState

  def get: ReadOnlyEffect[LinkState] = effects.reply(currentState)

object Link extends KeyValueEntity.Companion[Link, LinkState](
      componentId = ComponentId("link"),
      stateSerializer = LinkCodecs.given_Serializer_LinkState
    ):
  private val random = SecureRandom()
  private val Base32 = "abcdefghijklmnopqrstuvwxyz234567"

  /** A new link id: `lnk-` and 10 random bytes in base32, within the tag's id grammar. */
  def newId(): String =
    val bytes = new Array[Byte](10)
    random.nextBytes(bytes)
    val bits = BigInt(1, bytes)
    "lnk-" + (0 until 16).map(i => Base32(((bits >> (5 * (15 - i))) & 31).toInt)).mkString

  /**
   * The link an affiliate is given at a brand on activation, campaign `default` (`S/NetworkTree.scala:178-212`):
   * its id derived from the membership, so a repeat of the activation makes no second link.
   */
  def defaultIdOf(affiliate: String, brand: String): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(s"default:$affiliate/$brand".getBytes("UTF-8"))
    val bits   = BigInt(1, digest.take(10))
    "lnk-" + (0 until 16).map(i => Base32(((bits >> (5 * (15 - i))) & 31).toInt)).mkString

  def create(context: KeyValueEntityContext) = new Link(context)

  val createLink = command("create")(_.create)
  val revoke     = command("revoke")(_.revoke)
  val get        = query("get")(_.get)
