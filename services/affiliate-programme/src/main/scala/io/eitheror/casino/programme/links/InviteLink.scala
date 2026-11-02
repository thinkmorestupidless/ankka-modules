package io.eitheror.casino.programme.links

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, ErrorCode, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.programme.ProgrammeCodecs

import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat

/**
 * An affiliate's invite link at a brand (data-model.md *InviteLink*), id its token: a person who applies
 * through it is a pending sub of the affiliate (FR-027). The original capped a link's applications so a
 * leaked link cannot flood the programme (`S/AffiliateRoutes.scala:2357`); here 50 in a clock hour.
 */
final case class InviteLinkState(
    token: String = "",
    linkId: String = "",
    affiliate: String = "",
    brand: String = "",
    status: String = "",
    createdAt: Option[Instant] = None,
    hour: Option[Instant] = None,
    used: Int = 0,
    revokedAt: Option[Instant] = None
):
  def exists: Boolean = token.nonEmpty
  def live: Boolean   = exists && status == InviteLink.Active

final case class CreateInviteLink(token: String, linkId: String, affiliate: String, brand: String, at: Instant)

object InviteLinkCodecs:
  given Serializer[InviteLinkState]  = ProgrammeCodecs.serializer[InviteLinkState]("invite-link")
  given Serializer[CreateInviteLink] = ProgrammeCodecs.serializer[CreateInviteLink]("create-invite-link")
  given Serializer[Instant]          = ProgrammeCodecs.serializer[Instant]("instant")

import InviteLinkCodecs.given

final class InviteLink(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[InviteLinkState]:
  def emptyState: InviteLinkState = InviteLinkState()

  /** Made once; a repeat changes nothing. */
  def create(cmd: CreateInviteLink): Effect[InviteLinkState] =
    if currentState.exists then effects.reply(currentState)
    else
      effects.updateState(InviteLinkState(cmd.token, cmd.linkId, cmd.affiliate, cmd.brand, InviteLink.Active, Some(cmd.at))).thenReplyState

  /** One application through the link: refused when it is not live, or past the hour's cap. */
  def use(at: Instant): Effect[InviteLinkState] =
    val s = currentState
    if !s.live then effects.error(InviteLink.NotFound, ErrorCode.NotFound)
    else
      val hour = at.truncatedTo(ChronoUnit.HOURS)
      val used = if s.hour.contains(hour) then s.used else 0
      if used >= InviteLink.PerHour then effects.error(s"${InviteLink.TooMany}: $used applications this hour", ErrorCode.Conflict)
      else effects.updateState(s.copy(hour = Some(hour), used = used + 1)).thenReplyState

  def revoke(at: Instant): Effect[InviteLinkState] =
    if !currentState.exists then effects.error("not_found", ErrorCode.NotFound)
    else if !currentState.live then effects.reply(currentState)
    else effects.updateState(currentState.copy(status = InviteLink.Revoked, revokedAt = Some(at))).thenReplyState

  def get: ReadOnlyEffect[InviteLinkState] = effects.reply(currentState)

object InviteLink extends KeyValueEntity.Companion[InviteLink, InviteLinkState](
      componentId = ComponentId("invite-link"),
      stateSerializer = InviteLinkCodecs.given_Serializer_InviteLinkState
    ):
  val Active   = "active"
  val Revoked  = "revoked"
  val PerHour  = 50
  val NotFound = "link_not_found_or_expired"
  val TooMany  = "too_many_requests"

  private val random = SecureRandom()

  /** A new token: 16 random bytes in hex, as long as the original's (a UUID without its dashes). */
  def mint(): String =
    val b = new Array[Byte](16)
    random.nextBytes(b)
    HexFormat.of().formatHex(b)

  def entityId(token: String): EntityId = EntityId(token)

  def create(context: KeyValueEntityContext) = new InviteLink(context)

  val createLink = command("create")(_.create)
  val use        = command("use")(_.use)
  val revoke     = command("revoke")(_.revoke)
  val get        = query("get")(_.get)

/** One row per invite link: an affiliate's links (M31). */
final class InviteLinksView extends View[InviteLinkState, InviteLinkState]:
  def onChange(s: InviteLinkState): Effect = effects.updateRow(s)

object InviteLinks extends View.Companion[InviteLinksView, InviteLinkState, InviteLinkState](
      componentId = ComponentId("invite-links"),
      source = ChangeSource.stateOf(InviteLink),
      rowSerializer = InviteLinkCodecs.given_Serializer_InviteLinkState
    ):
  val ofAffiliate = query("of-affiliate")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'affiliate' = :affiliate ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new InviteLinksView
