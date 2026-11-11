package io.eitheror.casino.reporting.intake

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.{ReportingCodecs, Settings}
import io.eitheror.casino.reporting.days.Days

import java.time.{Instant, LocalDate, ZoneId}
import scala.collection.concurrent.TrieMap

/** The earliest fact of a topic folded in this generation (research R8). */
final case class CoverageState(topic: String = "", earliest: Option[Instant] = None)

/** A fact's time, folded by its topic's consumer. */
final case class Observed(topic: String, at: Instant)

/**
 * How far back a topic's figures are complete (data-model.md `Coverage`, key value, `g<n>/<topic>`): ankka has no
 * "complete from" (ankka 043), so each intake consumer records the earliest `occurredAt` it has folded in the
 * generation, and the status answers the first whole reporting day after it (FR-004).
 */
final class Coverage(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[CoverageState]:
  def emptyState: CoverageState = CoverageState()

  /** Keeps the earlier time; the reply is the earliest. */
  def observe(o: Observed): Effect[CoverageState] =
    if currentState.earliest.exists(!_.isAfter(o.at)) then effects.reply(currentState)
    else effects.updateState(CoverageState(o.topic, Some(o.at))).thenReplyState

  def get: ReadOnlyEffect[CoverageState] = effects.reply(currentState)

object Coverage extends KeyValueEntity.Companion[Coverage, CoverageState](
      componentId = ComponentId("coverage"),
      stateSerializer = ReportingCodecs.serializer[CoverageState]("coverage")
    ):
  given Serializer[Observed]      = ReportingCodecs.serializer[Observed]("coverage-observed")
  given Serializer[CoverageState] = ReportingCodecs.serializer[CoverageState]("coverage-reply")

  def id(generation: Int, topic: String): String = Generation.id(generation, topic)

  def create(context: KeyValueEntityContext) = new Coverage(context)

  val observe = command("observe")(_.observe)
  val get     = query("get")(_.get)

  /** The first whole reporting day from `earliest`: its own day when it falls on its midnight, else the next. */
  def completeFrom(earliest: Instant, zone: ZoneId): LocalDate =
    val day = Days.dayOf(earliest, zone)
    if Days.startOf(day, zone) == earliest then LocalDate.parse(day) else LocalDate.parse(day).plusDays(1)

  /**
   * The earliest time each consumer has told this instance, so a fact no earlier than it makes no call: the coverage
   * entity is one per topic and every fact would otherwise queue on it. Empty after a restart, which costs one call.
   */
  private val known = TrieMap.empty[String, Instant]

  /** Tells the topic's coverage `at` when it is earlier than this instance has told it. */
  def observed(client: ComponentClient, settings: Settings, topic: String, at: Instant): Unit =
    val key = id(settings.generation, topic)
    if known.get(key).forall(at.isBefore) then
      val earliest = client.forKeyValueEntity(EntityId(key)).call(observe).invoke(Observed(topic, at)).earliest.getOrElse(at)
      known.updateWith(key)(k => Some(k.filter(_.isBefore(earliest)).getOrElse(earliest))): Unit

  /** Forgets what this instance has told, for a suite. */
  def forget(): Unit = known.clear()
