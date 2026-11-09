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

  /**
   * A role the audit grants, one realm role each, and the brand it concerns: none for `audit_compliance`,
   * the slug for `audit_compliance_<slug>` and `reidentify_<slug>`. A grant assigns exactly it and a revoke
   * removes exactly it (research, review decisions).
   */
  final case class Role(name: String, brand: Option[String]):
    def recordBrand: String = brand.getOrElse(Own.Installation)

  private val BrandRoles = Vector(Routes.CompliancePrefix, "reidentify_")

  /** The brand a per-brand role names, when it is one. */
  def brandOf(name: String): Option[String] =
    BrandRoles.find(prefix => name.startsWith(prefix) && name.length > prefix.length).map(name.stripPrefix)

  /** The role a route names, or why it is not one the audit grants. */
  def role(name: String, brands: Set[String]): Either[String, Role] =
    if name == Routes.AuditCompliance then Right(Role(name, None))
    else brandOf(name).filter(brands).map(b => Role(name, Some(b)))
      .toRight(s"unknown_role: $name is not audit_compliance, audit_compliance_<brand> or reidentify_<brand> of a brand read here")

  /** Every role the realm read looks at: the installation's, and each brand's compliance and re-identification. */
  def managed(brands: Vector[String]): Vector[String] =
    Vector(Routes.AuditCompliance) ++ brands.flatMap(b => Vector(Routes.complianceRole(b), Routes.reidentifyRole(b)))

  /**
   * The realm id a route's `{person}` names: an installation pseudonym the registry holds for an operator,
   * as the listing answers, or the realm id itself.
   */
  def person(client: ComponentClient, named: String): String =
    val identity = client.forKeyValueEntity(io.eitheror.casino.audit.registry.Identity.entityId(named))
      .call(io.eitheror.casino.audit.registry.Identity.get).invoke()
    if identity.bound && identity.brand == Own.Installation && identity.kind == "operator" then identity.realId else named

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
          val role = Role(name, brandOf(name))
          Own.record(calls, role.recordBrand, FoundAction, "realm", "operator", Own.staff(calls, role.recordBrand, person), "done", None,
            Vector(Own.change("role", name), Own.change("held", RoleGrant.Outside)), now): Unit
        changed
      }
    }

  def realm: KeycloakAdmin = Wiring.realm.get()
