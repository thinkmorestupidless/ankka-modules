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
  /**
   * The roles routes check by name that a brand gives as it gives its others (spec 032 FR-012): reserved
   * from being made or changed, in no bag, and given by a giver holding `roles.give`.
   */
  val Given: Set[String] = Set("api_keys", "markets")

  /** Why `name` may not be given: none for a role a brand gives, its bags' or [[Given]]. */
  def givingRefusal(name: String): Option[(String, String)] = if Given(name) then None else nameRefusal(name)

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

  /**
   * The first permission `giverHolds` lacks of those carried by the roles a giving asks for or takes away
   * (FR-008c: give and take away): a giving replaces `before`, the roles the brand gave the operator last,
   * with `asked`, so what it removes weighs as much as what it adds. A role no longer in `bags` carries nothing.
   */
  def givingLacks(giverHolds: Set[String], bags: Map[String, Set[String]], before: Set[String], asked: Set[String]): Option[String] =
    escalation(giverHolds, (asked ++ (before -- asked)).flatMap(r => bags.getOrElse(r, Set.empty)))
