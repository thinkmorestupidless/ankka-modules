package io.eitheror.casino.audit.sweep

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.audit.AuditCodecs
import io.eitheror.casino.audit.retention.Retention

/** A brand's retention (data-model.md `Retention`), id the brand: a longer one than the installation's, or none. */
final case class RetentionState(years: Option[Int] = None):
  def of(floor: Int): Int = years.fold(floor)(math.max(_, floor))

final case class Raise(years: Int, floor: Int)

/** The retention as raised, or the refusal (`below_floor: …`). */
final case class Raised(before: Int, after: Option[Int], refusal: Option[String] = None)

/** Raised only, never below the brand's current or the installation's (FR-021). */
final class RetentionEntity extends KeyValueEntity[RetentionState]:
  def emptyState: RetentionState = RetentionState()

  def raise(r: Raise): Effect[Raised] =
    val before = currentState.of(r.floor)
    Retention.raise(before, r.years, r.floor) match
      case Left(problem) => effects.reply(Raised(before, None, Some(problem)))
      case Right(years)  => effects.updateState(RetentionState(Some(years))).thenReply(_ => Raised(before, Some(years)))

  def get: ReadOnlyEffect[RetentionState] = effects.reply(currentState)

object RetentionEntity extends KeyValueEntity.Companion[RetentionEntity, RetentionState](
      componentId = ComponentId("retention"),
      stateSerializer = AuditCodecs.serializer[RetentionState]("retention")
    ):
  def entityId(brand: String): EntityId = EntityId(brand)

  given Serializer[Raise]  = AuditCodecs.serializer[Raise]("raise")
  given Serializer[Raised] = AuditCodecs.serializer[Raised]("raised")

  /** The brand's retention in years: its own when raised, else the installation's. */
  def yearsOf(client: ComponentClient, brand: String, floor: Int): Int =
    client.forKeyValueEntity(entityId(brand)).call(get).invoke().of(floor)

  def create(context: KeyValueEntityContext) = new RetentionEntity

  val raise = command("raise")(_.raise)
  val get   = query("get")(_.get)
