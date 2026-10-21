package io.eitheror.casino.merchant.operators

import io.eitheror.casino.contract.access.Routes

/**
 * Who may make whom an operator (FR-027; the original's `PrivilegeCheck`): an operator holds the
 * highest level of their roles in the privilege table, may give a role only below their own, and may
 * place an operator only at their own level or narrower, inside their own subtree.
 */
object Privilege:

  def level(roles: Set[String]): Int = roles.flatMap(Routes.PaymentsRoles.get).maxOption.getOrElse(0)

  /** A role is assignable only by someone who strictly outranks it; a role outside the table never is. */
  def canAssignRole(callerRoles: Set[String], role: String): Boolean =
    Routes.PaymentsRoles.get(role).exists(level(callerRoles) > _)

  /**
   * A scope is assignable when it does not broaden the caller's level and lies in the caller's
   * subtree: the platform's anything, otherwise the caller's own entity or one under it. `ancestors`
   * are the target entity's parents, as the hierarchy records them.
   */
  def canAssignScope(caller: Scope, target: Scope, ancestors: Set[String]): Boolean =
    val levelOk   = target.level.ordinal >= caller.level.ordinal
    val subtreeOk = caller.level == Level.Platform || target.id == caller.id || ancestors.contains(caller.id)
    levelOk && subtreeOk
