package io.eitheror.casino.kyc.freshness

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.kyc.KycCodecs
import io.eitheror.casino.kyc.queue.KycQueue
import io.eitheror.casino.kyc.record.KycRecord

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Spec 030 K4 (F; research R13, V2 under *Found during implementation*): `GET …/status`, the freshness
 * of each view behind an operator list. ankka 0.16.3 tells a service nothing of how far its views have
 * read (the projections' offsets are the runtime's, on the management port and in the metrics), so
 * each view's source is followed by a consumer of its own, [[ViewFollower]], that records in
 * [[ViewMark]] when it handled a change of that source. It reads the same changes as the view at the
 * same pace, so `asOf` is when the view applied its last change, to within the two projections' own
 * lag and at most [[ViewFollower.Every]] behind.
 *
 * `rebuilding` is false: a view rebuilt by raising its version replays where the service cannot see,
 * and the follower, which is not rebuilt with it, cannot tell. Nothing honest can be inferred.
 */
final case class ViewMarkState(asOf: Option[Instant] = None)
final case class Mark(at: Instant)

/** One view's mark, under the view's name: the latest time a follower of its source recorded; never earlier. */
final class ViewMark(@scala.annotation.unused context: KeyValueEntityContext) extends KeyValueEntity[ViewMarkState]:
  def emptyState: ViewMarkState = ViewMarkState()

  def mark(m: Mark): Effect[ViewMarkState] =
    if currentState.asOf.exists(!_.isBefore(m.at)) then effects.reply(currentState)
    else
      val next = ViewMarkState(Some(m.at))
      effects.updateState(next).thenReply(_ => next)

  def get: ReadOnlyEffect[ViewMarkState] = effects.reply(currentState)

object ViewMark
    extends KeyValueEntity.Companion[ViewMark, ViewMarkState](
      componentId = ComponentId("view-mark"),
      stateSerializer = KycCodecs.serializer[ViewMarkState]("view-mark-state")
    ):
  given Serializer[ViewMarkState] = KycCodecs.serializer[ViewMarkState]("view-mark")
  given Serializer[Mark]          = KycCodecs.serializer[Mark]("mark")

  def create(context: KeyValueEntityContext) = new ViewMark(context)

  val mark = command("mark")(_.mark)
  val get  = query("get")(_.get)

/**
 * Follows one view's source and marks the view at the time it handles a change, a deletion too. One
 * instance writes at most once every [[ViewFollower.Every]], so a burst of changes is one write; the
 * mark is then behind by less than that.
 */
final class ViewFollower[Src](view: String, client: ComponentClient, now: () => Instant) extends Consumer[Src, Nothing]:
  private val last = AtomicLong(Long.MinValue)

  def onMessage(change: Src): Effect = marked()
  override def onDelete: Effect      = marked()

  private def marked(): Effect =
    val at = now()
    val previous = last.get
    if previous != Long.MinValue && at.toEpochMilli - previous < ViewFollower.Every.toMillis then effects.ignore()
    else
      client.forKeyValueEntity(EntityId(view)).call(ViewMark.mark).invoke(Mark(at)): Unit
      last.set(at.toEpochMilli)
      effects.done()

object ViewFollower:
  val Every: java.time.Duration = java.time.Duration.ofSeconds(1)

  private def clock = () => io.eitheror.casino.kyc.KycRuntime.now()

  /** A follower of `source` marking `view`, named after both. */
  private abstract class Of[Src](view: ComponentId, source: ChangeSource[Src], tag: String = "")
      extends Consumer.Companion[ViewFollower[Src], Src, Nothing](
        ComponentId(s"$view${if tag.isEmpty then "" else s"-$tag"}-view-follower"), source):
    def create(ctx: ConsumerContext) = new ViewFollower[Src](view, ctx.componentClient, clock)

  private object QueueFollower extends Of(KycQueue.componentId, ChangeSource.eventsOf(KycRecord))

  /** The views behind the operator lists, in the order `status` answers them: the review queue. */
  val views: Vector[String] = Vector[String](KycQueue.componentId)

  val descriptors: Seq[com.thinkmorestupidless.ankka.core.ComponentDescriptor] = Seq(ViewMark.descriptor, QueueFollower.descriptor)

/** `GET …/status`'s answer, `{views: [{name, asOf, rebuilding}]}`; `asOf` is null for a view that has applied nothing. */
object Freshness:
  final case class ViewStatus(name: String, asOf: Option[Instant], rebuilding: Boolean)

  def of(client: ComponentClient, views: Vector[String] = ViewFollower.views): Vector[ViewStatus] =
    views.map(v => ViewStatus(v, client.forKeyValueEntity(EntityId(v)).call(ViewMark.get).invoke().asOf, rebuilding = false))

  /** The names are the views' component ids and `asOf` an ISO instant: neither needs escaping. */
  def answer(statuses: Vector[ViewStatus]): Respond[Bytes] =
    val views = statuses.map { s =>
      val asOf = s.asOf.fold("null")(t => s"\"$t\"")
      s"""{"name":"${s.name}","asOf":$asOf,"rebuilding":${s.rebuilding}}"""
    }
    Respond(Bytes("application/json", views.mkString("""{"views":[""", ",", "]}").getBytes("UTF-8")), 200, Vector.empty)
