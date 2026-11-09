package io.eitheror.casino.audit.it

import io.eitheror.casino.audit.roles.{KeycloakAdmin, RealmError}

import scala.collection.mutable

/** The `eitheror` realm in process: its users and their realm roles. A user is made by `add`. */
final class FakeKeycloak extends KeycloakAdmin:
  private val users = mutable.LinkedHashMap.empty[String, Set[String]]
  @volatile var failing: Option[String] = None

  private def guarded[A](f: => Either[RealmError, A]): Either[RealmError, A] =
    failing.fold(synchronized(f))(why => Left(RealmError.Failed(why)))

  private def change(id: String)(f: Set[String] => Set[String]): Either[RealmError, Unit] =
    users.get(id).toRight(RealmError.NotFound(id)).map(r => users.update(id, f(r)))

  def assignRole(userId: String, role: String) = guarded(change(userId)(_ + role))
  def removeRole(userId: String, role: String) = guarded(change(userId)(_ - role))
  def usersWithRole(role: String)              = guarded(Right(users.collect { case (id, roles) if roles(role) => id }.toVector))

  /** A user of the realm holding `roles`, given outside the audit. */
  def add(id: String, roles: String*): Unit = synchronized(users.update(id, roles.toSet))
  def rolesOf(id: String): Set[String]      = synchronized(users.getOrElse(id, Set.empty))
