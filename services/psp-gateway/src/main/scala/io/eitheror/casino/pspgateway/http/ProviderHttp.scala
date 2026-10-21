package io.eitheror.casino.pspgateway.http

import io.eitheror.casino.pspgateway.providers.{Answered, CallFailure, Outbound}
import io.eitheror.casino.pspgateway.sessions.Provider

import java.net.URI
import java.net.http.{HttpClient, HttpConnectTimeoutException, HttpRequest, HttpResponse, HttpTimeoutException}
import java.time.{Clock, Duration, Instant}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** A provider's health as the status page shows it (FR-023, R11). */
final case class Health(state: String, calls: Int, failed: Int, p95Seconds: Double)

/**
 * Every call to a provider (FR-023): a 5 s connect bound, `PROVIDER_TIMEOUT` per request, no redirects
 * followed. Each provider keeps a window of its last 20 calls: `down` when the last 5 failed, `degraded`
 * when more than half did, else `up`. A failure is no answer, a timeout or a `5xx`.
 */
final class ProviderHttp(timeout: Duration, clock: Clock = Clock.systemUTC()):
  private val client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .followRedirects(HttpClient.Redirect.NEVER)
    .build()

  private final case class Sample(ok: Boolean, seconds: Double)
  private val windows = mutable.Map.empty[Provider, Vector[Sample]]

  /** A call for one provider, as the pure packages take it. */
  def caller(provider: Provider): Outbound => Either[CallFailure, Answered] = call(provider, _)

  def call(provider: Provider, out: Outbound): Either[CallFailure, Answered] =
    val started = clock.instant()
    val result =
      try
        val builder = HttpRequest.newBuilder(URI.create(out.url)).timeout(timeout)
        out.headers.foreach((k, v) => builder.header(k, v))
        out.contentType.foreach(builder.header("Content-Type", _))
        val publisher = out.body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString)
        val response  = client.send(builder.method(out.method, publisher).build(), HttpResponse.BodyHandlers.ofString())
        val headers   = response.headers.map.asScala.toMap.map((k, v) => k.toLowerCase -> v.asScala.headOption.getOrElse(""))
        Right(Answered(response.statusCode, headers, Option(response.body).getOrElse("")))
      catch
        case _: HttpConnectTimeoutException => Left(CallFailure.Unreachable("connect timed out"))
        case _: HttpTimeoutException        => Left(CallFailure.TimedOut)
        case _: InterruptedException        => Thread.currentThread.interrupt(); Left(CallFailure.Unreachable("interrupted"))
        case NonFatal(e)                    => Left(CallFailure.Unreachable(e.getClass.getSimpleName))
    record(provider, started, result)
    result

  private def record(provider: Provider, started: Instant, result: Either[CallFailure, Answered]): Unit =
    val ok      = result.exists(_.status / 100 != 5)
    val seconds = Duration.between(started, clock.instant()).toMillis / 1000.0
    synchronized {
      windows.update(provider, (windows.getOrElse(provider, Vector.empty) :+ Sample(ok, seconds)).takeRight(ProviderHttp.Window))
    }

  def health(provider: Provider): Health = synchronized {
    ProviderHttp.health(windows.getOrElse(provider, Vector.empty).map(s => (s.ok, s.seconds)))
  }

object ProviderHttp:
  val Window = 20
  val DownAfter = 5

  def health(samples: Vector[(Boolean, Double)]): Health =
    val failed = samples.count(!_._1)
    val state =
      if samples.size >= DownAfter && samples.takeRight(DownAfter).forall(!_._1) then "down"
      else if samples.nonEmpty && failed * 2 > samples.size then "degraded"
      else "up"
    val sorted = samples.map(_._2).sorted
    val p95 = if sorted.isEmpty then 0.0 else sorted(math.min(sorted.size - 1, math.ceil(sorted.size * 0.95).toInt - 1))
    Health(state, samples.size, failed, p95)
