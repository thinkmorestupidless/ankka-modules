package io.eitheror.casino.audit.api

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.SignedAccess
import io.eitheror.casino.audit.exports.{Export, ExportFormat, ExportState, StartExport}
import io.eitheror.casino.audit.own.Own
import io.eitheror.casino.contract.access.Routes

import java.time.Instant
import scala.concurrent.duration.*
import scala.util.Try

import Wire.*

final case class ExportAsked(key: String = "", brand: String = "", from: Option[String] = None, to: Option[String] = None)
final case class FormatAsked(format: String = "")

/**
 * `/v1/audit-exports` (FR-014; contracts/routes.md): a brand's records for a period as one file in the
 * format the brand's regulator agreed, under a key, so the same key again answers the same export; its
 * status, and once done a URL signed for 15 minutes; and the format, set by the installation's owner.
 * Each request, each completion and each change of format is a record.
 */
final class ExportsEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/audit-exports"):
  val acl: Acl = Acl.DenyAll

  withAcl(Routes.auditCompliance(ctx.authenticate())) {
    postBody("") { (text: String) => ExportsRoute.start(ctx, principal, text) }
    get("/{id}") { (id: String) => ExportsRoute.status(ctx, principal, id) }
  }

  withAcl(Routes.installationOwner(ctx.authenticate())) {
    putBody("/format/{brand}") { (brand: String, text: String) => ExportsRoute.format(ctx, principal, brand, text) }
  }

object ExportsRoute:
  given JsonValueCodec[ExportAsked] = JsonCodecMaker.make(CodecMakerConfig.withTransientNone(true))
  given JsonValueCodec[FormatAsked] = JsonCodecMaker.make(CodecMakerConfig.withTransientNone(true))

  val BadRequest = "bad_request"
  val UnknownExport = "unknown_export"
  /** A key is an object's name segment: letters, digits, `.`, `_` and `-`. */
  private val KeyPattern = "[A-Za-z0-9._-]{1,128}"

  private def body[A: JsonValueCodec](text: String): Either[String, A] =
    Try(readFromString[A](if text.trim.isEmpty then "{}" else text)).toEither.left.map(_ => s"$BadRequest: the body is not the route's shape")

  private def instant(field: String, v: Option[String]): Either[String, Option[Instant]] =
    v.fold(Right(None))(t => Try(Instant.parse(t)).toOption.map(Some(_)).toRight(s"$BadRequest: $field '$t' is not an instant"))

  /** Whether a caller may export the brand, every record of it of either visibility (FR-020), or the refusal. */
  private def scope(p: Principal, brand: String): Either[String, Option[String]] =
    Routes.auditScope(p) match
      case Routes.BrandSet.Every                       => Right(None)
      case Routes.BrandSet.Only(slugs) if slugs(brand) => Right(None)
      case _                                           => Left(s"${RecordsRoute.BrandNotInRoles}: $brand is not one of your brands")

  private def shown(id: String, s: ExportState, url: Option[String]): Answer =
    json(200, obj(
      "id" -> Some(text(id)), "brand" -> Some(text(s.brand)), "format" -> Some(text(s.format)), "status" -> Some(text(s.status)),
      "count" -> s.count.map(_.toString), "url" -> url.map(text)
    ))

  def start(ctx: Ctx, p: Principal, raw: String): Answer =
    val asked = for
      b    <- body[ExportAsked](raw)
      _    <- Either.cond(b.key.matches(KeyPattern), (), s"$BadRequest: key is 1 to 128 letters, digits, '.', '_' or '-'")
      _    <- Either.cond(ctx.everyBrand(b.brand) && b.brand != Own.Installation, (), s"$BadRequest: brand '${b.brand}' is not one read here")
      from <- instant("from", b.from)
      to   <- instant("to", b.to)
      _    <- Either.cond(from.zip(to).forall((f, t) => f.isBefore(t)), (), s"$BadRequest: from is not before to")
    yield (b, from, to)
    asked match
      case Left(problem) => refused(400, problem)
      case Right((b, from, to)) =>
        scope(p, b.brand) match
          case Left(problem) => refused(403, problem)
          case Right(visibility) =>
            val workflow = ctx.clients.componentClient.forWorkflow(Export.entityId(b.key))
            val known    = workflow.call(Export.get).invoke()
            if known.started then
              if known.brand == b.brand then json(202, obj("id" -> Some(text(b.key)), "status" -> Some(text(known.status))))
              else refuse(409, "key_reused", s"the key ${b.key} names an export of another brand")
            else
              val format = ctx.clients.componentClient.forKeyValueEntity(ExportFormat.entityId(b.brand)).call(ExportFormat.get).invoke().format
              val actor  = Own.staff(ctx.calls, b.brand, p.subject)
              val s      = workflow.call(Export.start).invoke(StartExport(b.brand, format, from, to, visibility, actor, ctx.now()))
              Export.recorded(ctx.calls, s, Own.change("status", Export.Running))
              json(202, obj("id" -> Some(text(b.key)), "status" -> Some(text(s.status))))

  def status(ctx: Ctx, p: Principal, id: String): Answer =
    val s = ctx.clients.componentClient.forWorkflow(Export.entityId(id)).call(Export.get).invoke()
    if !s.started || scope(p, s.brand).isLeft then refuse(404, UnknownExport, s"no export $id you may see")
    else
      val url = s.objectName.filter(_ => s.status == Export.Done)
        .map(n => ctx.clients.storage.bucket(Export.Bucket).signedUrl(n, SignedAccess.Read, 15.minutes).toString)
      shown(id, s, url)

  def format(ctx: Ctx, p: Principal, brand: String, raw: String): Answer =
    body[FormatAsked](raw).filterOrElse(f => ExportFormat.Formats(f.format), s"$BadRequest: format is csv or jsonl") match
      case Left(problem) => refused(400, problem)
      case Right(f) if !ctx.everyBrand(brand) || brand == Own.Installation => refuse(404, "unknown_brand", s"no brand $brand is read here")
      case Right(f) =>
        val ref    = ctx.clients.componentClient.forKeyValueEntity(ExportFormat.entityId(brand))
        val before = ref.call(ExportFormat.get).invoke().format
        ref.call(ExportFormat.set).invoke(f.format): Unit
        Own.record(ctx.calls, brand, "audit.export-format", Own.staff(ctx.calls, brand, p.subject), "brand", brand, "done", None,
          Vector(io.eitheror.casino.audit.stored.StoredChange("format", Some(before), Some(f.format))), ctx.now()): Unit
        json(200, obj("brand" -> Some(text(brand)), "format" -> Some(text(f.format))))
