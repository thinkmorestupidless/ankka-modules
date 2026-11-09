package io.eitheror.casino.audit.registry

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.AuditCodecs

/**
 * Who a pseudonym stands for (data-model.md `Identity`), id the pseudonym: the one place the service
 * holds a real id (FR-009). It has no view; only the re-identification route reads it.
 */
final case class IdentityState(brand: String = "", kind: String = "", realId: String = ""):
  def bound: Boolean = realId.nonEmpty

final case class Bind(brand: String, kind: String, realId: String)

/** `bound` when written or written alike before; `conflict` when the pseudonym already names someone else. */
final case class Bound(outcome: String)

object IdentityCodecs:
  given Serializer[IdentityState] = AuditCodecs.serializer[IdentityState]("identity")
  given Serializer[Bind]          = AuditCodecs.serializer[Bind]("bind")
  given Serializer[Bound]         = AuditCodecs.serializer[Bound]("bound")

import IdentityCodecs.given

/** Written once; the same binding again changes nothing, another is refused as a conflict. */
final class Identity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[IdentityState]:
  def emptyState: IdentityState = IdentityState()

  def bind(cmd: Bind): Effect[Bound] =
    val asked = IdentityState(cmd.brand, cmd.kind, cmd.realId)
    if !currentState.bound then effects.updateState(asked).thenReply(_ => Bound(Identity.Bound))
    else if currentState == asked then effects.reply(Bound(Identity.Bound))
    else effects.reply(Bound(Identity.Conflict))

  def get: ReadOnlyEffect[IdentityState] = effects.reply(currentState)

object Identity extends KeyValueEntity.Companion[Identity, IdentityState](
      componentId = ComponentId("identity"),
      stateSerializer = IdentityCodecs.given_Serializer_IdentityState
    ):
  val Bound    = "bound"
  val Conflict = "conflict"

  def entityId(pseudonym: String): EntityId = EntityId(pseudonym)

  def create(context: KeyValueEntityContext) = new Identity(context)

  val bind = command("bind")(_.bind)
  val get  = query("get")(_.get)
