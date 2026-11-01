package io.eitheror.casino.programme.it

import io.eitheror.casino.programme.accounts.{KeycloakAdmin, RealmError}

import java.util.UUID
import scala.collection.mutable

/** The `eitheror` realm in process: its users, their attributes, roles and passwords. */
final class FakeKeycloak extends KeycloakAdmin:
  final case class User(id: String, email: String, firstName: Option[String], lastName: Option[String],
      attributes: Map[String, String], roles: Set[String] = Set.empty, password: Option[String] = None)
  private val users = mutable.LinkedHashMap.empty[String, User]
  @volatile var failing: Option[String] = None

  private def guarded[A](f: => Either[RealmError, A]): Either[RealmError, A] =
    failing.fold(synchronized(f))(why => Left(RealmError.Failed(why)))

  private def change(id: String)(f: User => User): Either[RealmError, Unit] =
    users.get(id).toRight(RealmError.NotFound(id)).map(u => users.update(id, f(u)))

  def createUser(email: String, firstName: Option[String], lastName: Option[String], attributes: Map[String, String]) = guarded {
    if users.values.exists(_.email.equalsIgnoreCase(email)) then Left(RealmError.AlreadyExists(email))
    else
      val id = UUID.randomUUID().toString
      users.update(id, User(id, email, firstName, lastName, attributes))
      Right(id)
  }
  def assignRole(userId: String, role: String)          = guarded(change(userId)(u => u.copy(roles = u.roles + role)))
  def removeRole(userId: String, role: String)          = guarded(change(userId)(u => u.copy(roles = u.roles - role)))
  def findByEmail(email: String)                        = guarded(Right(users.values.find(_.email.equalsIgnoreCase(email)).map(_.id)))
  def resetPassword(userId: String, password: String)   = guarded(change(userId)(_.copy(password = Some(password))))
  def updateEmail(userId: String, email: String)        = guarded(change(userId)(_.copy(email = email)))
  def deleteUser(userId: String)                        = guarded { users.remove(userId); Right(()) }

  def user(id: String): Option[User]          = synchronized(users.get(id))
  def byEmail(email: String): Option[User]    = synchronized(users.values.find(_.email.equalsIgnoreCase(email)))
  def all: Vector[User]                       = synchronized(users.values.toVector)
  def reset(): Unit                           = synchronized { users.clear(); failing = None }
