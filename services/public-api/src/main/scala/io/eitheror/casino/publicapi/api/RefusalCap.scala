package io.eitheror.casino.publicapi.api

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * FR-036's bound on a key's refusals: this instance records at most `perMinute` refusals of one key a minute; the
 * refusals beyond are counted, and the count is named on the key's next recorded refusal, so a leaked key retried in a
 * loop costs the brand a bounded number of writes. The count is in memory, per instance: it is lost when the instance
 * stops or the key is never refused again, and the cap's warning names the key but not the count. The bound is
 * `perMinute` times the instances, and costs no write of its own.
 */
final class RefusalCap(perMinute: Int, now: () => Instant):
  import RefusalCap.*

  private val keys = ConcurrentHashMap[String, Window]()

  /** `Some(n)` when this refusal of `keyId` is recorded, `n` the refusals not recorded before it; `None` when it is counted. */
  def refused(keyId: String): Option[Int] =
    val minute = now().getEpochSecond / 60
    var answer = Option.empty[Int]
    var capped = false
    keys.compute(keyId, (_, w0) => {
      val w = Option(w0).filter(_.minute == minute).getOrElse(Window(minute, 0, 0, Option(w0).fold(0)(_.unrecorded)))
      if w.recorded < perMinute then { answer = Some(w.unrecorded); w.copy(recorded = w.recorded + 1, unrecorded = 0) }
      else { capped = w.over == 0; w.copy(over = w.over + 1, unrecorded = w.unrecorded + 1) }
    }): Unit
    if capped then log.warn("public_api_refusals_capped key={} per_minute={}", keyId, perMinute)
    answer

object RefusalCap:
  private val log = org.slf4j.LoggerFactory.getLogger("public-api")

  /** The refusals of one key recorded in full a minute, on each instance. */
  val PerMinute = 60

  /** A key's minute: refusals recorded in it, refusals over the cap in it, and refusals not yet named on a record. */
  private final case class Window(minute: Long, recorded: Int, over: Int, unrecorded: Int)
