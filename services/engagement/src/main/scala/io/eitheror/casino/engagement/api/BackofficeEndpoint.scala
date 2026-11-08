package io.eitheror.casino.engagement.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.engagement.{EngagementRuntime, Json}
import io.eitheror.casino.engagement.Json.*
import io.eitheror.casino.engagement.profile.ProfileState
import io.eitheror.casino.engagement.reads.{PresenceView, SendRow, SendsView}
import io.eitheror.casino.engagement.send.{Discard, Outcome, Retry, Send, SendState}

import java.time.Duration

import Answers.*

/**
 * `/v1/casino/backoffice/<slug>` (contracts/routes.md): what the brand's operators read of engagement and do to it.
 * A player's sends and the parked sends from `SendsView`, newest first, 50 a page; a parked send retried or
 * discarded on its `Send`, each an audit record naming the operator; presence from `PresenceView`; the agent stream
 * under its ticket, since `EventSource` sends no bearer. A send's id is its key, which holds a `/`: a path carries it
 * encoded (`%2F`), and the route decodes it.
 */
final class BackofficeEndpoint(ctx: Ctx) extends HttpEndpoint(s"/v1/casino/backoffice/${ctx.slug}"):
  val acl: Acl = Routes.operator(ctx.slug, ctx.authenticate())

  get("/engagement/status") { () => ok(Status.of(ctx)) }

  post("/agent-stream/ticket") { () => ok(AgentStream.ticket(ctx, principal.subject)) }

  withAcl(Routes.public) {
    sseEvents("/agent-stream") { () => AgentStream.open(ctx, request.query.raw("ticket")) }
  }

  get("/players/{player}/sends") { (player: String) =>
    ok(Operators.page(SendsView.byPlayer(ctx.sends, player, Operators.pageOf(request.query.raw("page")))))
  }

  get("/parked-sends") { () =>
    ok(Operators.page(SendsView.parked(ctx.sends, request.query.raw("search"), Operators.pageOf(request.query.raw("page")))))
  }

  post("/parked-sends/{sendId}/retry") { (sendId: String) => Operators.retry(ctx, Operators.key(sendId), principal.subject) }

  postBody("/parked-sends/{sendId}/discard") { (sendId: String, text: String) =>
    Json.parseObject(text) match
      case None => badBody
      case Some(b) =>
        b.text("reason").map(_.trim).filter(_.nonEmpty)
          .fold(missingFields(Seq("reason")))(reason => Operators.discard(ctx, Operators.key(sendId), principal.subject, reason))
  }

  get("/presence") { () =>
    val (rows, count) = PresenceView.online(ctx.clients.viewClient.forView(PresenceView))
    ok(Json.obj("online" -> Json.arr(rows.map(r => Json.obj("playerId" -> r.playerId, "since" -> r.since.map(_.toString)))*),
      "count" -> count))
  }

/** The sends routes, apart from the endpoint so a suite can read them. */
object Operators:
  val NotFound  = "send_not_found"
  val NotParked = "not_parked"

  /** How long a retry waits for the attempt it starts: four attempts' waits and the CRM's bound, at most. */
  val RetryBound: Duration = Duration.ofSeconds(15)

  /** A send's id as the path carries it: ankka hands a path parameter over undecoded, and the key's `/` is `%2F`. */
  def key(sendId: String): String = java.net.URLDecoder.decode(sendId.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8)

  def pageOf(text: Option[String]): Int = text.flatMap(_.toIntOption).filter(_ > 0).getOrElse(1)

  def row(r: SendRow): ObjectNode =
    Json.obj("sendId" -> r.key, "trigger" -> r.trigger, "event" -> r.event, "class" -> r.sendClass, "channel" -> r.channel,
      "time" -> r.updatedAt.toString, "outcome" -> r.outcome, "reason" -> r.reason)

  def page(p: SendsView.Page): JsonNode =
    Json.obj("sends" -> Json.arr(p.rows.map(row)*), "page" -> p.page, "total" -> p.total)

  /** A send's record as an operator reads it after acting on it: its row, and who discarded it and why. */
  def record(key: String, s: SendState): JsonNode =
    val o = SendRow.of(key, s).map(row).getOrElse(Json.obj("sendId" -> key))
    s.discardedBy.foreach(by => Json.set(o, "discardedBy", by))
    ProfileState.reveal(s.discardReason).foreach(r => Json.set(o, "discardReason", r))
    o

  /** The send, or 404 `send_not_found`; 409 `not_parked` unless it is parked. */
  private def parked(ctx: Ctx, key: String)(act: SendState => Answer): Answer =
    val s = ctx.sendState(key)
    if s.request.isEmpty then refusal(404, NotFound, "sendId" -> key)
    else if s.outcome != Outcome.Parked then refusal(409, NotParked, "sendId" -> key, "outcome" -> s.outcome)
    else
      try act(s)
      catch case e: CommandError if e.getMessage != null && e.getMessage.contains(Send.NotParked) =>
        refusal(409, NotParked, "sendId" -> key)

  /** One more attempt at the call that parked it, judged afresh (FR-011); the record once the attempt has an outcome. */
  def retry(ctx: Ctx, key: String, operator: String): Answer = parked(ctx, key) { _ =>
    ctx.send(key).call(Send.retry).invoke(Retry(operator)): Unit
    val deadline = System.nanoTime + RetryBound.toNanos
    var s        = ctx.sendState(key)
    while s.outcome == Outcome.Pending && System.nanoTime < deadline do
      Thread.sleep(50)
      s = ctx.sendState(key)
    ok(record(key, s))
  }

  def discard(ctx: Ctx, key: String, operator: String, reason: String): Answer = parked(ctx, key) { _ =>
    ok(record(key, ctx.send(key).call(Send.discard).invoke(Discard(operator, reason))))
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
      "connections"     -> Json.obj(PresenceView.connectionsByInstance(ctx.clients.viewClient.forView(PresenceView)).toVector.sorted*)
    )
