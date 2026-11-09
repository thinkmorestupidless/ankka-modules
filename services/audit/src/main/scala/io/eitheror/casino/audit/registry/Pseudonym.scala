package io.eitheror.casino.audit.registry

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.AuditCodecs

import java.util.UUID

/** A person's pseudonym (data-model.md `Pseudonym`), id the HMAC of `<brand>/<kind>/<realId>`: no real id in its id. */
final case class PseudonymState(pseudonym: String = "")

/** The pseudonym, and whether this call drew it. */
final case class Issued(pseudonym: String, issued: Boolean)

object PseudonymCodecs:
  given Serializer[PseudonymState] = AuditCodecs.serializer[PseudonymState]("pseudonym")
  given Serializer[Issued]         = AuditCodecs.serializer[Issued]("issued")

import PseudonymCodecs.given

/**
 * The first `issue` draws a random UUIDv4 and every later one answers it (FR-009): the entity serialises
 * two first sightings of a person into one pseudonym. A pseudonym is never derived from the real id.
 */
final class Pseudonym(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[PseudonymState]:
  def emptyState: PseudonymState = PseudonymState()

  def issue: Effect[Issued] =
    if currentState.pseudonym.nonEmpty then effects.reply(Issued(currentState.pseudonym, issued = false))
    else effects.updateState(PseudonymState(UUID.randomUUID().toString)).thenReply(s => Issued(s.pseudonym, issued = true))

  def get: ReadOnlyEffect[PseudonymState] = effects.reply(currentState)

object Pseudonym extends KeyValueEntity.Companion[Pseudonym, PseudonymState](
      componentId = ComponentId("pseudonym"),
      stateSerializer = PseudonymCodecs.given_Serializer_PseudonymState
    ):
  def entityId(registryId: String): EntityId = EntityId(registryId)

  def create(context: KeyValueEntityContext) = new Pseudonym(context)

  val issue = command("issue")(_.issue)
  val get   = query("get")(_.get)
