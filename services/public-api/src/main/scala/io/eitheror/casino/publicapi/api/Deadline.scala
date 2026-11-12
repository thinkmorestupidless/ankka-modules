package io.eitheror.casino.publicapi.api

import com.thinkmorestupidless.ankka.http.{Bytes, Respond}
import io.eitheror.casino.publicapi.contract.{Refusal, Refusals}
import org.slf4j.LoggerFactory

import java.time.Duration
import java.util.concurrent.{Executors, TimeUnit, TimeoutException}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
 * The request budget (research R11; original `REQUEST_TIMEOUT_MS = 5000`): a `/v1` handler's work runs on a thread of
 * its own and the request waits for it at most the settings' budget. On expiry, or when the work fails (a view read
 * that throws), the answer is `503 unavailable`. A refusal the work raises (`Refused`) is answered as itself.
 *
 * ankka's `request` is the calling thread's, so the work is given what it needs from the request before it starts and
 * never reads `request` itself.
 */
final class Deadline(budget: Duration):
  import Deadline.*

  def run(work: => Respond[Bytes]): Respond[Bytes] =
    val f = Future(work)(using pool)
    try Await.result(f, FiniteDuration(budget.toMillis, TimeUnit.MILLISECONDS))
    catch
      case _: TimeoutException =>
        log.warn("public_api_budget_exceeded budget_ms={}", budget.toMillis)
        Refusals.unavailable.respond
      case Refused(r) => r.respond
      case NonFatal(e) =>
        log.warn("public_api_unavailable cause={}", e.getClass.getSimpleName)
        Refusals.unavailable.respond

object Deadline:
  private val log = LoggerFactory.getLogger("public-api")

  /** Daemon threads, so a request that outlived its budget never holds the JVM open. */
  private val pool: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool { r =>
    val t = Thread(r, "public-api-request")
    t.setDaemon(true)
    t
  })

  /** A refusal raised from inside the work, so a check deep in a read answers as the contract says. */
  final case class Refused(refusal: Refusal) extends RuntimeException(refusal.code, null, false, false)

  def refuse(r: Refusal): Nothing = throw Refused(r)
