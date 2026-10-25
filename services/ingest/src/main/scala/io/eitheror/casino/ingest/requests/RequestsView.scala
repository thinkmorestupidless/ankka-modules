package io.eitheror.casino.ingest.requests

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.ingest.IngestCodecs

import java.time.Instant

/** A record as the operator reads it, with its arrival in epoch milliseconds to order and bound by. */
final case class RequestRow(at: Long, record: RecordState)

/**
 * The operator's reads of the records (FR-014, FR-017; research R8): a sub-brand's by time, every attempt
 * under a request id, and the lost answers not yet seen. SC-007's 2 s is this view's lag.
 */
final class RequestsView extends View[RecordState, RequestRow]:
  def onChange(state: RecordState): Effect =
    if !state.exists then effects.ignore()
    else effects.updateRow(RequestRow(state.receivedAt.fold(0L)(_.toEpochMilli), state))

object RequestsView extends View.Companion[RequestsView, RecordState, RequestRow](
      componentId = ComponentId("requests"),
      source = ChangeSource.stateOf(RequestRecord),
      rowSerializer = IngestCodecs.serializer[RequestRow]("request-row")
    ):
  def create(ctx: ViewComponentContext) = new RequestsView

  private val at       = "(payload::jsonb->>'at')::bigint"
  private val subBrand = "payload::jsonb->'record'->>'subBrand'"
  private val lost     = "payload::jsonb->'record'->>'outcome' = 'lost_answer' AND COALESCE((payload::jsonb->'record'->>'seen')::boolean, false) = false"

  val bySubBrand: DeclaredQuery = query("by-sub-brand")(
    s"SELECT payload FROM $table WHERE $subBrand = :sub_brand AND $at >= CAST(:from AS bigint) AND $at < CAST(:to AS bigint) " +
      s"ORDER BY $at DESC, row_key DESC")

  val byRequestId: DeclaredQuery = query("by-request-id")(
    s"SELECT payload FROM $table WHERE $subBrand = :sub_brand AND payload::jsonb->'record'->>'requestId' = :request_id ORDER BY $at ASC, row_key ASC")

  val lostAnswers: DeclaredQuery = query("lost-answers")(s"SELECT payload FROM $table WHERE $lost ORDER BY $at ASC, row_key ASC")

  val lostAnswersOf: DeclaredQuery = query("lost-answers-of")(
    s"SELECT payload FROM $table WHERE $lost AND $subBrand = :sub_brand ORDER BY $at ASC, row_key ASC")

  def of(views: ViewClient, subBrand: String, from: Instant, to: Instant, limit: Int): Vector[RecordState] =
    views.forView(RequestsView).ask(bySubBrand, limit, "sub_brand" -> subBrand, "from" -> from.toEpochMilli.toString,
      "to" -> to.toEpochMilli.toString).map(_.record)

  def byId(views: ViewClient, subBrand: String, requestId: String, limit: Int = 1000): Vector[RecordState] =
    views.forView(RequestsView).ask(byRequestId, limit, "sub_brand" -> subBrand, "request_id" -> requestId).map(_.record)

  def lostAnswers(views: ViewClient, subBrand: Option[String], limit: Int = 1000): Vector[RecordState] =
    subBrand.fold(views.forView(RequestsView).ask(lostAnswers, limit))(s =>
      views.forView(RequestsView).ask(lostAnswersOf, limit, "sub_brand" -> s)).map(_.record)
