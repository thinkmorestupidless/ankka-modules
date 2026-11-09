package io.eitheror.casino.engagement.it

import com.fasterxml.jackson.databind.JsonNode
import io.eitheror.casino.engagement.Json
import io.eitheror.casino.engagement.Json.*

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** One call either Customer.io host received, with what it was answered. */
final case class CrmCall(host: String, path: String, headers: Map[String, String], raw: String, status: Int):
  val body: Option[JsonNode] = Json.parse(raw)
  def accepted: Boolean      = status / 100 == 2

/**
 * Customer.io's two hosts in process, as `tests/stubs/crm-stub` answers them (research R2): Track
 * (`POST /api/v2/entity`, basic `siteId:trackKey`, 200 `{}`) and App (`POST /v1/send/email`, bearer
 * `appKey`, 200 `{delivery_id, queued_at}`); a wrong credential is 401. One log of every call and one
 * script across both: a scripted step is taken before the credential check, in order. `reset()`
 * clears the log and the script and keeps the credentials.
 */
final class FakeCrm:
  private enum Step:
    case Fail(status: Int)
    case Hang
    case Refuse(status: Int, body: String)
    case Pass

  private val log    = mutable.ArrayBuffer.empty[CrmCall]
  private val script = mutable.Queue.empty[Step]

  @volatile private var siteId   = FakeCrm.SiteId
  @volatile private var trackKey = FakeCrm.TrackKey
  @volatile private var appKey   = FakeCrm.AppKey
  /** How long a hang holds before answering 504: past the client's 10 s timeout. */
  @volatile var hangMillis: Long = 30_000L

  private final class Host(name: String, routePath: String)(answer: Received => Answer) extends FakeServer():
    def clear(): Unit = resetServer()
    // A hung call is recorded (as the 504 it ends with) before it holds, so a test sees it while it hangs.
    protected def route(r: Received): Answer =
      val step = Option.when(r.method == "POST" && r.path == routePath)(next())
      val a = step match
        case None                               => Answer(404, """{"error":"not_found"}""")
        case Some(Some(Step.Fail(status)))      => FakeCrm.meta(status, s"HTTP $status")
        case Some(Some(Step.Refuse(status, b))) => Answer(status, b)
        case Some(Some(Step.Hang))              => Answer(504, """{"error":"hung"}""")
        case Some(Some(Step.Pass) | None) if Json.parse(r.body).isEmpty => FakeCrm.meta(400, "the body is not JSON")
        case Some(Some(Step.Pass) | None)       => answer(r)
      log.synchronized(log += CrmCall(name, r.path, r.headers, r.body, a.status))
      if step.contains(Some(Step.Hang)) then Thread.sleep(hangMillis)
      a

  private val track = Host("track", "/api/v2/entity") { r =>
    if r.headers.get("authorization").contains(FakeCrm.basic(siteId, trackKey)) then Answer(200, "{}")
    else FakeCrm.unauthorised
  }
  private val app = Host("app", "/v1/send/email") { r =>
    if r.headers.get("authorization").contains(s"Bearer $appKey") then
      Answer.ok(Json.obj("delivery_id" -> java.util.UUID.randomUUID.toString.replace("-", "").take(24),
        "queued_at" -> java.time.Instant.now.getEpochSecond))
    else FakeCrm.unauthorised
  }

  val trackUrl: String = track.address
  val appUrl: String   = app.address

  def expect(siteId: String, trackKey: String, appKey: String): Unit =
    this.siteId = siteId
    this.trackKey = trackKey
    this.appKey = appKey

  /** The next `count` calls answer `status` (429, 500, 503…). */
  def fail(status: Int, count: Int = 1): Unit = enqueue(count, Step.Fail(status))
  /** The next `count` calls answer only after `hangMillis`, so the caller's timeout fires first. */
  def hang(count: Int = 1): Unit = enqueue(count, Step.Hang)
  /** The next `count` calls are refused with `status` and `body`, as a 4xx the provider means. */
  def refuse(status: Int, body: String, count: Int = 1): Unit = enqueue(count, Step.Refuse(status, body))
  /** The next `count` calls are answered as unscripted, so a later step applies to a later call. */
  def pass(count: Int = 1): Unit = enqueue(count, Step.Pass)

  def reset(): Unit =
    script.synchronized(script.clear())
    log.synchronized(log.clear())
    hangMillis = 30_000L
    track.clear()
    app.clear()

  def stop(): Unit =
    track.stop()
    app.stop()

  /** Every call to either host, oldest first. */
  def calls: Vector[CrmCall]    = log.synchronized(log.toVector)
  def entities: Vector[JsonNode] = calls.filter(_.host == "track").flatMap(_.body)
  def identifies(playerId: String): Vector[JsonNode] = entity("identify", playerId)
  def tracks(playerId: String): Vector[JsonNode]     = entity("event", playerId)
  def deletes(playerId: String): Vector[JsonNode]    = entity("delete", playerId)
  /** Every App body, oldest first. */
  def sends: Vector[JsonNode] = calls.filter(_.host == "app").flatMap(_.body)
  def attributes(call: JsonNode): Map[String, JsonNode] =
    Option(call.get("attributes")).filter(_.isObject).map(_.properties.asScala.map(e => e.getKey -> e.getValue).toMap).getOrElse(Map.empty)
  /** The calls answered 2xx. */
  def accepted: Int = calls.count(_.accepted)

  private def entity(action: String, playerId: String): Vector[JsonNode] =
    entities.filter(b => b.text("action").contains(action) && Option(b.get("identifiers")).flatMap(_.text("id")).contains(playerId))

  private def enqueue(count: Int, step: Step): Unit = script.synchronized(script.enqueueAll(Vector.fill(math.max(count, 1))(step)))
  private def next(): Option[Step] = script.synchronized(Option.when(script.nonEmpty)(script.dequeue()))

object FakeCrm:
  val SiteId   = "stub-site"
  val TrackKey = "stub-track-key"
  val AppKey   = "stub-app-key"

  def basic(user: String, password: String): String =
    "Basic " + Base64.getEncoder.encodeToString(s"$user:$password".getBytes(UTF_8))

  private def meta(status: Int, error: String): Answer = Answer.json(status, Json.obj("meta" -> Json.obj("error" -> error)))
  private val unauthorised: Answer                     = meta(401, "Unauthorized request")
