package io.eitheror.casino.audit.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.audit.Wiring
import io.eitheror.casino.audit.ids.Ids
import io.eitheror.casino.audit.own.Own
import io.eitheror.casino.audit.registry.{Bind, Identity, Pseudonym}
import io.eitheror.casino.contract.access.Routes

import scala.util.Try

import Wire.*

final case class WhoAsked(pseudonym: String = "", reasonCode: String = "")
final case class PseudonymAsked(brand: String = "", kind: String = "", id: String = "", reasonCode: String = "")

/**
 * `/v1/audit-identities` (FR-017, FR-018; contracts/routes.md): who a pseudonym is, and the pseudonym of
 * a real id, for a holder of the brand's re-identification role only. Every answer and every refusal is
 * a record visible to the brand; none of them holds the real id.
 */
final class IdentitiesEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/audit-identities"):
  val acl: Acl = Acl.DenyAll

  // Any member of staff reaches the routes, so a refusal is the route's and can be recorded.
  withAcl(Routes.staffMember(ctx.authenticate())) {
    postBody("/who") { (text: String) => IdentitiesRoute.who(ctx, principal, text) }
    postBody("/pseudonym") { (text: String) => IdentitiesRoute.pseudonym(ctx, principal, text) }
  }

object IdentitiesRoute:
  given JsonValueCodec[WhoAsked]       = JsonCodecMaker.make(CodecMakerConfig.withTransientNone(true))
  given JsonValueCodec[PseudonymAsked] = JsonCodecMaker.make(CodecMakerConfig.withTransientNone(true))

  val Who              = "audit.reidentify"
  val Lookup           = "audit.pseudonym"
  val NotReidentifier  = "not_reidentifier"
  val UnknownPseudonym = "unknown_pseudonym"
  val Kinds            = Set("player", "operator", "affiliate", "service")

  private def body[A: JsonValueCodec](text: String): Either[String, A] =
    Try(readFromString[A](if text.trim.isEmpty then "{}" else text)).toEither.left.map(_ => "bad_request: the body is not the route's shape")

  /** The brands a principal re-identifies at: each `reidentify_<slug>` it holds with the brand's compliance role. */
  private def reidentifiesAnywhere(p: Principal): Boolean =
    p.roles.exists(r => r.startsWith("reidentify_") && Routes.reidentifies(p, r.stripPrefix("reidentify_")))

  private def recorded(ctx: Ctx, p: Principal, brand: String, action: String, subject: String, reason: String,
      outcome: String = "done", reasonCode: Option[String] = None): Unit =
    Own.record(ctx.calls, brand, action, Own.staff(ctx.calls, brand, p.subject), "pseudonym", subject, outcome, reasonCode,
      Vector(Own.change("reason", reason)), ctx.now()): Unit

  private def refusedFor(ctx: Ctx, p: Principal, brand: String, action: String, subject: String, reason: String): Answer =
    recorded(ctx, p, brand, action, subject, reason, outcome = "refused", reasonCode = Some(NotReidentifier))
    refuse(403, NotReidentifier, s"re-identifying at $brand needs brand_compliance, brand_$brand and reidentify_$brand")

  def who(ctx: Ctx, p: Principal, raw: String): Answer =
    body[WhoAsked](raw).filterOrElse(_.reasonCode.nonEmpty, "bad_request: a reason code is required") match
      case Left(problem) => refused(400, problem)
      case Right(w) =>
        val identity = ctx.clients.componentClient.forKeyValueEntity(Identity.entityId(w.pseudonym)).call(Identity.get).invoke()
        if !identity.bound then
          // Unknown: no brand to hold the caller to, so only a re-identifier is told it is unknown.
          if reidentifiesAnywhere(p) then
            recorded(ctx, p, Own.Installation, Who, w.pseudonym, w.reasonCode, outcome = "refused", reasonCode = Some(UnknownPseudonym))
            refuse(404, UnknownPseudonym, "no such pseudonym")
          else refusedFor(ctx, p, Own.Installation, Who, w.pseudonym, w.reasonCode)
        else if !Routes.reidentifies(p, identity.brand) then refusedFor(ctx, p, identity.brand, Who, w.pseudonym, w.reasonCode)
        else
          recorded(ctx, p, identity.brand, Who, w.pseudonym, w.reasonCode)
          json(200, obj("brand" -> Some(text(identity.brand)), "kind" -> Some(text(identity.kind)), "id" -> Some(text(identity.realId))))

  def pseudonym(ctx: Ctx, p: Principal, raw: String): Answer =
    val asked = for
      a <- body[PseudonymAsked](raw)
      _ <- Either.cond(a.reasonCode.nonEmpty, (), "bad_request: a reason code is required")
      _ <- Either.cond(Kinds(a.kind), (), s"bad_request: kind is one of ${Kinds.toVector.sorted.mkString(", ")}")
      _ <- Either.cond(a.id.nonEmpty && a.brand.nonEmpty, (), "bad_request: brand and id are required")
    yield a
    asked match
      case Left(problem) => refused(400, problem)
      case Right(a) if !Routes.reidentifies(p, a.brand) =>
        val brand = if ctx.everyBrand(a.brand) then a.brand else Own.Installation
        refusedFor(ctx, p, brand, Lookup, "", a.reasonCode)
      case Right(a) =>
        val registryId = Ids.registryId(Wiring.requiredKeys.registry, a.brand, a.kind, a.id)
        val issued     = ctx.clients.componentClient.forKeyValueEntity(Pseudonym.entityId(registryId)).call(Pseudonym.issue).invoke()
        ctx.calls.bind(issued.pseudonym, Bind(a.brand, a.kind, a.id)): Unit
        recorded(ctx, p, a.brand, Lookup, issued.pseudonym, a.reasonCode)
        json(200, obj("pseudonym" -> Some(text(issued.pseudonym)), "issued" -> Some(issued.issued.toString)))
