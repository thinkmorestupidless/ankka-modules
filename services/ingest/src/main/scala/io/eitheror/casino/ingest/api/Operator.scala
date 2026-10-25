package io.eitheror.casino.ingest.api

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.http.QueryParams
import io.eitheror.casino.ingest.Json
import io.eitheror.casino.ingest.api.Answers.Reply
import io.eitheror.casino.ingest.requests.{RecordState, RequestRecord, RequestsView}

import java.time.{Duration, Instant}
import scala.util.Try

/**
 * A payments operator reads what sub-brands sent (FR-014, FR-017): a sub-brand's records by time or by
 * request id, the lost answers not yet seen, and marks one seen. Every answer is the record's fields and
 * nothing more: there is nothing more to give (SC-006).
 */
final class Operator(ctx: Ctx):

  def requests(subBrand: String, query: QueryParams): Reply =
    val limit = query.raw("limit").flatMap(_.toIntOption).filter(_ > 0).map(math.min(_, 1000)).getOrElse(100)
    val records = query.raw("requestId").filter(_.nonEmpty) match
      case Some(id) => RequestsView.byId(ctx.views, subBrand, id, limit)
      case None =>
        val to   = instant(query.raw("to")).getOrElse(ctx.now().plusMillis(1))
        val from = instant(query.raw("from")).getOrElse(to.minus(Duration.ofHours(24)))
        RequestsView.of(ctx.views, subBrand, from, to, limit)
    Answers.ok(Json.obj("requests" -> Json.arr(records.map(Operator.render)*)))

  def lostAnswers(query: QueryParams): Reply =
    val records = RequestsView.lostAnswers(ctx.views, query.raw("subBrand").filter(_.nonEmpty))
    Answers.ok(Json.obj("lostAnswers" -> Json.arr(records.map(Operator.render)*)))

  def seen(recordId: String): Reply =
    try
      ctx.components.forKeyValueEntity(EntityId(recordId)).call(RequestRecord.markSeen).invoke(): Unit
      Answers.noContent
    catch
      case e: CommandError if e.code == ErrorCode.NotFound => Answers.refusal(404, "not_found")
      case e: CommandError if e.code == ErrorCode.Conflict => Answers.refusal(409, "not_a_lost_answer")

  private def instant(text: Option[String]): Option[Instant] = text.flatMap(t => Try(Instant.parse(t)).toOption)

object Operator:
  /** A record as contracts/routes.md lists it: data-model.md's fields, the absent ones left out. */
  def render(r: RecordState): ObjectNode =
    Json.obj("recordId" -> r.recordId, "subBrand" -> r.subBrand, "receivedAt" -> r.receivedAt.map(_.toString), "route" -> r.route,
      "requestId" -> r.requestId, "amount" -> r.amount, "currency" -> r.currency, "country" -> r.country, "outcome" -> r.outcome,
      "refusal" -> r.refusal, "ticket" -> r.ticket, "paymentId" -> r.paymentId, "attempt" -> r.attempt,
      "lostAnswer" -> r.lostAnswer.map(l => Json.obj("answeredAt" -> l.answeredAt.toString, "ticket" -> l.ticket, "paymentId" -> l.paymentId)),
      "seen" -> Option.when(r.lostAnswer.isDefined)(r.seen))
