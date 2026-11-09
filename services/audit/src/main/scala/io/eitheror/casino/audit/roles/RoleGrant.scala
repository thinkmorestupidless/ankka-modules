package io.eitheror.casino.audit.roles

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.AuditCodecs

import java.time.Instant

/**
 * How a person came to hold a role (data-model.md `RoleGrant`), id `<role>/<pseudonym>`: the person by
 * their installation pseudonym, never their realm id. `granted` through the audit, by whom and when,
 * with the brands a brand compliance grant covers; `outside` when the realm read found them holding it
 * with no grant; `revoked` once taken away.
 */
final case class GrantState(status: String = "", by: String = "", at: Option[Instant] = None, brands: Set[String] = Set.empty):
  def holds: Boolean = status == RoleGrant.Granted || status == RoleGrant.Outside

final case class Grant(by: String, brand: Option[String], at: Instant)
final case class Revoke(by: String, brand: Option[String], at: Instant)
final case class Found(at: Instant)

/** What a call changed: `changed` false when the state already said so. */
final case class GrantChanged(changed: Boolean, state: GrantState)

final class RoleGrant extends KeyValueEntity[GrantState]:
  import RoleGrant.*
  def emptyState: GrantState = GrantState()

  def grant(c: Grant): Effect[GrantChanged] =
    val s     = currentState
    val after = GrantState(Granted, c.by, Some(c.at), s.brands.filter(_ => s.status == Granted) ++ c.brand)
    if s.status == Granted && after.brands == s.brands then effects.reply(GrantChanged(false, s))
    else effects.updateState(after).thenReply(GrantChanged(true, _))

  /** A brand compliance revoke takes one brand; the role is revoked once no brand is left. */
  def revoke(c: Revoke): Effect[GrantChanged] =
    val s      = currentState
    val brands = c.brand.fold(Set.empty[String])(s.brands - _)
    if !s.holds then effects.reply(GrantChanged(false, s))
    else if brands.nonEmpty then effects.updateState(s.copy(brands = brands)).thenReply(GrantChanged(true, _))
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
