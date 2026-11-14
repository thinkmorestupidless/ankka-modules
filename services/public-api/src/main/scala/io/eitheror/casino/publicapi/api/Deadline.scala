package io.eitheror.casino.publicapi.api

import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.publicapi.contract.{Refusal, Refusals}
import org.slf4j.LoggerFactory

import java.time.Duration
import java.util.concurrent.{ArrayBlockingQueue, ExecutionException, RejectedExecutionException, ThreadPoolExecutor, TimeUnit,
  TimeoutException}

/**
 * The request budget (research R11; original `REQUEST_TIMEOUT_MS = 5000`): a `/v1` handler's work runs on a thread of
 * its own and the request waits for it at most the settings' budget. On expiry, or when the work fails (a view read
 * that throws), the answer is `503 unavailable`. A refusal the work raises (`Refused`) is answered as itself.
 *
 * The threads are bounded (`Workers`, with `Waiting` more queued): a request that finds them all busy and the queue
 * full is `503` at once rather than another thread, and work that outlives its budget is interrupted, so a slow
 * database cannot grow the service's threads without limit.
 *
 * ankka's `request` is the calling thread's, so the work is given what it needs from the request before it starts and
 * never reads `request` itself.
 */
final class Deadline(budget: Duration, workers: Int = Deadline.Workers, waiting: Int = Deadline.Waiting):
  import Deadline.*

  /** Daemon threads, so a request that outlived its budget never holds the JVM open. */
  private val pool = ThreadPoolExecutor(workers, workers, 60L, TimeUnit.SECONDS, ArrayBlockingQueue[Runnable](waiting), { r =>
    val t = Thread(r, "public-api-request")
    t.setDaemon(true)
    t
  })
  pool.allowCoreThreadTimeOut(true)

  def run(work: => Respond[Bytes]): Respond[Bytes] =
    try
      val task = pool.submit(() => work)
      try task.get(budget.toMillis, TimeUnit.MILLISECONDS)
      catch
        case _: TimeoutException =>
          task.cancel(true): Unit
          log.warn("public_api_budget_exceeded budget_ms={}", budget.toMillis)
          Refusals.unavailable.respond
        case e: ExecutionException =>
          e.getCause match
            case Refused(r) => r.respond
            case cause =>
              log.warn("public_api_unavailable cause={}", Option(cause).fold("unknown")(_.getClass.getSimpleName))
              Refusals.unavailable.respond
    catch
      case _: RejectedExecutionException =>
        log.warn("public_api_busy workers={} waiting={}", workers, waiting)
        Refusals.unavailable.respond

object Deadline:
  private val log = LoggerFactory.getLogger("public-api")

  /** The threads that run `/v1` work, and how many requests may wait for one before the next is `503`. */
  val Workers = 64
  val Waiting = 256

  /** A refusal raised from inside the work, so a check deep in a read answers as the contract says. */
  final case class Refused(refusal: Refusal) extends RuntimeException(refusal.code, null, false, false)

  def refuse(r: Refusal): Nothing = throw Refused(r)
