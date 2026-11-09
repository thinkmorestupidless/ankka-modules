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
  /** The kinds the registry pseudonymises (FR-008); a service is stored as written and has no pseudonym. */
  val Kinds            = Ids.PersonKinds
  /** A reason is a code, never free text (FR-008: nothing written holds a real id; spec.md "a reason code and a reference"). */
  val ReasonCode       = "[a-z0-9_.-]{1,64}".r
  /** A real id here is a UUID: anything shaped like one, or a run of 8 hex digits, is refused as a reason. */
  private val IdShaped = "(?i).*[0-9a-f]{8}.*".r

  def reason(code: String): Boolean = ReasonCode.matches(code) && !IdShaped.matches(code.replace("-", ""))

  private val reasonProblem =
    "bad_request: reasonCode is a code of 1 to 64 lower-case letters, digits, '_', '.' or '-', and never shaped like an id"

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
    refuse(403, NotReidentifier, s"re-identifying at $brand needs audit_compliance_$brand and reidentify_$brand")

  def who(ctx: Ctx, p: Principal, raw: String): Answer =
    body[WhoAsked](raw).filterOrElse(w => reason(w.reasonCode), reasonProblem) match
      case Left(problem) => refused(400, problem)
      case Right(w) =>
        val identity = ctx.clients.componentClient.forKeyValueEntity(Identity.entityId(w.pseudonym)).call(Identity.get).invoke()
        if !identity.bound then
          // Unknown, so what was typed may be anything, a real id pasted by mistake among them: the record
          // names its digest, never the text. No brand to hold the caller to, so only a re-identifier is
          // told it is unknown.
          val typed = Ids.digest(Wiring.requiredKeys.registry, Own.Installation, w.pseudonym)
          if reidentifiesAnywhere(p) then
            recorded(ctx, p, Own.Installation, Who, typed, w.reasonCode, outcome = "refused", reasonCode = Some(UnknownPseudonym))
            refuse(404, UnknownPseudonym, "no such pseudonym")
          else refusedFor(ctx, p, Own.Installation, Who, typed, w.reasonCode)
        else if !Routes.reidentifies(p, identity.brand) then refusedFor(ctx, p, identity.brand, Who, w.pseudonym, w.reasonCode)
        else
          recorded(ctx, p, identity.brand, Who, w.pseudonym, w.reasonCode)
          json(200, obj("brand" -> Some(text(identity.brand)), "kind" -> Some(text(identity.kind)), "id" -> Some(text(identity.realId))))

  def pseudonym(ctx: Ctx, p: Principal, raw: String): Answer =
    val asked = for
      a <- body[PseudonymAsked](raw)
      _ <- Either.cond(reason(a.reasonCode), (), reasonProblem)
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
