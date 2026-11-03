package io.eitheror.casino.rewards.codes

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Serializer}
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.rewards.RewardsRuntime
import io.eitheror.casino.rewards.clients.{Called, Programme}

import java.time.Instant
import scala.concurrent.duration.Duration
import scala.jdk.DurationConverters.*
import scala.util.control.NonFatal

/**
 * Fetching the programme's code document for the brand (research R13; FR-021), from `REWARDS_CODE_DOCUMENT_URL`. A
 * document is kept only when it is read whole and is the brand's; anything else (no answer, a refusal, a body that is
 * not a document, another brand's) is a failure, logged without the address, and the last copy keeps serving.
 */
object CodesRefresh:
  private val log = org.slf4j.LoggerFactory.getLogger("io.eitheror.casino.rewards.codes.CodesRefresh")

  /** The timer's name: `codes-<slug>`. */
  def timerName(slug: String): String = s"codes-$slug"

  /** Fetches and keeps the document; whether it was kept. */
  def run(client: ComponentClient, programme: Programme, slug: String, url: String, at: Instant): Boolean =
    programme.fetch(url) match
      case Called.Done(d) if d.brand.isEmpty || d.brand == slug =>
        client.forKeyValueEntity(EntityId(slug)).call(CodeDocument.fetched).invoke(Fetched(d, at)): Unit
        true
      case Called.Done(_) =>
        log.warn(s"the code document for $slug names another brand; the last copy serves")
        false
      case Called.Refused(status, code, _) =>
        log.warn(s"the code document for $slug was refused ($status $code); the last copy serves")
        false
      case Called.Unreachable(why) =>
        log.warn(s"the code document for $slug could not be fetched ($why); the last copy serves")
        false

  /**
   * Sets `codes-<slug>` recurring at the refresh bound, first due at once, when the service has a document to read; with
   * none it sets nothing and the service is inert (research R13). Set at every start: the same timer keeps its cadence.
   */
  def schedule(timers: TimerScheduler): Unit =
    val s = RewardsRuntime.settings
    if s.codeDocumentUrl.isEmpty then timers.delete(timerName(s.brand))
    else
      try timers.createRecurringTimer(timerName(s.brand), Duration.Zero, s.codesEvery.toScala, CodesTimer.refresh.deferred(s.brand))
      catch case NonFatal(e) => log.error(s"the code document's timer could not be set: ${e.getMessage}")

/** `codes-<slug>` due: the document fetched and kept. A failure is not the timer's: the next period tries again. */
final class CodesTimer(context: TimedActionContext) extends TimedAction:
  def refresh(slug: String): Effect =
    RewardsRuntime.settings.codeDocumentUrl.foreach { url =>
      CodesRefresh.run(context.componentClient, RewardsRuntime.programme(context.services), slug, url, RewardsRuntime.now()): Unit
    }
    effects.done()

object CodesTimer extends TimedAction.Companion[CodesTimer](ComponentId("codes-timer")):
  given Serializer[String] = com.thinkmorestupidless.ankka.core.Serializers.string

  def create(context: TimedActionContext) = new CodesTimer(context)
  val refresh = handler("refresh")(_.refresh)
