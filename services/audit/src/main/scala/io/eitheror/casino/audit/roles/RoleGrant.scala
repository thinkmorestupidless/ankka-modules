package io.eitheror.casino.audit.roles

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.AuditCodecs

import java.time.Instant

/**
 * How a person came to hold a role (data-model.md `RoleGrant`), id `<role>/<pseudonym>`: the person by
 * their installation pseudonym, never their realm id. `granted` through the audit, by whom and when;
 * `outside` when the realm read found them holding it with no grant; `revoked` once taken away. A role
 * names at most one brand, so a grant is the role's alone.
 */
final case class GrantState(status: String = "", by: String = "", at: Option[Instant] = None):
  def holds: Boolean = status == RoleGrant.Granted || status == RoleGrant.Outside

final case class Grant(by: String, at: Instant)
final case class Revoke(by: String, at: Instant)
final case class Found(at: Instant)

/** What a call changed: `changed` false when the state already said so. */
final case class GrantChanged(changed: Boolean, state: GrantState)

final class RoleGrant extends KeyValueEntity[GrantState]:
  import RoleGrant.*
  def emptyState: GrantState = GrantState()

  def grant(c: Grant): Effect[GrantChanged] =
    if currentState.status == Granted then effects.reply(GrantChanged(false, currentState))
    else effects.updateState(GrantState(Granted, c.by, Some(c.at))).thenReply(GrantChanged(true, _))

  def revoke(c: Revoke): Effect[GrantChanged] =
    if !currentState.holds then effects.reply(GrantChanged(false, currentState))
    else effects.updateState(GrantState(Revoked, c.by, Some(c.at))).thenReply(GrantChanged(true, _))

  /** A holding the realm read found with no grant: recorded once, as `outside`. */
  def found(c: Found): Effect[GrantChanged] =
    if currentState.holds then effects.reply(GrantChanged(false, currentState))
    else effects.updateState(GrantState(Outside, "", Some(c.at))).thenReply(GrantChanged(true, _))

  def get: ReadOnlyEffect[GrantState] = effects.reply(currentState)

object RoleGrant extends KeyValueEntity.Companion[RoleGrant, GrantState](
      componentId = ComponentId("role-grant"),
      stateSerializer = AuditCodecs.serializer[GrantState]("role-grant")
    ):
  val Granted = "granted"
  val Outside = "outside"
  val Revoked = "revoked"

  def entityId(role: String, pseudonym: String): EntityId = EntityId(s"$role/$pseudonym")

  given Serializer[Grant]        = AuditCodecs.serializer[Grant]("grant")
  given Serializer[Revoke]       = AuditCodecs.serializer[Revoke]("revoke")
  given Serializer[Found]        = AuditCodecs.serializer[Found]("found")
  given Serializer[GrantChanged] = AuditCodecs.serializer[GrantChanged]("grant-changed")

  def create(context: KeyValueEntityContext) = new RoleGrant

  val grant  = command("grant")(_.grant)
  val revoke = command("revoke")(_.revoke)
  val found  = command("found")(_.found)
  val get    = query("get")(_.get)
