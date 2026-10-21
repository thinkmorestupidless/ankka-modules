package io.eitheror.casino.merchant.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.Json.*
import io.eitheror.casino.merchant.api.Answers.*
import io.eitheror.casino.merchant.hierarchy.*
import io.eitheror.casino.merchant.operators.Target

import java.util.UUID

/** `/v1/holdings` (FR-002): created at the installation level only; listed and read within the operator's level. */
final class HoldingsEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/holdings"):
  val acl: Acl = Routes.paymentsOperator(ctx.authenticate())
  private def op = Operator.of(principal)
  private def entity(id: String) = ctx.components.forEventSourcedEntity(EntityId(id))

  postBody("/") { (text: String) =>
    guarded {
      if !op.platform then refuse(403, "INSUFFICIENT_SCOPE")
      else body(text).fold(identity, b =>
        val s = entity(UUID.randomUUID().toString).call(Holding.create).invoke(CreateOrg(b.textOr("name", ""), None, op.actor, ctx.now()))
        created(Render.holding(s)))
    }
  }

  get("/") { () =>
    val o = op
    val rows = ctx.views.forView(HoldingsView).all(10_000).filter(r => o.permits(Target(Some(r.id)))).sortBy(_.createdAt)
    ok(Json.obj("holdings" -> Json.arr(rows.map(r => Render.orgRow(r, "holding"))*)))
  }

  get("/{id}") { (id: String) =>
    guarded {
      val s = ctx.holding(id)
      if !s.exists || !op.permits(Target(Some(id))) then refuse(404, "HOLDING_NOT_FOUND") else ok(Render.holding(s))
    }
  }

  private def status(id: String, text: String) =
    guarded {
      if !op.platform then refuse(403, "INSUFFICIENT_SCOPE")
      else body(text).fold(identity, b =>
        if !ctx.holding(id).exists then refuse(404, "HOLDING_NOT_FOUND")
        else
          val s = entity(id).call(Holding.changeStatus).invoke(ChangeOrgStatus(b.textOr("status", ""), op.actor, ctx.now()))
          ok(Json.obj("holdingId" -> id, "status" -> s.status)))
    }
  patchBody("/{id}/status") { (id: String, text: String) => status(id, text) }
  putBody("/{id}/status") { (id: String, text: String) => status(id, text) }

  /** A holding's companies, refused as not found when the holding is outside the operator's level. */
  get("/{id}/companies") { (id: String) =>
    val o = op
    if !ctx.holding(id).exists || !o.permits(Target(Some(id))) && o.scope.level != io.eitheror.casino.merchant.operators.Level.Company then
      refuse(404, "HOLDING_NOT_FOUND")
    else
      val rows = ctx.views.forView(CompaniesView).where(jsonText("parentId") ++ sql" = $id", 10_000)
        .filter(r => o.permits(Target(Some(id), Some(r.id)))).sortBy(_.createdAt)
      ok(Json.obj("companies" -> Json.arr(rows.map(r => Render.orgRow(r, "company"))*)))
  }

/** `/v1/companies` (FR-002): created under a holding at the installation level; read within the operator's level. */
final class CompaniesEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/companies"):
  val acl: Acl = Routes.paymentsOperator(ctx.authenticate())
  private def op = Operator.of(principal)
  private def entity(id: String) = ctx.components.forEventSourcedEntity(EntityId(id))

  postBody("/") { (text: String) =>
    guarded {
      if !op.platform then refuse(403, "INSUFFICIENT_SCOPE")
      else body(text).fold(identity, b =>
        val holding = b.textOr("holdingId", "")
        if !ctx.holding(holding).exists then refuse(404, "HOLDING_NOT_FOUND")
        else
          val s = entity(UUID.randomUUID().toString).call(Company.create).invoke(CreateOrg(b.textOr("name", ""), Some(holding), op.actor, ctx.now()))
          created(Render.company(s)))
    }
  }

  get("/") { () =>
    val o = op
    val rows = ctx.views.forView(CompaniesView).all(10_000).filter(r => o.permits(Target(r.parentId, Some(r.id)))).sortBy(_.createdAt)
    ok(Json.obj("companies" -> Json.arr(rows.map(r => Render.orgRow(r, "company"))*)))
  }

  get("/{id}") { (id: String) =>
    guarded {
      val s = ctx.company(id)
      if !s.exists || !op.permits(Target(s.parentId, Some(id))) then refuse(404, "COMPANY_NOT_FOUND") else ok(Render.company(s))
    }
  }

  private def status(id: String, text: String) =
    guarded {
      if !op.platform then refuse(403, "INSUFFICIENT_SCOPE")
      else body(text).fold(identity, b =>
        if !ctx.company(id).exists then refuse(404, "COMPANY_NOT_FOUND")
        else
          val s = entity(id).call(Company.changeStatus).invoke(ChangeOrgStatus(b.textOr("status", ""), op.actor, ctx.now()))
          ok(Json.obj("companyId" -> id, "status" -> s.status)))
    }
  patchBody("/{id}/status") { (id: String, text: String) => status(id, text) }
  putBody("/{id}/status") { (id: String, text: String) => status(id, text) }

/**
 * `/v1/sub-brands` (FR-001, FR-002): created under a company, with a brand for a casino's; listed and
 * read within the operator's level; and the credential routes the original served here as well.
 */
final class SubBrandsEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/sub-brands"):
  val acl: Acl = Routes.paymentsOperator(ctx.authenticate())
  private def op  = Operator.of(principal)
  private val ops = MerchantOps(ctx)
  private def name = request.query.raw("name")

  postBody("/") { (text: String) =>
    guarded {
      body(text).fold(identity, b =>
        if Seq("withdrawalTurnoverMultiplier", "withdrawalTurnoverDefinition").exists(f => b.has(f)) then
          refuse(400, "INVALID_REQUEST", "withdrawal turnover is the brand wallet's configuration (spec 002 FR-004a)")
        else
          val companyId = b.textOr("companyId", "")
          val company   = ctx.company(companyId)
          if !company.exists then refuse(404, "COMPANY_NOT_FOUND")
          else if !op.permits(Target(company.parentId, Some(companyId))) then scopeViolation
          else
            val s = ctx.components.forEventSourcedEntity(EntityId(UUID.randomUUID().toString)).call(SubBrand.create)
              .invoke(CreateSubBrand(companyId, company.parentId.getOrElse(""), b.textOr("name", ""), b.textOr("type", SubBrandType.Casino),
                b.text("brand"), op.actor, ctx.now()))
            created(Render.subBrand(s)))
    }
  }

  get("/") { () =>
    val o       = op
    val company = request.query.raw("companyId")
    val rows = company.fold(ctx.views.forView(SubBrandsView).all(10_000))(c => ctx.views.forView(SubBrandsView).where(jsonText("companyId") ++ sql" = $c", 10_000))
      .filter(r => o.permits(Target(Option(r.holdingId).filter(_.nonEmpty), Option(r.companyId).filter(_.nonEmpty), Some(r.id)))).sortBy(_.createdAt)
    ok(Json.obj("subBrands" -> Json.arr(rows.map(Render.subBrandRow)*)))
  }

  get("/{id}") { (id: String) => ops.scoped(op, id)(s => ok(Render.subBrand(s))) }

  private def status(id: String, text: String) =
    ops.scoped(op, id) { _ =>
      body(text).fold(identity, b =>
        val s = ctx.components.forEventSourcedEntity(EntityId(id)).call(SubBrand.changeStatus).invoke(ChangeSubBrandStatus(b.textOr("status", ""), op.actor, ctx.now()))
        ok(Json.obj("subBrandId" -> id, "status" -> s.status)))
    }
  patchBody("/{id}/status") { (id: String, text: String) => status(id, text) }
  putBody("/{id}/status") { (id: String, text: String) => status(id, text) }

  postBody("/{id}/psp-credentials") { (id: String, text: String) => ops.registerCredential(op, id, text) }
  get("/{id}/psp-credentials") { (id: String) => ops.listCredentials(op, id) }
  get("/{id}/psp-credentials/{provider}") { (id: String, provider: String) => ops.readCredential(op, id, provider, name) }
  putBody("/{id}/psp-credentials/{provider}") { (id: String, provider: String, text: String) => ops.replaceCredentialSecrets(op, id, provider, name, text) }
  delete("/{id}/psp-credentials/{provider}") { (id: String, provider: String) => ops.deleteCredential(op, id, provider, name) }
  post("/{id}/psp-credentials/{provider}/activate") { (id: String, provider: String) => ops.credentialStatus(op, id, provider, name, CredentialRecord.Active) }
  patch("/{id}/psp-credentials/{provider}/activate") { (id: String, provider: String) => ops.credentialStatus(op, id, provider, name, CredentialRecord.Active) }
  post("/{id}/psp-credentials/{provider}/disable") { (id: String, provider: String) => ops.credentialStatus(op, id, provider, name, CredentialRecord.Disabled) }
  patch("/{id}/psp-credentials/{provider}/disable") { (id: String, provider: String) => ops.credentialStatus(op, id, provider, name, CredentialRecord.Disabled) }
  get("/{id}/gateway-config") { (id: String) => ops.gatewayConfig(op, id) }

/**
 * `/v1/merchants/{id}` (FR-004, FR-006, FR-007): the original's "merchant" is the sub-brand. Keys,
 * credentials (the name as the query parameter `name`, research R14) and terminals.
 */
final class MerchantsEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/merchants"):
  val acl: Acl = Routes.paymentsOperator(ctx.authenticate())
  private def op  = Operator.of(principal)
  private val ops = MerchantOps(ctx)
  private def name = request.query.raw("name")

  get("/{id}") { (id: String) => ops.scoped(op, id)(s => ok(Render.subBrand(s))) }

  postBody("/{id}/keys") { (id: String, text: String) => ops.generateKey(op, id, text) }
  get("/{id}/keys") { (id: String) => ops.listKeys(op, id) }
  post("/{id}/keys/{keyId}/disable") { (id: String, keyId: String) => ops.disableKey(op, id, keyId) }

  get("/{id}/psp-configurations") { (id: String) => ops.listConfigurations(op, id) }
  postBody("/{id}/psp-credentials") { (id: String, text: String) => ops.registerCredential(op, id, text) }
  get("/{id}/psp-credentials") { (id: String) => ops.listCredentials(op, id) }
  get("/{id}/psp-credentials/{provider}") { (id: String, provider: String) => ops.readCredential(op, id, provider, name) }
  putBody("/{id}/psp-credentials/{provider}") { (id: String, provider: String, text: String) => ops.replaceCredentialSecrets(op, id, provider, name, text) }
  delete("/{id}/psp-credentials/{provider}") { (id: String, provider: String) => ops.deleteCredential(op, id, provider, name) }
  post("/{id}/psp-credentials/{provider}/activate") { (id: String, provider: String) => ops.credentialStatus(op, id, provider, name, CredentialRecord.Active) }
  patch("/{id}/psp-credentials/{provider}/activate") { (id: String, provider: String) => ops.credentialStatus(op, id, provider, name, CredentialRecord.Active) }
  post("/{id}/psp-credentials/{provider}/disable") { (id: String, provider: String) => ops.credentialStatus(op, id, provider, name, CredentialRecord.Disabled) }
  patch("/{id}/psp-credentials/{provider}/disable") { (id: String, provider: String) => ops.credentialStatus(op, id, provider, name, CredentialRecord.Disabled) }

  postBody("/{id}/gateways/{gateway}/integrations") { (id: String, gateway: String, text: String) => ops.createIntegration(op, id, gateway, text) }
  get("/{id}/gateways/integrations") { (id: String) => ops.listIntegrations(op, id) }
  get("/{id}/gateways/integrations/{tid}") { (id: String, tid: String) => ops.readIntegration(op, id, tid) }
  putBody("/{id}/gateways/integrations/{tid}/properties") { (id: String, tid: String, text: String) => ops.setProperties(op, id, tid, text) }
  patchBody("/{id}/gateways/integrations/{tid}/properties") { (id: String, tid: String, text: String) => ops.setProperties(op, id, tid, text) }
  putBody("/{id}/gateways/integrations/{tid}/capability") { (id: String, tid: String, text: String) => ops.setCapability(op, id, tid, text) }
  patchBody("/{id}/gateways/integrations/{tid}/capability") { (id: String, tid: String, text: String) => ops.setCapability(op, id, tid, text) }
  post("/{id}/gateways/integrations/{tid}/activate") { (id: String, tid: String) => ops.activate(op, id, tid, on = true) }
  patch("/{id}/gateways/integrations/{tid}/activate") { (id: String, tid: String) => ops.activate(op, id, tid, on = true) }
  post("/{id}/gateways/integrations/{tid}/disable") { (id: String, tid: String) => ops.activate(op, id, tid, on = false) }
  patch("/{id}/gateways/integrations/{tid}/disable") { (id: String, tid: String) => ops.activate(op, id, tid, on = false) }
  delete("/{id}/gateways/integrations/{tid}") { (id: String, tid: String) => ops.deleteIntegration(op, id, tid) }
  get("/{id}/gateway-config") { (id: String) => ops.gatewayConfig(op, id) }

/** `/v1/catalog/gateway/{gateway}` (FR-009): the static catalogue, secrets marked. */
final class CatalogEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/catalog"):
  val acl: Acl = Routes.paymentsOperator(ctx.authenticate())
  get("/gateway/{gateway}") { (gateway: String) => ok(Render.catalog(gateway)) }
