package io.eitheror.casino.engagement.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.{HttpProblem, SseEvent}
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.channel.{AgentChannel, AgentPolled, AgentTicket, Frames}
import io.eitheror.casino.engagement.channel.AgentChannelCommand.{Close, Heartbeat, Open, Poll}
import io.eitheror.casino.engagement.channel.AgentTicketCommand.{Issue, Redeem}
import io.eitheror.casino.engagement.profile.ProfileState
import org.apache.pekko.stream.scaladsl.Source

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

/**
 * The backoffice's agent stream (contracts/routes.md): `EventSource` sends no bearer, so the operator buys a ticket
 * with theirs and opens the stream with the ticket, once, within 30 s. The stream is the brand's `AgentChannel`
 * connection, polled every second for the players who came online, with a `ping` every 15 s and the lease every 30 s.
 */
object AgentStream:
  val InvalidTicket = "invalid_ticket"

  private val log = org.slf4j.LoggerFactory.getLogger("io.eitheror.casino.engagement.api.AgentStream")

  /** `POST /agent-stream/ticket`: a new ticket for the brand and the operator. */
  def ticket(ctx: Ctx, operator: String) =
    val id     = AgentTicket.newTicket()
    val now    = ctx.now()
    val issued = ctx.components.forKeyValueEntity(EntityId(id)).call(AgentTicket.issue).invoke(Issue(ctx.slug, operator, now))
    Json.obj("ticket" -> id, "expires_in" -> java.time.Duration.between(now, issued.expiresAt).toSeconds)

  /** `GET /agent-stream?ticket=`: refused `invalid_ticket` before the stream opens, the reason logged only. */
  def open(ctx: Ctx, ticket: Option[String]): Source[SseEvent, ?] =
    val s   = ctx.settings
    val now = ctx.now()
    val redeemed = ticket.filter(_.nonEmpty).map(t =>
      ctx.components.forKeyValueEntity(EntityId(t)).call(AgentTicket.redeem).invoke(Redeem(ctx.slug, now)))
    redeemed match
      case Some(r) if r.ok => log.info(s"agent stream opened for ${r.operator.getOrElse("")}")
      case other =>
        log.info(s"agent stream refused: ${other.flatMap(_.refused).fold("no ticket")(_.wire)}")
        throw HttpProblem(403, InvalidTicket, Map.empty)
    val id      = UUID.randomUUID().toString
    val channel = ctx.components.forEventSourcedEntity(EntityId(ctx.slug))
    var cursor  = channel.call(AgentChannel.open).invoke(Open(id, s.instanceId, now)).seq
    var pingAt  = now
    var leaseAt = now
    @volatile var ended = false
    given ExecutionContext = ExecutionContext.parasitic

    // Only the one `mapAsync(1)` stage reads or writes the vars above, a tick after another.
    def tick(): Future[Vector[SseEvent]] =
      val at   = ctx.now()
      val ping = Option.when(!at.isBefore(pingAt.plus(s.heartbeatEvery))) { pingAt = at; Frames.ping() }
      val lease: Future[Boolean] =
        if at.isBefore(leaseAt.plus(s.leaseEvery)) then Future.successful(true)
        else
          leaseAt = at
          channel.call(AgentChannel.heartbeat).invokeAsync(Heartbeat(id, at)).map(_.open)
      for
        leased <- lease
        polled <- if leased then channel.call(AgentChannel.poll).invokeAsync(Poll(id, cursor)) else Future.successful(gone)
      yield
        if !polled.open then
          ended = true
          Vector.empty
        else
          cursor = polled.seq
          polled.frames.map(f => Frames.playerOnline(f.online.playerId,
            ProfileState.reveal(Some(f.online.emailPrefix)).getOrElse(""), f.online.sessionId, f.online.occurredAt)) ++ ping

    Source.tick(s.pollEvery.toMillis.millis, s.pollEvery.toMillis.millis, ()).mapAsync(1)(_ => tick())
      .takeWhile(_ => !ended, inclusive = true)
      .mapConcat(identity)
      .watchTermination() { (mat, done) =>
        done.onComplete(_ => channel.call(AgentChannel.close).invokeAsync(Close(id, "closed", ctx.now())): Unit)
        mat
      }

  private val gone = AgentPolled(open = false, Vector.empty, 0, 0L)
