package io.eitheror.casino.audit.roles

import com.thinkmorestupidless.ankka.sdk.ComponentClient
import io.eitheror.casino.audit.Wiring
import io.eitheror.casino.audit.ingest.IngestCalls
import io.eitheror.casino.audit.own.Own
import io.eitheror.casino.contract.access.Routes

import java.time.Instant

/**
 * The roles the shared audit grants (research R8) and how a grant, a revoke or a holding found outside
 * it is recorded. A record names the person by their pseudonym under the brand the role concerns, or
 * under the installation for `audit_compliance`.
 */
object Grants:
  val GrantAction   = "audit.role-grant"
  val RevokeAction  = "audit.role-revoke"
  val FoundAction   = "audit.role-found"

  /** A role the audit grants, and the brand it concerns; `brand_compliance` names its brand in the body. */
  final case class Role(name: String, brand: Option[String]):
    /** The realm roles a grant gives: `brand_compliance` with the brand's own `brand_<slug>`. */
    def realmRoles: Vector[String] =
      if name == Routes.BrandCompliance then Vector(name) ++ brand.map(b => s"brand_$b") else Vector(name)
    def recordBrand: String = brand.getOrElse(Own.Installation)

  /** The role a route names, or why it is not one the audit grants. */
  def role(name: String, brand: Option[String], brands: Set[String]): Either[String, Role] =
    if name == Routes.AuditCompliance then Right(Role(name, None))
    else if name == Routes.BrandCompliance then
      brand.filter(brands).map(b => Role(name, Some(b))).toRight(s"bad_request: brand_compliance names one of ${brands.toVector.sorted.mkString(", ")}")
    else if name.startsWith("reidentify_") && brands(name.stripPrefix("reidentify_")) then Right(Role(name, Some(name.stripPrefix("reidentify_"))))
    else Left(s"unknown_role: $name is not audit_compliance, brand_compliance or reidentify_<brand>")

  /** Every role the realm read looks at: the installation's, brand compliance, and each brand's re-identification. */
  def managed(brands: Vector[String]): Vector[String] =
    Vector(Routes.AuditCompliance, Routes.BrandCompliance) ++ brands.map(Routes.reidentifyRole)

  /** A person's installation pseudonym: what the grant is held under. */
  def holder(calls: IngestCalls, person: String): String = Own.staff(calls, Own.Installation, person)

  def recorded(calls: IngestCalls, action: String, role: Role, actor: String, person: String, now: Instant): Unit =
    Own.record(calls, role.recordBrand, action, actor, "operator", Own.staff(calls, role.recordBrand, person), "done", None,
      Vector(Own.change("role", role.name)) ++ role.brand.map(Own.change("brand", _)), now): Unit

  /** The realm read's part for one role: each holder with no grant found once, and recorded. */
  def readRole(client: ComponentClient, realm: KeycloakAdmin, name: String, now: Instant): Either[RealmError, Int] =
    val calls = IngestCalls.of(client)
    realm.usersWithRole(name).map { holders =>
      holders.count { person =>
        val changed = client.forKeyValueEntity(RoleGrant.entityId(name, holder(calls, person))).call(RoleGrant.found).invoke(Found(now)).changed
        if changed then
          val role = Role(name, Option.when(name.startsWith("reidentify_"))(name.stripPrefix("reidentify_")))
          Own.record(calls, role.recordBrand, FoundAction, "realm", "operator", Own.staff(calls, role.recordBrand, person), "done", None,
            Vector(Own.change("role", name), Own.change("held", RoleGrant.Outside)), now): Unit
        changed
      }
    }

  def realm: KeycloakAdmin = Wiring.realm.get()
