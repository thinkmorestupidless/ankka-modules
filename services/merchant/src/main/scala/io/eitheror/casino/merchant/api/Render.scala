package io.eitheror.casino.merchant.api

import com.fasterxml.jackson.databind.node.ObjectNode
import io.eitheror.casino.merchant.Json
import io.eitheror.casino.merchant.hierarchy.*
import io.eitheror.casino.merchant.terminals.TerminalState
import io.eitheror.casino.merchant.terminals.catalogue.Catalogue

/** The original's answer shapes (`Models.scala`), camelCase as spray-json wrote them. */
object Render:
  def holding(s: OrgState): ObjectNode =
    Json.obj("holdingId" -> s.id, "name" -> s.name, "status" -> s.status, "createdAt" -> s.createdAt.map(_.toString).getOrElse(""))

  def company(s: OrgState): ObjectNode =
    Json.obj("companyId" -> s.id, "holdingId" -> s.parentId.getOrElse(""), "name" -> s.name, "status" -> s.status,
      "createdAt" -> s.createdAt.map(_.toString).getOrElse(""))

  def subBrand(s: SubBrandState): ObjectNode =
    Json.obj("subBrandId" -> s.id, "companyId" -> s.companyId, "holdingId" -> s.holdingId, "name" -> s.name, "type" -> s.subBrandType,
      "status" -> s.status, "brand" -> s.brand, "createdAt" -> s.createdAt.map(_.toString).getOrElse(""))

  def subBrandRow(r: SubBrandRow): ObjectNode =
    Json.obj("subBrandId" -> r.id, "companyId" -> r.companyId, "holdingId" -> r.holdingId, "name" -> r.name, "type" -> r.subBrandType,
      "status" -> r.status, "brand" -> r.brand, "createdAt" -> r.createdAt.toString)

  def orgRow(r: OrgRow, kind: String): ObjectNode =
    val o = Json.obj(s"${kind}Id" -> r.id, "name" -> r.name, "status" -> r.status, "createdAt" -> r.createdAt.toString)
    r.parentId.foreach(p => o.put("holdingId", p))
    o

  def key(k: MerchantKey): ObjectNode =
    Json.obj("keyId" -> k.keyId, "name" -> k.name, "active" -> k.active, "createdAt" -> k.createdAt.toString)

  /** A credential's public summary: no secret (FR-006). */
  def credential(c: CredentialRecord): ObjectNode =
    Json.obj("configId" -> c.id, "provider" -> c.provider, "name" -> c.name, "credentialName" -> c.name, "environment" -> c.environment,
      "status" -> c.status, "scheme" -> c.scheme)

  /** With the secret keys set and a masked preview of each (`PspCredentialLifecycleSpec`). */
  def credentialDetail(c: CredentialRecord, secrets: Map[String, String]): ObjectNode =
    val o = credential(c)
    Json.set(o, "credentialKeys", secrets.keys.toVector.sorted)
    o.set("credentialPreviews", Json.objOf(secrets.map((k, v) => k -> Catalogue.preview(v)))): Unit
    o

  /**
   * A terminal's secret-free summary (the original's `integrationSummaryJson`): which keys are set, which
   * required ones are missing, and a masked preview of each value, never the value itself.
   */
  def integration(t: TerminalState, secrets: Map[String, String]): ObjectNode =
    val values  = t.properties.filter(_._2.trim.nonEmpty) ++ secrets.filter(_._2.trim.nonEmpty)
    val keys    = values.keys.toVector.sorted
    val missing = Catalogue.requiredKeys(t.gateway, t.scheme).filterNot(keys.contains)
    val o = Json.obj("integrationId" -> t.id, "gateway" -> t.gateway, "scheme" -> t.scheme, "name" -> t.name, "status" -> t.status,
      "regions" -> t.regions, "propertyKeys" -> keys, "missingRequiredProperties" -> missing, "supportsDeposit" -> t.supportsDeposit,
      "supportsWithdrawal" -> t.supportsWithdrawal, "requiresPayoutDestination" -> t.requiresPayoutDestination)
    o.set("propertyPreviews", Json.objOf(values.map((k, v) => k -> Catalogue.preview(v)))): Unit
    o

  def catalog(gateway: String): ObjectNode =
    val c = Catalogue.of(gateway)
    Json.obj("gateway" -> c.gateway, "schemes" -> Json.arr(c.schemes.map { s =>
      Json.obj("pspId" -> s.pspId, "category" -> s.category, "requiredProperties" -> Json.arr(s.requiredProperties.map { p =>
        Json.obj("key" -> p.key, "label" -> p.label, "required" -> p.required, "multiline" -> p.multiline, "secret" -> p.secret)
      }*))
    }*))
