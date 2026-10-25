package io.eitheror.casino.ingest.api

import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.ingest.Background.given
import io.eitheror.casino.ingest.api.Answers.Reply
import io.eitheror.casino.ingest.merchant.{Bound, Raced}
import io.eitheror.casino.ingest.requests.*
import io.eitheror.casino.ingest.shape.{Checked, Digests, Shape}

import java.util.UUID
import scala.concurrent.duration.*

/**
 * A checked create under its request id (FR-010 to FR-014; research R5, R6): claimed on the id's `Request`,
 * forwarded once per attempt to `merchant` as the key's sub-brand, raced against the answer bound, and
 * answered as `merchant` answered. A repeat with the same body gets the first session and asks `merchant`
 * nothing; another body is `409`; a refusal leaves the id free; an unanswered attempt frees it and its late
 * answer is a lost answer, never the id's.
 */
final class Create(ctx: Ctx, c: Context, subBrand: String, checked: Checked, body: ObjectNode, clientIp: String):
  private val requestId = checked.transactionId.getOrElse(UUID.randomUUID().toString)
  private val digest    = Digests.ofBody(body)
  private val request   = ctx.components.forKeyValueEntity(EntityId(Request.id(subBrand, requestId)))
  private def bound     = ctx.settings.answerBound.toMillis.millis

  c.record = c.record.copy(requestId = Some(requestId), amount = Some(checked.amount), currency = Some(checked.currency),
    country = Some(checked.country))

  def run(): Reply =
    val deadline = System.nanoTime() + bound.toNanos
    claim(deadline)

  /** `begin`; one in flight under the same body is waited for, within the handler's own bound. */
  private def claim(deadline: Long): Reply =
    val begun = request.call(Request.begin).invoke(Begin(digest, c.recordId, ctx.now()))
    begun.kind match
      case Begun.Forward => forward(begun.attempt)
      case Begun.Answered =>
        val a = begun.answer.getOrElse(Answer(200, "{}"))
        c.finish(Answers.passThrough(a), c.record.copy(outcome = Outcome.Repeated, ticket = a.text("session_token"), attempt = Some(begun.attempt)))
      case Begun.Mismatch =>
        c.finish(Answers.reused(requestId), c.record.copy(outcome = Outcome.RequestIdReused))
      case _ =>
        if System.nanoTime() < deadline then
          Thread.sleep(Create.PollMillis)
          claim(deadline)
        else c.finish(Answers.tryAgain, c.record.copy(outcome = Outcome.Unanswered, attempt = Some(begun.attempt)))

  private def forward(attempt: Int): Reply =
    val command = Shape.toCommand(subBrand, requestId, checked, body, clientIp)
    val record  = c.record.copy(attempt = Some(attempt))
    Bound.race(ctx.merchant.create(command), bound) match
      case Raced.Answered(a) if a.ok =>
        request.call(Request.answer).invoke(Settle(attempt, ctx.now(), Some(a))): Unit
        val ticket = a.text("session_token")
        ticket.foreach(t => ctx.components.forKeyValueEntity(EntityId(Ownership.ofTicket(t))).call(Ownership.set)
          .invoke(OwnershipState(subBrand, Some(requestId), c.recordId, Some(t))): Unit)
        c.finish(Answers.passThrough(a), record.copy(outcome = Outcome.Served, ticket = ticket))
      case Raced.Answered(a) =>
        request.call(Request.refused).invoke(Settle(attempt, ctx.now())): Unit
        c.finish(Answers.passThrough(a), record.copy(outcome = Outcome.Refused, refusal = a.text("error")))
      case Raced.Unanswered(late) =>
        request.call(Request.unanswered).invoke(Settle(attempt, ctx.now())): Unit
        val reply = c.finish(Answers.tryAgain, record.copy(outcome = Outcome.Unanswered))
        late.foreach(a => if a.ok then lateAnswer(attempt, a))
        reply
      case Raced.Failed(_) =>
        request.call(Request.unanswered).invoke(Settle(attempt, ctx.now())): Unit
        c.finish(Answers.tryAgain, record.copy(outcome = Outcome.Unanswered))

  /**
   * `merchant` made a session after the caller was told to try again: offered to the id first, which takes
   * it only if this attempt is still the one in flight (it never is, once released), and otherwise kept on
   * this attempt's record as a lost answer. No ownership is written for it: nobody legitimate holds it.
   */
  private def lateAnswer(attempt: Int, a: Answer): Unit =
    val taken = request.call(Request.answer).invoke(Settle(attempt, ctx.now(), Some(a)))
    if !taken.accepted then
      ctx.components.forKeyValueEntity(EntityId(c.recordId)).call(RequestRecord.lostAnswer)
        .invoke(LostAnswer(ctx.now(), a.text("session_token"))): Unit

object Create:
  /** How often a create waits on one in flight under the same request id. */
  val PollMillis = 200L
