package io.eitheror.casino.engagement.api

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.engagement.EngagementRuntime
import io.eitheror.casino.engagement.channel.{Channel, Frames, Polled}
import io.eitheror.casino.engagement.channel.ChannelCommand.{Ack, Close, Heartbeat, Open, Poll}
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.Source

import java.time.{Duration, Instant}
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * `/v1/stream` (FR-020 to FR-023; contracts/routes.md): a player's push stream. The connection is the channel's
 * (research R10), never this instance's: opened there, leased there every 30 s, and polled there every second for the
 * signals after the connection's cursor, each written as a frame and then acked. ankka checks the sign-in only when
 * the stream opens, so the re-check reads the token's expiry (research, *Found during implementation*).
 */
final class StreamEndpoint(ctx: Ctx) extends HttpEndpoint("/v1/stream"):
  val acl: Acl = Stream.acl(ctx.slug, ctx.authenticate())

  sseEvents("/") { () =>
    val p      = principal
    val player = Routes.playerId(p).getOrElse(throw HttpProblem(403, Stream.Unresolved, Map.empty))
    Stream.open(ctx, player, p.claims.get("exp").flatMap(_.toLongOption).map(Instant.ofEpochSecond))
  }

object Stream:
  val Unresolved = "subject_unresolved"
  val Capacity   = "capacity"

  /** What a frame queue may hold before the connection is dropped, with no frame, as the original (R10). */
  val Queue = 64

  /** Who may open the stream: a player of this brand whose sign-in names their id; any other sign-in has no subject. */
  def acl(slug: String, base: Acl): Acl = base match
    case Acl.Authenticate(decide) =>
      Acl.Authenticate { (context: RequestContext) =>
        decide(context) match
          case allowed @ AuthDecision.Allow(p) => refusal(slug, p).fold(allowed)(AuthDecision.Forbidden(_))
          case other                           => other
      }
    case other => throw IllegalArgumentException(s"the stream needs an authenticating ACL, not $other")

  def refusal(slug: String, p: Principal): Option[String] =
    if Routes.playerId(p).isEmpty then Some(Unresolved)
    else if p.issuer.contains(Routes.PlayerIssuer) && Routes.realmOf(p).contains(s"casino-$slug") then None
    else Some(s"${Routes.Refusal.WrongBrand}: this is brand $slug")

  private val stopping = AtomicReference(Promise[Unit]())

  /** Every open stream writes `closing` with `shutting_down` and ends; the service is stopping. */
  def stop(): Unit = stopping.get.trySuccess(()): Unit

  /** Streams may open and stay open again (each start, and a suite after [[stop]]). */
  def resume(): Unit = if stopping.get.isCompleted then stopping.set(Promise[Unit]())

  /**
   * One connection's times and cursor. Only the stream's single `mapAsync(1)` stage reads or writes it, one tick after
   * another, so it needs no lock.
   */
  private final class Connection(val id: String, val player: String, val expiresAt: Option[Instant], now: Instant,
      val recycleAt: Instant, revalidateEvery: Duration, var cursor: Long):
    private val jitter    = 1.0 + ThreadLocalRandom.current().nextDouble(0.2)
    val revalidation      = Duration.ofMillis((revalidateEvery.toMillis * jitter).toLong)
    var heartbeatAt       = now
    var leaseAt           = now
    var checkAt           = now.plus(revalidation)
    var reason            = "client_closed"
    @volatile var ended   = false

  /**
   * The stream for `player`, opened on the channel; refused 503 `capacity` when this instance holds its maximum. The
   * first frame is `ready`; then a tick a second, until a closing reason or the channel says the connection is gone.
   * However it ends, the connection is closed on the channel.
   */
  def open(ctx: Ctx, player: String, expiresAt: Option[Instant]): Source[SseEvent, ?] =
    val s = ctx.settings
    if EngagementRuntime.connections.incrementAndGet() > s.maxConnections then
      EngagementRuntime.connections.decrementAndGet(): Unit
      throw HttpProblem(503, Capacity, Map("Retry-After" -> "5"))
    val now     = ctx.now()
    val id      = UUID.randomUUID().toString
    val channel = ctx.components.forEventSourcedEntity(EntityId(player))
    val opened =
      try channel.call(Channel.open).invoke(Open(id, s.instanceId, now))
      catch
        case NonFatal(e) =>
          EngagementRuntime.connections.decrementAndGet(): Unit
          throw e
    val recycleAt = now.plus(recycle(s.recycleAfter, id))
    val c         = Connection(id, player, expiresAt, now, recycleAt, s.revalidateEvery, opened.seq)
    given ExecutionContext = ExecutionContext.parasitic

    def tick(): Future[Vector[SseEvent]] =
      val at = ctx.now()
      closing(c, at, ctx) match
        case Some(reason) =>
          c.reason = reason
          c.ended = true
          Future.successful(Vector(Frames.closing(reason)))
        case None =>
          val beat = Option.when(!at.isBefore(c.heartbeatAt.plus(s.heartbeatEvery))) {
            c.heartbeatAt = at
            Frames.heartbeat(at)
          }
          val lease: Future[Boolean] =
            if at.isBefore(c.leaseAt.plus(s.leaseEvery)) then Future.successful(true)
            else
              c.leaseAt = at
              channel.call(Channel.heartbeat).invokeAsync(Heartbeat(id, at)).map(_.open)
          for
            leased <- lease
            polled <- if leased then channel.call(Channel.poll).invokeAsync(Poll(id, c.cursor)) else Future.successful(gone)
            _      <- if polled.open && polled.seq > c.cursor then ack(polled.seq, at) else Future.unit
          yield
            if !polled.open then
              c.reason = "expired"
              c.ended = true
              Vector.empty
            else polled.signals.map(sig => Frames.signal(ctx.slug, sig.correlationId)) ++ beat

    def ack(seq: Long, at: Instant): Future[Unit] =
      channel.call(Channel.acked).invokeAsync(Ack(id, seq, at)).map(_ => c.cursor = seq)

    Source.single(Vector(Frames.ready(player, now)))
      .concat(Source.tick(s.pollEvery.toMillis.millis, s.pollEvery.toMillis.millis, ()).mapAsync(1)(_ => tick()))
      .takeWhile(_ => !c.ended, inclusive = true)
      .mapConcat(identity)
      .buffer(Queue, OverflowStrategy.fail)
      .watchTermination() { (mat, done) =>
        done.onComplete { result =>
          if result.isFailure then c.reason = "dropped"
          EngagementRuntime.connections.decrementAndGet(): Unit
          channel.call(Channel.close).invokeAsync(Close(id, c.reason, ctx.now())): Unit
        }
        mat
      }

  private val gone = Polled(open = false, Vector.empty, missed = false, 0L)

  /**
   * The reason the stream closes now, if any: the service stopping; the token expired at the re-check (every 30 s ×
   * 1.0–1.2, once per connection); the connection past its recycle age, judged when its lease is next due.
   */
  private def closing(c: Connection, at: Instant, ctx: Ctx): Option[String] =
    if stopping.get.isCompleted then Some("shutting_down")
    else if !at.isBefore(c.checkAt) && { c.checkAt = at.plus(c.revalidation); c.expiresAt.exists(!_.isAfter(at)) } then
      Some("revalidation_failed")
    else if !at.isBefore(c.leaseAt.plus(ctx.settings.leaseEvery)) && !at.isBefore(c.recycleAt) then Some("connection_recycled")
    else None

  /** 5 min × (1 + (|id.hashCode| % 20) / 100), as the original's sweeper, so a fleet's streams do not recycle together. */
  def recycle(after: Duration, id: String): Duration =
    Duration.ofMillis(after.toMillis * (100 + math.abs(id.hashCode % 20)) / 100)
