package io.eitheror.casino.contract.access

/**
 * Spec 032 (contracts/brand-roles.md): a role an operator holds in a brand is a realm role named for
 * that brand, `<role>@<slug>` (`brand_finance@goldvibe`), and a brand reads only the roles named for
 * it. This is the one place the name is built and read. A brand's own names never hold the separator
 * ([[OperatorPermissions.validRoleName]]), so splitting at the last one is unambiguous.
 *
 * Not brand roles, and never named for a brand: `brand_<slug>`, `platform_admin`, the audit roles,
 * the payments roles and `affiliate`.
 */
object BrandRoles:

  val Separator = '@'

  /** The realm role for `role` held in `slug`. */
  def nameIn(slug: String, role: String): String = s"$role$Separator$slug"

  /** `(role, slug)` when `realmRole` is named for a brand. */
  def brandOf(realmRole: String): Option[(String, String)] =
    val at = realmRole.lastIndexOf(Separator)
    Option.when(at > 0 && at < realmRole.length - 1)((realmRole.take(at), realmRole.drop(at + 1)))

  /** The roles of `roles` named for `slug`, by their names without the brand. */
  def heldIn(slug: String, roles: Set[String]): Set[String] =
    roles.flatMap(r => brandOf(r).collect { case (role, `slug`) => role })

  /** Whether `roles` holds `role` named for `slug`. */
  def holds(slug: String, roles: Set[String], role: String): Boolean = roles.contains(nameIn(slug, role))

  /** Roles a brand may give although they are reserved: routes check them by name (FR-012). */
  val Given: Set[String] = Set("api_keys", "markets")

  /**
   * Roles named for a brand that the programme gives outside any giving (research R8). A brand cannot
   * give them (they are reserved), and a giving never takes them away.
   */
  val ProgrammeGiven: Set[String] = Set("affiliate_manager")

  /** The roles, beyond the default ones, that are named for a brand: made at apply and carried over. */
  val Named: Set[String] = Given ++ ProgrammeGiven

  /** Whether a giving for `slug` puts `realmRole` in or takes it away: named for `slug`, and not the programme's. */
  def givenBy(slug: String, realmRole: String): Boolean =
    brandOf(realmRole).exists((role, s) => s == slug && !ProgrammeGiven(role))
