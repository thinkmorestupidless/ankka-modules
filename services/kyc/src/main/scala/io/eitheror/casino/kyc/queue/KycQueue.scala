package io.eitheror.casino.kyc.queue

import com.thinkmorestupidless.ankka.core.{ComponentId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.kyc.KycCodecs
import io.eitheror.casino.kyc.record.*

import java.time.Instant

/**
 * One player's row in the operator's queue (data-model.md *KycQueue*, R12). It keeps what its columns are
 * computed from, the documents awaiting review and the open flags, so each event is applied by the record's
 * own rule (`KycRecord.applied`) and no rule is written twice. No personal value is kept: an operator's
 * upload reason is dropped from each document, and an open flag has no dismissal note.
 */
final case class QueueRow(
    playerId: String,
    status: String = Status.Unverified.wire,
    mode: String = Mode.Automatic.wire,
    awaiting: Int = 0,
    oldestAwaitingAt: Option[Instant] = None,
    readyAt: Option[Instant] = None,
    flags: Vector[String] = Vector.empty,
    flaggedAt: Option[Instant] = None,
    sof: String = Sof.Unverified.wire,
    depositsByCurrency: Map[String, Long] = Map.empty,
    updatedAt: Option[Instant] = None,
    pending: Vector[Document] = Vector.empty,
    open: Vector[Flag] = Vector.empty
):
  /** The record as far as the row knows it. */
  private[queue] def record: KycState =
    KycState(playerId, documents = pending, status = Status.of(status).getOrElse(Status.Unverified),
      mode = Mode.of(mode).getOrElse(Mode.Automatic), sof = if sof == Sof.Cleared.wire then Sof.Cleared else Sof.Unverified,
      readyAt = readyAt, flags = open, deposits = depositsByCurrency.map((c, t) => c -> Deposits(t)))

object QueueRow:
  given Serializer[QueueRow] = KycCodecs.serializer[QueueRow]("queue-row")

  def of(s: KycState, at: Instant): QueueRow =
    val pending = s.documents.filter(_.awaiting).map(d => d.copy(by = d.by.copy(reason = None)))
    val open    = s.flags.filter(_.open)
    QueueRow(
      playerId = s.playerId,
      status = s.status.wire,
      mode = s.mode.wire,
      awaiting = pending.size,
      oldestAwaitingAt = pending.map(_.uploadedAt).minOption,
      readyAt = s.readyAt,
      flags = open.map(_.reason.wire).distinct,
      flaggedAt = open.map(_.at).minOption,
      sof = s.sof.wire,
      depositsByCurrency = s.deposits.map((c, d) => c -> d.total),
      updatedAt = Some(at),
      pending = pending,
      open = open
    )

  def next(row: QueueRow, event: KycEvent): QueueRow = of(KycRecord.applied(row.record, event), event.at)

final class KycQueueView extends View[KycEvent, QueueRow]:
  def onChange(event: KycEvent): Effect =
    val row  = rowState.getOrElse(QueueRow(updateContext.subject))
    val next = QueueRow.next(row, event)
    if rowState.isDefined && next.copy(updatedAt = row.updatedAt) == row then effects.ignore() else effects.updateRow(next)

/**
 * The queue's queries (FR-035), each listing oldest first by its filter's time; `sort=newest` reverses it at the
 * route. A row's default values are not written (`KycCodecs.stored`), so a column at its default reads as null.
 */
object KycQueue extends View.Companion[KycQueueView, KycEvent, QueueRow](
      componentId = ComponentId("kyc-queue"),
      source = ChangeSource.eventsOf(KycRecord),
      rowSerializer = QueueRow.given_Serializer_QueueRow
    ):
  private def p(field: String) = s"payload::jsonb->>'$field'"

  val awaiting = query("awaiting")(s"SELECT payload FROM $table WHERE (${p("awaiting")})::int > 0 ORDER BY ${p("oldestAwaitingAt")}")

  val byStatus = query("by-status")(s"SELECT payload FROM $table WHERE COALESCE(${p("status")}, 'unverified') = :status ORDER BY ${p("updatedAt")}")

  val ready = query("ready")(s"SELECT payload FROM $table WHERE ${p("readyAt")} IS NOT NULL ORDER BY ${p("readyAt")}")

  val flagged = query("flagged")(
    s"SELECT payload FROM $table WHERE jsonb_exists(payload::jsonb->'flags', :reason) ORDER BY ${p("flaggedAt")}")

  /** For `PolicyFlagger`: players not verified whose lifetime deposits in `currency` exceed `amount`. */
  val overThreshold = query("over-threshold")(
    s"SELECT payload FROM $table WHERE ${p("status")} IS DISTINCT FROM 'verified' " +
      s"AND COALESCE(jsonb_extract_path_text(payload::jsonb, 'depositsByCurrency', :currency)::bigint, 0) > CAST(:amount AS bigint) ORDER BY row_key")

  /** For `PolicyFlagger`: players whose source of funds is not cleared and whose lifetime deposits in `currency` exceed `amount`. */
  val overSof = query("over-sof")(
    s"SELECT payload FROM $table WHERE ${p("sof")} IS DISTINCT FROM 'cleared' " +
      s"AND COALESCE(jsonb_extract_path_text(payload::jsonb, 'depositsByCurrency', :currency)::bigint, 0) > CAST(:amount AS bigint) ORDER BY row_key")

  def create(ctx: ViewComponentContext) = new KycQueueView
