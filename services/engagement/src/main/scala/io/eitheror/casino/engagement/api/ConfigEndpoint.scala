package io.eitheror.casino.engagement.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.engagement.{EngagementRuntime, Json}
import io.eitheror.casino.engagement.clients.{CrmAnswer, CrmCredentials}
import io.eitheror.casino.engagement.config.*

import scala.jdk.CollectionConverters.*

import Answers.*

/**
 * `/v1/casino/crm-config` (FR-004, FR-005; contracts/routes.md): the brand's Customer.io configuration for the
 * console, its three keys shown masked and written to the secret store, never to the entity; and the seed. A
 * `{brand}` other than this one is 404 `wrong_brand`.
 */
final class ConfigEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/casino/crm-config"):
  val acl: Acl = Routes.operatorHolding(ctx.slug, "brand_admin", "resource_admin", "platform_admin")(ctx.authenticate())

  get("/{brand}") { (brand: String) => if brand != ctx.slug then wrongBrand else ok(CrmConfigRoute.shown(ctx)) }

  putBody("/{brand}") { (brand: String, text: String) =>
    if brand != ctx.slug then wrongBrand
    else Json.parseObject(text).fold(badBody)(b => CrmConfigRoute.put(ctx, b, principal.subject))
  }

  post("/{brand}/seed") { (brand: String) => if brand != ctx.slug then wrongBrand else CrmConfigRoute.seed(ctx) }

object CrmConfigRoute:
  /** The body's names for the three keys, and the secret each is stored under. */
  val Keys: Vector[(String, String => String)] =
    Vector("trackApiKey" -> Secrets.track, "appApiKey" -> Secrets.app, "webhookSigningKey" -> Secrets.signing)

  private def stored(ctx: Ctx, name: String): Option[String] = ctx.clients.secrets.get(name)

  /** The configuration as the console shows it: every trigger present, `""` when absent; each key masked. */
  def shown(ctx: Ctx): JsonNode =
    val c = ctx.config
    Json.obj(
      Vector[(String, Any)]("siteId" -> c.siteId, "region" -> c.region, "fromEmail" -> c.fromEmail) ++
        Keys.map((field, name) => field -> Masking.mask(stored(ctx, name(ctx.slug)))) :+
        ("triggers" -> Json.obj(Seed.triggers.map(t => t -> Json.obj("transactionalId" -> c.triggers.getOrElse(t, "")))*))*
    )

  /** What a body says, or the first field it gets wrong. A field left out keeps what is stored. */
  def parse(body: JsonNode, current: CrmConfig): Either[String, (CrmConfig, Vector[(String, String)])] =
    def text(field: String): Either[String, Option[String]] =
      Option(body.get(field)).filterNot(_.isNull) match
        case None                     => Right(None)
        case Some(v) if v.isTextual   => Right(Some(v.asText.trim))
        case Some(_)                  => Left(field)
    val triggers: Either[String, Map[String, String]] =
      Option(body.get("triggers")).filterNot(_.isNull) match
        case None => Right(current.triggers)
        case Some(t) if !t.isObject => Left("triggers")
        case Some(t) =>
          t.properties.asScala.toVector.foldLeft[Either[String, Map[String, String]]](Right(current.triggers)) {
            case (Left(e), _) => Left(e)
            case (Right(m), entry) =>
              val (key, v) = (entry.getKey, entry.getValue)
              val id = Option(v).filter(_.isObject).flatMap(o => Option(o.get("transactionalId"))).filterNot(_.isNull)
              if !Seed.triggers.contains(key) then Left(s"triggers.$key")
              else if v == null || !v.isObject || id.exists(!_.isTextual) then Left(s"triggers.$key.transactionalId")
              else Right(id.map(_.asText.trim).filter(_.nonEmpty).fold(m - key)(m.updated(key, _)))
          }
    for
      site    <- text("siteId")
      region  <- text("region")
      from    <- text("fromEmail")
      _       <- from.filter(f => f.nonEmpty && !f.contains("@")).map(_ => "fromEmail").toLeft(())
      t       <- triggers
      secrets <- Keys.foldLeft[Either[String, Vector[(String, String)]]](Right(Vector.empty)) {
                   case (Left(e), _)            => Left(e)
                   case (Right(acc), (field, _)) => text(field).map(v => v.fold(acc)(x => acc :+ (field -> x)))
                 }
    yield (current.copy(siteId = site.getOrElse(current.siteId), region = region.fold(current.region)(CrmConfig.region),
      fromEmail = from.getOrElse(current.fromEmail), triggers = t), secrets)

  /** `PUT /{brand}`: the keys written to the secret store (a masked one keeps the stored), the rest to the entity. */
  def put(ctx: Ctx, body: JsonNode, operator: String): Answer =
    parse(body, ctx.config) match
      case Left(field) => refusal(400, "invalid_config", "field" -> field)
      case Right((next, sent)) =>
        val names     = Keys.toMap
        val rewritten = sent.flatMap { (field, incoming) =>
          val name   = names(field)(ctx.slug)
          val before = stored(ctx, name)
          val after  = Masking.merge(incoming, before)
          Option.when(after != before) {
            EngagementRuntime.secrets.write(ctx.clients.secrets, name, after)
            field
          }
        }
        ctx.components.forKeyValueEntity(EntityId(ctx.slug)).call(Configuration.put)
          .invoke(ConfigChange(next, operator, rewritten)): Unit
        ok(shown(ctx))

  /**
   * `POST /{brand}/seed`: one identify and one track per trigger at `_seed_<slug>`, in the enum's order. 422
   * `credentials_not_configured` with no site; 503 `crm_unavailable` when Customer.io took none of the calls.
   */
  def seed(ctx: Ctx): Answer =
    val c = ctx.config
    if !c.configured then refusal(422, "credentials_not_configured")
    else
      val keys   = EngagementRuntime.secrets.keys(ctx.clients.secrets, ctx.slug)
      val creds  = CrmCredentials(c.siteId, c.region, keys.track, keys.app)
      val person = Seed.person(ctx.slug)
      val crm    = EngagementRuntime.crm
      // The count is of the tracks taken: a track is what puts the event in the campaign builder's list.
      val tracked = Seed.pairs(ctx.slug).count { pair =>
        crm.identify(creds, person, pair.attributes): Unit
        accepted(crm.track(creds, person, pair.event, pair.tracked))
      }
      if tracked == 0 then refusal(503, "crm_unavailable")
      else ok(Json.obj("seeded" -> tracked, "person" -> person))

  private def accepted(a: CrmAnswer): Boolean = a match
    case CrmAnswer.Accepted(_) => true
    case _                     => false
