package io.eitheror.casino.payouts.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.payouts.Json
import io.eitheror.casino.payouts.limits.{LimitsState, Pair, SetLimits, WithdrawalLimits}

import scala.jdk.CollectionConverters.*

import Answers.*

/**
 * `/v1/casino/brands/<slug>/withdrawal-config` (contracts/routes.md; R10): the brand's weekly and monthly
 * withdrawal limits per currency. The ACL admits an operator of the brand; the routes ask for an admin
 * role, as the original's did, and any other is `forbidden`.
 */
final class ConfigEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/brands/${ctx.slug}/withdrawal-config"):
  val acl: Acl = Routes.operator(ctx.slug, ctx.authenticate())

  private def admin(f: => Reply): Reply = if principal.roles.exists(ConfigEndpoint.AdminRoles) then f else forbidden

  private def limits = ctx.components.forEventSourcedEntity(EntityId(ctx.slug))

  get("/") { () => admin(ok(ConfigEndpoint.render(ctx.limits))) }

  putBody("/") { (text: String) =>
    admin(Json.parseObject(text).fold(badBody) { b =>
      ConfigEndpoint.parse(b) match
        case Left(field) => ConfigEndpoint.invalid(field)
        case Right(pairs) =>
          val a = limits.call(WithdrawalLimits.set).invoke(SetLimits(pairs, principal.subject, ctx.now()))
          a.refusal.fold(ok(ConfigEndpoint.render(a.state)))(ConfigEndpoint.invalid)
    })
  }

object ConfigEndpoint:
  /** The roles the original admits to `withdrawal-config` (R6). */
  val AdminRoles: Set[String] = Set("brand_admin", "resource_admin", "platform_admin")

  def invalid(field: String): Reply = refusal(400, "INVALID_REQUEST", s"$field is not a valid limit", "field" -> field)

  /** `{limits: {<currency>: {weekly, monthly}}}`, an unset limit `null`. */
  def render(s: LimitsState): JsonNode =
    def limit(v: Option[Long]): Any = v.fold[Any](null)(identity)
    val byCurrency = s.limits.toVector.sortBy(_._1).map((code, p) => code -> Json.obj("weekly" -> limit(p.weekly), "monthly" -> limit(p.monthly)))
    Json.obj("limits" -> Json.obj(byCurrency*))

  /**
   * The body's pairs, or the first field that is not one: `limits`, `limits.<currency>`, or a window that
   * is not a whole number of minor units. A negative amount or an unknown currency is the entity's to refuse.
   */
  def parse(b: ObjectNode): Either[String, Map[String, Pair]] =
    Option(b.get("limits")).collect { case o: ObjectNode => o }.toRight("limits").flatMap { o =>
      o.properties.asScala.toVector.foldLeft[Either[String, Map[String, Pair]]](Right(Map.empty)) { (acc, entry) =>
        val code = entry.getKey
        for
          pairs  <- acc
          fields <- Option(entry.getValue).collect { case p: ObjectNode => p }.toRight(s"limits.$code")
          weekly <- window(fields, code, "weekly")
          monthly <- window(fields, code, "monthly")
        yield pairs + (code -> Pair(weekly, monthly))
      }
    }

  private def window(fields: ObjectNode, code: String, name: String): Either[String, Option[Long]] =
    Option(fields.get(name)).filterNot(_.isNull) match
      case None                                                => Right(None)
      case Some(n) if n.isIntegralNumber && n.canConvertToLong => Right(Some(n.asLong))
      case Some(_)                                             => Left(s"limits.$code.$name")
