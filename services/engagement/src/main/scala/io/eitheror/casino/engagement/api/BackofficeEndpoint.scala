package io.eitheror.casino.engagement.api

import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.engagement.{EngagementRuntime, Json}
import io.eitheror.casino.engagement.reads.SendsView
import io.eitheror.casino.engagement.send.Outcome

import java.time.Duration

import Answers.*

/**
 * `/v1/casino/backoffice/<slug>` (contracts/routes.md): what the brand's operators read of engagement. The status
 * (US1) and the agent stream under its ticket, since `EventSource` sends no bearer; the sends, the parked sends and
 * presence come with the rest of US7.
 */
final class BackofficeEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/backoffice/${ctx.slug}"):
  val acl: Acl = Routes.operator(ctx.slug, ctx.authenticate())

  get("/engagement/status") { () => ok(Status.of(ctx)) }

  post("/agent-stream/ticket") { () => ok(AgentStream.ticket(ctx, principal.subject)) }

  withAcl(Routes.public) {
    sseEvents("/agent-stream") { () => AgentStream.open(ctx, request.query.raw("ticket")) }
  }

/** `GET /engagement/status`, apart from the endpoint so a suite can read it. */
object Status:
  /** The outcomes the last hour is counted by, each present at 0 when none was reached. */
  val LastHour: Vector[String] = Vector(Outcome.Accepted, Outcome.Refused, Outcome.Parked, Outcome.SkippedStale,
    Outcome.NoConfiguration, Outcome.MarketingSuppressed, Outcome.AccountClosed)

  def of(ctx: Ctx) =
    val counts = SendsView.counts(ctx.sends, ctx.now().minus(Duration.ofHours(1)))
    val parked = SendsView.parked(ctx.sends)
    Json.obj(
      "configured"      -> ctx.config.configured,
      "processor"       -> "Customer.io",
      "parked"          -> Json.obj("count" -> parked.total, "oldestAt" -> SendsView.oldestParked(ctx.sends).flatMap(_.parkedAt).map(_.toString)),
      "lastHour"        -> Json.obj(LastHour.map(o => o -> counts.getOrElse(o, 0L))*),
      "webhookRefusals" -> Json.obj(EngagementRuntime.webhookRefusals.toVector.sortBy(_._1).map((k, v) => k -> v)*),
      "connections"     -> Json.obj(ctx.settings.instanceId -> EngagementRuntime.connections.get.toLong)
    )
