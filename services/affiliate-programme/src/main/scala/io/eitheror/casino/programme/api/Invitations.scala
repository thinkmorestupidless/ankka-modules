package io.eitheror.casino.programme.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.programme.ProgrammeCodecs
import io.eitheror.casino.programme.accounts.*
import io.eitheror.casino.programme.api.Wire.*
import io.eitheror.casino.programme.links.{CreateInviteLink, InviteLink, InviteLinks}
import io.eitheror.casino.programme.sealing.Identity

import java.time.Instant
import java.util.UUID

/**
 * Invite links (M31 to M33) and the public join through one (P3, P4, FR-027): a person who applies
 * through a link is a pending sub of its affiliate, for the affiliate to approve.
 */
object Invitations:
  final case class InviteLinkBody(brand: Option[String] = None)
  final case class LinkRow(linkId: String, token: String, brandId: String, status: String, createdAt: Option[Instant])
  final case class Created(linkId: String, token: String, createdAt: Instant)
  final case class Preview(affiliateName: String, brandId: String)
  final case class JoinBody(email: Option[String] = None, name: Option[String] = None)

  given JsonValueCodec[InviteLinkBody]  = ProgrammeCodecs.wire[InviteLinkBody]
  given rowsCodec: JsonValueCodec[Vector[LinkRow]] = JsonCodecMaker.make(CodecMakerConfig.withTransientNone(true))
  given JsonValueCodec[Created]         = ProgrammeCodecs.wire[Created]
  given JsonValueCodec[Preview]         = ProgrammeCodecs.wire[Preview]
  given JsonValueCodec[JoinBody]        = ProgrammeCodecs.wire[JoinBody]

import Invitations.{*, given}

final class Invitations(api: Api):
  /** The affiliate's live links, at `brand` when named. */
  def of(affiliate: String, brand: Option[String]): Vector[LinkRow] =
    api.views.forView(InviteLinks).ask(InviteLinks.ofAffiliate, "affiliate" -> affiliate)
      .filter(l => l.live && brand.forall(_ == l.brand))
      .map(l => LinkRow(l.linkId, l.token, l.brand, l.status, l.createdAt))

  def create(affiliate: String, brand: String): Created =
    val token = InviteLink.mint()
    val at    = api.now()
    val s = api.components.forKeyValueEntity(InviteLink.entityId(token)).call(InviteLink.createLink)
      .invoke(CreateInviteLink(token, UUID.randomUUID().toString, affiliate, brand, at))
    Created(s.linkId, token, at)

  /** Revoked when the affiliate holds a link of that id; false when they hold none. */
  def revoke(affiliate: String, linkId: String): Boolean =
    api.views.forView(InviteLinks).ask(InviteLinks.ofAffiliate, "affiliate" -> affiliate).find(_.linkId == linkId) match
      case None => false
      case Some(l) =>
        api.components.forKeyValueEntity(InviteLink.entityId(l.token)).call(InviteLink.revoke).invoke(api.now()): Unit
        true

  private def link(token: String, brand: String) =
    Option(api.components.forKeyValueEntity(InviteLink.entityId(token)).call(InviteLink.get).invoke()).filter(l => l.live && l.brand == brand)

  /** P3: whose link it is, by the name they go by. */
  def preview(token: String, brand: Option[String]): Respond[Bytes] =
    brand.flatMap(link(token, _)) match
      case None => refuse(404, "not_found")
      case Some(l) =>
        val a = api.account(l.affiliate)
        val name = api.identity(a.id, a).toOption.flatten.map(id => id.displayName.getOrElse(id.name)).getOrElse("")
        ok(Preview(name, l.brand))

  /**
   * P4: an application through the link, counted against its hour's cap. A new email is a new account
   * under the link's affiliate; one already holding a membership at the brand was submitted already.
   */
  def join(token: String, brand: Option[String], b: JoinBody): Respond[Bytes] =
    val enrolment = Enrolment(api)
    val email     = b.email.map(_.trim).getOrElse("")
    if email.isEmpty then refuse(400, "email_required")
    else if enrolment.address(Some(email)).isEmpty then refuse(400, "email_invalid")
    else
      brand.flatMap(link(token, _)) match
        case None => refuse(404, InviteLink.NotFound)
        case Some(l) =>
          api.components.forKeyValueEntity(InviteLink.entityId(token)).call(InviteLink.use).invoke(api.now()): Unit
          enrolment.lookup(email).fold(e => refuse(503, "store_unavailable", e), { (digest, known) =>
            if known.exists(a => api.membership(a, l.brand).holds) then refuse(409, "already_submitted")
            else
              val affiliate = known.getOrElse(Affiliate.newId())
              val made =
                if known.isDefined then Right(())
                else enrolment.open(affiliate, digest, Identity(b.name.getOrElse(email), b.name, email), Some(l.affiliate), 0,
                  AccountFields(), "invite-link")
              made.fold(e => refuse(503, "store_unavailable", e), { _ =>
                api.components.forEventSourcedEntity(Membership.entityId(affiliate, l.brand)).call(Membership.applyFor)
                  .invoke(Apply(affiliate, l.brand, Application(api.now(), None, "invite-link", Some(l.affiliate)))): Unit
                answer(201, Answers.Applied(affiliate, Membership.Pending))
              })
          })
