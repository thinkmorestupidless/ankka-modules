package io.eitheror.casino.players.roles

import io.eitheror.casino.contract.access.OperatorPermissions

/**
 * The rules the brand holds over its roles (spec 030 contracts/roles.md *Rules the owner holds*), decided
 * against every role as they stand, before a role is changed.
 */
object RoleRules:
  val NoSuchRole        = "role_not_found"
  val NotDefault        = "not_a_default_role"
  val DefaultRole       = "default_role"
  val UnknownPermission = "unknown_permission"
  val ReservedRole      = "reserved_role"
  val InvalidName       = "invalid_role_name"
  val LastRoleChanger   = "last_role_changer"
  val GiverLacks        = "forbidden"

  /** The permission that may change roles: a brand keeps at least one role carrying it. */
  val Change = "roles.change"

  /** Why `name` may not be a role the brand makes or changes, if it may not. */
  def nameRefusal(name: String): Option[(String, String)] =
    if !OperatorPermissions.validRoleName(name) then Some(InvalidName -> s"'$name' is not a role's name: lower case letters, digits and _, at most 40")
    else if OperatorPermissions.reserved(name) then Some(ReservedRole -> s"$name is not a role the brand makes, changes or gives")
    else None

  /** The first permission the contract does not know, if any. */
  def unknown(permissions: Set[String]): Option[String] = permissions.toVector.sorted.find(p => !OperatorPermissions.known(p))

  /**
   * Whether making `role` carry `after` (none: deleting it) leaves no role able to change roles, given every
   * role's bag as it stands.
   */
  def leavesNoChanger(roles: Map[String, Set[String]], role: String, after: Option[Set[String]]): Boolean =
    val next = after.fold(roles - role)(bag => roles.updated(role, bag))
    roles.values.exists(_(Change)) && !next.values.exists(_(Change))

  /** The first permission `giverHolds` lacks of those the given roles carry, if any (FR-008c). */
  def escalation(giverHolds: Set[String], carried: Set[String]): Option[String] =
    carried.toVector.sorted.find(p => !giverHolds(p))
