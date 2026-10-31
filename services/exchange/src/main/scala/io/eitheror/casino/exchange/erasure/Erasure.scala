package io.eitheror.casino.exchange.erasure

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * A player's erasure as the exchange keeps it (R13, FR-008): when, which resting orders it cancelled,
 * how many positions it left standing. The player id is a plain id on every fact and view (FR-006,
 * spec 001 FR-024), so nothing personal is rewritten; this record is the mark, and a repeat answers it.
 */
final case class Erased(at: Option[Instant] = None, cancelled: Vector[String] = Vector.empty, positionsKept: Int = 0)

object Erased:
  given Serializer[Erased] = Codecs.serializer[Erased]("exchange-erased")

final class ErasedEntity(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[Erased]:
  def emptyState: Erased = Erased()
  def mark(e: Erased): Effect[Erased] =
    if currentState.at.nonEmpty then effects.reply(currentState) else effects.updateState(e).thenReplyState
  def get: ReadOnlyEffect[Erased] = effects.reply(currentState)

object ErasedEntity extends KeyValueEntity.Companion[ErasedEntity, Erased](ComponentId("exchange-erased"), Erased.given_Serializer_Erased):
  def create(context: KeyValueEntityContext) = new ErasedEntity(context)
  val mark = command("mark")(_.mark)
  val get  = query("get")(_.get)
