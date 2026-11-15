package io.eitheror.casino.contract.access

/**
 * Spec 030 contracts/roles.md: what an operator of a brand may do, one permission per action. A route
 * requires exactly one permission and names no role. A role is a bag of permissions the brand keeps in
 * its `players` service and changes at run time; a token carries roles only. [[DefaultRoles]] is the
 * role matrix a brand starts with and what a default role is reset to, never what a role carries at
 * run time ([[RoleBags]] is).
 */
object OperatorPermissions:

  /** Every permission, in the order of contracts/roles.md. */
  val All: Vector[String] = Vector(
    "player.read", "wallet.read", "withdrawal.read", "reward.read", "identity.read", "protection.read",
    "note.write", "message.send", "presence.read",
    "adjustment.make", "turnover.override", "withdrawal.review", "reward.grant", "reward.revoke",
    "standing.change", "profile.change", "session.end", "flag.clear", "fraud.review", "identity.review",
    "protection.change", "erasure.request", "export.player", "tier.change",
    "dashboard.read", "report.read", "report.dayEnd", "report.compliance",
    "offer.manage", "config.brand", "config.games", "config.crm", "config.agent", "config.attribution",
    "modules.read", "modules.request", "roles.read", "roles.change", "roles.give",
    "callback.resolve", "fact.redeliver", "send.release"
  )

  private val AllSet: Set[String] = All.toSet

  def known(permission: String): Boolean = AllSet.contains(permission)

  /** Holds every permission, in code: not the brand's to change. */
  val PlatformAdmin = "platform_admin"

  val Support    = "brand_support"
  val Finance    = "brand_finance"
  val Compliance = "brand_compliance"
  val Admin      = "brand_admin"

  /** The reads every default role holds. */
  private val reads = Set(
    "player.read", "wallet.read", "withdrawal.read", "reward.read", "identity.read", "protection.read",
    "note.write", "message.send", "presence.read", "dashboard.read", "roles.read"
  )

  /** The role matrix of contracts/roles.md (original spec 135): each default role and its default bag. */
  val DefaultRoles: Map[String, Set[String]] = Map(
    Support    -> reads,
    Finance    -> (reads ++ Set("adjustment.make", "turnover.override", "withdrawal.review", "reward.grant", "reward.revoke",
                    "export.player", "report.read", "report.dayEnd")),
    Compliance -> (reads ++ Set("standing.change", "profile.change", "session.end", "flag.clear", "fraud.review",
                    "identity.review", "protection.change", "erasure.request", "export.player", "report.compliance")),
    Admin      -> AllSet
  )

  /**
   * Names a brand may not create, change or give: `platform_admin`, any `brand_…` but the default roles
   * (so every `brand_<slug>`), and the payments roles (spec 004's privilege table), which confer no casino
   * action. The default roles are the brand's.
   */
  def reserved(role: String): Boolean =
    role == PlatformAdmin || Routes.PaymentsRoles.contains(role) ||
      (role.startsWith("brand_") && !DefaultRoles.contains(role))

  /** A role's name: lower case letters, digits and `_`, at most 40 (data-model.md *Role*). */
  def validRoleName(name: String): Boolean = name.matches("[a-z0-9_]{1,40}")

  /** The permissions `roles` carry under `bags`; every permission for `platform_admin`. */
  def permissionsOf(roles: Set[String], bags: Map[String, Set[String]]): Set[String] =
    if roles.contains(PlatformAdmin) then AllSet
    else roles.flatMap(r => bags.getOrElse(r, Set.empty))
