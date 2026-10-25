package io.eitheror.casino.ingest.api

import com.thinkmorestupidless.ankka.core.EntityId
import io.eitheror.casino.ingest.Background.given
import io.eitheror.casino.ingest.api.Answers.Reply
import io.eitheror.casino.ingest.merchant.{Bound, Raced}
import io.eitheror.casino.ingest.requests.*
import io.eitheror.casino.ingest.shape.Shape

import scala.concurrent.duration.*

/**
 * A submit or an abandon by the cashier ticket (FR-001, FR-009; research R7): the ticket is the credential,
 * and only a ticket an ownership names reaches `merchant`, under its owner's rate. There is no `Request`
 * here: `merchant` answers a second submit under one ticket with the first's payment (004 correction 2).
 */
final class Ticket(ctx: Ctx, c: Context, ticket: String):
  private def bound = ctx.settings.answerBound.toMillis.millis

  /** The owner of the ticket, or `404 SESSION_NOT_FOUND` under no sub-brand. */
  private def owned(): Either[Ended, OwnershipState] =
    c.record = c.record.copy(ticket = Some(ticket))
    c.owner(Ownership.ofTicket(ticket)).toRight(c.ended(Answers.sessionNotFound, Outcome.NotFound))

  def submit(raw: String): Reply =
    Context.run(c) {
      for
        body  <- c.body(raw)
        owner <- owned()
        _     <- c.take(owner.subBrand)
      yield forward(owner, ctx.merchant.submit(ticket, Shape.submitBody(body)), submitted = true)
    }

  def abandon(): Reply =
    Context.run(c) {
      for
        owner <- owned()
        _     <- c.take(owner.subBrand)
      yield forward(owner, ctx.merchant.abandon(ticket), submitted = false)
    }

  private def forward(owner: OwnershipState, call: => scala.concurrent.Future[Answer], submitted: Boolean): Reply =
    c.record = c.record.copy(requestId = owner.requestId)
    Bound.race(call, bound) match
      case Raced.Answered(a) if a.ok =>
        val paymentId = a.text("paymentId")
        if submitted then paymentId.foreach(own(owner, _))
        c.finish(Answers.passThrough(a), c.record.copy(outcome = Outcome.Served, paymentId = paymentId))
      case Raced.Answered(a) =>
        c.finish(Answers.passThrough(a), c.record.copy(outcome = Outcome.Refused, refusal = a.text("error")))
      case Raced.Unanswered(late) =>
        val reply = c.finish(Answers.tryAgain, c.record.copy(outcome = Outcome.Unanswered))
        if submitted then late.foreach(a => if a.ok then lost(a))
        reply
      case Raced.Failed(_) =>
        c.finish(Answers.tryAgain, c.record.copy(outcome = Outcome.Unanswered))

  /** The payment belongs to the ticket's owner, and the ticket names its payment. */
  private def own(owner: OwnershipState, paymentId: String): Unit =
    val o = OwnershipState(owner.subBrand, owner.requestId, owner.recordId, Some(ticket), Some(paymentId))
    ctx.components.forKeyValueEntity(EntityId(Ownership.ofPayment(paymentId))).call(Ownership.set).invoke(o): Unit
    ctx.components.forKeyValueEntity(EntityId(Ownership.ofTicket(ticket))).call(Ownership.set).invoke(o): Unit

  /** A submit `merchant` answered after the bound: the payment it made, for the operator, and no ownership (FR-014). */
  private def lost(a: Answer): Unit =
    ctx.components.forKeyValueEntity(EntityId(c.recordId)).call(RequestRecord.lostAnswer)
      .invoke(LostAnswer(ctx.now(), Some(ticket), a.text("paymentId"))): Unit

/**
 * A payment read (FR-009): with the creating sub-brand's key, or with the `cashierTicket` of the session that
 * made it. Anything else is `404`, the same answer as for a payment that does not exist, and `merchant` is
 * not asked; neither header is `401`.
 */
final class Read(ctx: Ctx, c: Context, paymentId: String, header: String => Option[String]):
  private def bound = ctx.settings.answerBound.toMillis.millis

  def run(): Reply =
    Context.run(c) {
      c.record = c.record.copy(paymentId = Some(paymentId))
      for
        owner <- whose()
        _     <- c.take(owner)
      yield Bound.race(ctx.merchant.read(paymentId), bound) match
        case Raced.Answered(a) if a.ok => c.finish(Answers.passThrough(a), c.record.copy(outcome = Outcome.Served))
        case Raced.Answered(a)         => c.finish(Answers.passThrough(a), c.record.copy(outcome = Outcome.Refused, refusal = a.text("error")))
        case _                         => c.finish(Answers.tryAgain, c.record.copy(outcome = Outcome.Unanswered))
    }

  private def payment = c.owner(Ownership.ofPayment(paymentId))

  private def whose(): Either[Ended, String] =
    val key    = header(Context.KeyHeader).map(_.trim).filter(_.nonEmpty)
    val ticket = header(Context.TicketHeader).map(_.trim).filter(_.nonEmpty)
    (key, ticket) match
      case (Some(_), _) =>
        c.key().flatMap(sub => payment.filter(_.subBrand == sub).map(_.subBrand).toRight(c.ended(Answers.paymentNotFound, Outcome.NotFound)))
      case (None, Some(t)) =>
        c.record = c.record.copy(ticket = Some(t))
        c.owner(Ownership.ofTicket(t)).filter(_.paymentId.contains(paymentId)).map(_.subBrand)
          .toRight(c.ended(Answers.paymentNotFound, Outcome.NotFound))
      case (None, None) => Left(c.ended(Answers.unauthenticated, Outcome.KeyMissing))

