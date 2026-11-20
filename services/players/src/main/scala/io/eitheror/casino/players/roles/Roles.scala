package io.eitheror.casino.players.roles

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.players.PlayersCodecs

/** A role as `GET roles` lists it (spec 030 contracts/roles.md). */
final case class RoleRow(name: String, description: String, permissions: Vector[String], default: Boolean)

final class RolesRows extends View[RoleEvent, RoleRow]:
  def onChange(event: RoleEvent): Effect = (rowState, event) match
    case (_, e: RoleEvent.Defined) =>
      effects.updateRow(RoleRow(e.name, e.description, e.permissions.toVector.sorted, e.default))
    case (Some(r), e: RoleEvent.PermissionsChanged) => effects.updateRow(r.copy(description = e.description, permissions = e.after.toVector.sorted))
    case (Some(r), e: RoleEvent.Reset)              => effects.updateRow(r.copy(permissions = e.after.toVector.sorted))
    case (Some(_), _: RoleEvent.Deleted)            => effects.deleteRow()
    case _                                          => effects.ignore()

/** Every role of the brand: what every service of the brand reads, every 10 seconds. */
object Roles extends View.Companion[RolesRows, RoleEvent, RoleRow](
      componentId = ComponentId("roles"),
      source = ChangeSource.eventsOf(Role),
      rowSerializer = PlayersCodecs.serializer[RoleRow]("role-row")
    ):
  def create(ctx: ViewComponentContext) = new RolesRows

  val every: DeclaredQuery = query("every-role")(s"SELECT payload FROM $table ORDER BY row_key ASC")

  /** A brand has a handful of roles; 1,000 is far above any brand's. */
  def all(views: ViewClient): Vector[RoleRow] = views.forView(Roles).ask(every, 1000)

  def bags(views: ViewClient): Map[String, Set[String]] = all(views).map(r => r.name -> r.permissions.toSet).toMap
