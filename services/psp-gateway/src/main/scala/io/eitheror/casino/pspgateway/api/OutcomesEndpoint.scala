package io.eitheror.casino.pspgateway.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.pspgateway.{GatewayRuntime, Json}
import io.eitheror.casino.pspgateway.sessions.{DepositSession, PayoutSession, Provider, Redrive, SessionRow, SessionsView}

/** `/v1/psp/outcomes`: payments operators list and re-drive the outcomes not delivered (FR-016). */
final class OutcomesEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/psp/outcomes"):
  val acl: Acl = Routes.paymentsOperator(ctx.authenticate())

  private def limit = request.query.raw("limit").flatMap(_.toIntOption).filter(_ > 0).map(math.min(_, 1000)).getOrElse(100)

  get("/undelivered") { () => Refusal.ok(OutcomesEndpoint.listing(SessionsView.undelivered(ctx.views, limit))) }
  get("/refused") { () => Refusal.ok(OutcomesEndpoint.listing(SessionsView.refused(ctx.views, limit))) }

  /** An operator's re-drive: an undelivered or refused outcome is delivered again now, by this operator. */
  post("/{reference}/redrive") { (reference: String) =>
    Refusal.guarded {
      val by     = principal.subject
      val at     = ctx.now()
      val entity = ctx.components.forEventSourcedEntity(EntityId(reference))
      val deposit = entity.call(DepositSession.get).invoke()
      val payout  = if deposit.exists then None else Some(entity.call(PayoutSession.get).invoke()).filter(_.exists)
      val r =
        if deposit.exists then Some(entity.call(DepositSession.redrive).invoke(Redrive(by, at)))
        else payout.map(_ => entity.call(PayoutSession.redrive).invoke(Redrive(by, at)))
      r match
        case None                    => Refusal(404, "not_found")
        case Some(x) if x.redriven   => Refusal.json(202, Json.obj("reference" -> reference, "redriven" -> true))
        case Some(x)                 => Refusal(409, "not_redrivable", Some(x.delivery.word))
    }
  }

object OutcomesEndpoint:
  def listing(rows: Vector[SessionRow]) =
    Json.obj("outcomes" -> Json.arr(rows.map(r => Json.obj("reference" -> r.reference, "kind" -> r.kind, "provider" -> r.provider,
      "receiver" -> r.receiver, "outcome" -> r.outcome, "attempts" -> r.attempts, "lastAnswer" -> r.lastAnswer,
      "since" -> java.time.Instant.ofEpochMilli(r.since).toString))*))

/**
 * `/status`: any service of the project, and payments operators through the ingress (FR-023): the
 * providers' configuration and health, and how many outcomes are undelivered and refused.
 */
final class StatusEndpoint(ctx: Ctx) extends HttpEndpoint("/status"):
  val acl: Acl = Routes.projectOrPaymentsOperator(ctx.settings.project, ctx.authenticate())

  get("/") { () =>
    val providers = Json.obj(Provider.values.toSeq.map { p =>
      val h = GatewayRuntime.http.health(p)
      p.wire -> Json.obj("state" -> h.state, "calls" -> h.calls, "failed" -> h.failed, "p95Seconds" -> h.p95Seconds,
        "configured" -> ctx.settings.configured(p))
    }*)
    Refusal.ok(Json.obj("providers" -> providers,
      "undelivered" -> SessionsView.count(ctx.views, "Undelivered"), "refused" -> SessionsView.count(ctx.views, "Refused")))
  }
