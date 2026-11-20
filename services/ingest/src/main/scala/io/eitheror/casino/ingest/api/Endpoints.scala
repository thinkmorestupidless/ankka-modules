package io.eitheror.casino.ingest.api

import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.http.openapi.Description
import io.eitheror.casino.contract.http.Json as JsonBody
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.ingest.requests.Route

/**
 * `/v1/cashier`: a sub-brand's server or a brand's frontend proxy (contracts/routes.md). Public: the create
 * is admitted by the cashier key and the submit and abandon by the ticket, never by a platform identity.
 */
final class CashierEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/cashier"):
  val acl: Acl = Routes.public

  private def context(route: String) =
    val r = request
    Context(ctx, route, r.header)

  postBody("/sessions") { (body: String) =>
    val c        = context(Route.Create)
    val clientIp = io.eitheror.casino.ingest.shape.Shape.clientIp(request.header("X-Forwarded-For"))
    Context.run(c) {
      for
        parsed  <- c.body(body)
        _        = c.noted(parsed)
        sub     <- c.key()
        _       <- c.take(sub)
        checked <- io.eitheror.casino.ingest.shape.Fields.check(parsed, ctx.settings.requestIdBound)
                     .left.map(r => c.ended(Answers.invalid(r.field), io.eitheror.casino.ingest.requests.Outcome.InvalidField, Some(r.field)))
      yield Create(ctx, c, sub, checked, parsed, clientIp).run()
    }
  }

  // Spec 031: the ticket's two routes described for the player SDK, tagged `ticket` beside `player`
  // because their admission (the ticket, under `Routes.public`) does not show it. `POST /sessions`, a
  // sub-brand's server's, is not tagged.
  override protected def description: Description = Described.refusals

  described(_.summary("Submit the cashier session: start the payment at the terminal chosen")
    .body[Described.Submit].answers[Described.Submitted](200)
    .refuses(400, "not_json, INVALID_REQUEST, terminal_not_found, missing_crypto_currency, PAYMENT_REJECTED")
    .refuses(404, "SESSION_NOT_FOUND").refuses(429, "TOO_MANY_REQUESTS").refuses(502, "psp_error")
    .refuses(503, "TRY_AGAIN, OUTCOME_PENDING, psp_unavailable").tag("player", "ticket")) {
    postBody("/sessions/{ticket}/submit") { (ticket: String, body: JsonBody) => Ticket(ctx, context(Route.Submit), ticket).submit(body.text) }
  }

  described(_.summary("Abandon the cashier session's payment; answers the payment as it now stands")
    .answers[Described.Payment](200).refuses(404, "SESSION_NOT_FOUND, PAYMENT_NOT_FOUND").refuses(429, "TOO_MANY_REQUESTS")
    .refuses(503, "TRY_AGAIN").tag("player", "ticket")) {
    post("/sessions/{ticket}/abandon") { (ticket: String) => Ticket(ctx, context(Route.Abandon), ticket).abandon() }
  }

/** `/v1/payments`: the original's public read path, which the frontend's proxy maps `payments*` to. */
final class PaymentsEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/payments"):
  val acl: Acl = Routes.public

  override protected def description: Description = Described.refusals

  // Spec 031: read by the session's `cashierTicket` (the player's) or the sub-brand's key.
  described(_.summary("A payment, by the cashier ticket of the session that made it")
    .answers[Described.Payment](200).refuses(401, "UNAUTHENTICATED, INVALID_MERCHANT_KEY").refuses(404, "PAYMENT_NOT_FOUND")
    .refuses(429, "TOO_MANY_REQUESTS").refuses(503, "TRY_AGAIN").tag("player", "ticket")) {
    get("/{paymentId}") { (paymentId: String) =>
      val r = request
      Read(ctx, Context(ctx, Route.Read, r.header), paymentId, r.header).run()
    }
  }

/** `/v1/ingest`: payments operators at the installation's level, as 005's outcome routes (research R12). */
final class IngestEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/ingest"):
  val acl: Acl = Routes.paymentsOperator(ctx.authenticate())

  private val operator = Operator(ctx)

  get("/sub-brands/{subBrand}/requests") { (subBrand: String) => operator.requests(subBrand, request.query) }
  get("/sub-brands/{subBrand}/rate") { (subBrand: String) => Rate(ctx, subBrand).read() }
  putBody("/sub-brands/{subBrand}/rate") { (subBrand: String, body: String) => Rate(ctx, subBrand).set(body) }
  get("/lost-answers") { () => operator.lostAnswers(request.query) }
  post("/lost-answers/{recordId}/seen") { (recordId: String) => operator.seen(recordId) }
