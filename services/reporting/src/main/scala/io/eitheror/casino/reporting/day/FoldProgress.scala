package io.eitheror.casino.reporting.day

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.reporting.{ReportingCodecs, Settings}
import io.eitheror.casino.reporting.days.Days
import io.eitheror.casino.reporting.intake.Generation

import java.time.{Duration, Instant}
import scala.collection.concurrent.TrieMap

/**
 * How far one `to-days` consumer has read in a generation (research R7): the latest fact time it has folded, and the
 * instant it last read an event, of this generation or another. ankka reports no consumer lag (T031), so the closer
 * infers caught-up from these two.
 */
final case class ProgressState(consumer: String = "", latest: Option[Instant] = None, activeAt: Option[Instant] = None)

/** What a consumer tells its progress: the latest fact time it has seen (none when it has folded none yet), and now. */
final case class Progressed(consumer: String, latest: Option[Instant], at: Instant)

/** One consumer's progress, as the closer reads it. */
final case class ConsumerProgress(consumer: String, state: ProgressState)

/** Key value, id `g<n>/<consumer>`: each time only ever moves forward, so a repeated or late tell changes nothing. */
final class FoldProgress(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[ProgressState]:
  def emptyState: ProgressState = ProgressState()

  def record(p: Progressed): Effect[ProgressState] =
    val s    = currentState
    val next = ProgressState(p.consumer, FoldProgress.later(s.latest, p.latest), FoldProgress.later(s.activeAt, Some(p.at)))
    if next == s then effects.reply(s) else effects.updateState(next).thenReplyState

  def get: ReadOnlyEffect[ProgressState] = effects.reply(currentState)

object FoldProgress extends KeyValueEntity.Companion[FoldProgress, ProgressState](
      componentId = ComponentId("fold-progress"),
      stateSerializer = ReportingCodecs.serializer[ProgressState]("fold-progress")
    ):
  given Serializer[Progressed]    = ReportingCodecs.serializer[Progressed]("fold-progress-progressed")
  given Serializer[ProgressState] = ReportingCodecs.serializer[ProgressState]("fold-progress-reply")

  def id(generation: Int, consumer: String): String = Generation.id(generation, consumer)

  def create(context: KeyValueEntityContext) = new FoldProgress(context)

  val record = command("record")(_.record)
  val get    = query("get")(_.get)

  def later(a: Option[Instant], b: Option[Instant]): Option[Instant] = (a, b) match
    case (Some(x), Some(y)) => Some(if y.isAfter(x) then y else x)
    case _                  => a.orElse(b)

  /**
   * At most this long between two tells of one consumer while it reads, so its `activeAt` is never more than this behind,
   * far inside `CLOSE_IDLE`; a fact that takes its latest past another day's close is told at once.
   */
  val Refresh: Duration = Duration.ofSeconds(30)

  private final case class Told(latest: Option[Instant], at: Instant)

  /** What this instance has seen and told per consumer, so a fact does not cost a call: one entity per consumer. */
  private val seen = TrieMap.empty[String, Instant]
  private val told = TrieMap.empty[String, Told]

  /**
   * A consumer read an event at `now`, of this generation with its fact time `factAt`, or of another with none. It is
   * told when this instance has not told it, when `Refresh` has passed since, or when the latest fact seen is past the
   * close of a day the last tell was not.
   */
  def observed(client: ComponentClient, settings: Settings, consumer: String, factAt: Option[Instant], now: Instant): Unit =
    val key    = s"${settings.brand}/${id(settings.generation, consumer)}"
    val latest = factAt.fold(seen.get(key))(t => Some(seen.updateWith(key)(s => later(s, Some(t))).get))
    val last   = told.get(key)
    def closeDay(t: Instant) = Days.dayOf(t.minus(settings.closeGrace), settings.reportingZone)
    val crossed = latest.exists(l => last.forall(_.latest.forall(t => closeDay(t) < closeDay(l))))
    if last.forall(t => !now.isBefore(t.at.plus(Refresh))) || crossed then
      client.forKeyValueEntity(EntityId(id(settings.generation, consumer))).call(record).invoke(Progressed(consumer, latest, now)): Unit
      told.update(key, Told(latest, now))

  /** Each consumer's progress in the settings' generation, none for a consumer that has told nothing. */
  def of(client: ComponentClient, settings: Settings, consumers: Seq[String]): Vector[ConsumerProgress] =
    consumers.toVector.map(c =>
      ConsumerProgress(c, client.forKeyValueEntity(EntityId(id(settings.generation, c))).call(get).invoke()))

  /** Forgets what this instance has seen and told, for a suite or a rebuild in one JVM. */
  def forget(): Unit =
    seen.clear()
    told.clear()
