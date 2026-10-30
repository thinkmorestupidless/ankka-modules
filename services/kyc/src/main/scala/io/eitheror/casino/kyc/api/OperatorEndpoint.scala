package io.eitheror.casino.kyc.api

import com.fasterxml.jackson.databind.JsonNode
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.kyc.Json
import io.eitheror.casino.kyc.Json.*
import io.eitheror.casino.kyc.record.{Actor, Uploader}

import Answers.*

/**
 * `/v1/casino/backoffice/<slug>/kyc`: the brand's compliance operators (FR-002, R14). The ACL admits an
 * operator of the brand; each route then asks for one of the compliance roles, and any other is `forbidden`.
 */
final class OperatorEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/backoffice/${ctx.slug}/kyc"):
  val acl: Acl = Routes.operator(ctx.slug, ctx.authenticate())

  private val documents = DocumentRoutes(ctx)

  private def compliance(f: => Reply): Reply =
    if principal.roles.exists(OperatorEndpoint.ComplianceRoles) then f else refused("forbidden")

  private def body(text: String)(f: JsonNode => Reply): Reply = Json.parseObject(text).fold(badBody)(f)

  postBody("/{playerId}/uploads") { (p: String, text: String) =>
    compliance(body(text) { b =>
      b.text("reason").fold(refused("reason_required"))(reason => documents.upload(p, b, Uploader.support(principal.subject, reason)))
    })
  }
  postBody("/{playerId}/documents") { (p: String, text: String) => compliance(body(text)(documents.confirm(p, _))) }
  get("/{playerId}/views/{documentId}") { (p: String, d: String) =>
    compliance(documents.view(p, d, Actor.operator(principal.subject)))
  }

object OperatorEndpoint:
  /** The roles that may act on a player's KYC and on the brand's policy (FR-002). */
  val ComplianceRoles: Set[String] = Set("brand_admin", "brand_compliance", "compliance_officer", "resource_admin", "platform_admin")
