package io.eitheror.casino.players.clients

import scala.concurrent.{Await, Future, TimeoutException}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

/** How a call to another service or the realm stood at its bound. */
enum Raced[+A]:
  /** It answered within the bound. */
  case Answered(value: A)
  /** It had not answered: the caller decides (fail closed, or name it missing), and `late` is still heard. */
  case Unanswered(late: Future[A])
  /** It failed before the bound: the service could not be resolved or reached. */
  case Failed(cause: Throwable)

/**
 * A caller's bound on another service (research "For ankka": 0.13's service client has no per-call
 * timeout), as ingest's: the call is waited for up to the bound and never cancelled. Handlers run on
 * virtual threads, where the wait parks the thread.
 */
object Bound:
  def race[A](call: => Future[A], bound: FiniteDuration): Raced[A] =
    val running = Try(call).fold(Future.failed, identity)
    Try(Await.ready(running, bound)) match
      case Failure(_: TimeoutException) => Raced.Unanswered(running)
      case Failure(e)                   => Raced.Failed(e)
      case Success(_) =>
        running.value match
          case Some(Success(a)) => Raced.Answered(a)
          case Some(Failure(e)) => Raced.Failed(e)
          case None             => Raced.Unanswered(running)

  /** `race` with a `java.time.Duration` bound, as the settings hold them. */
  def within[A](bound: java.time.Duration)(call: => Future[A]): Raced[A] =
    race(call, FiniteDuration(bound.toMillis, java.util.concurrent.TimeUnit.MILLISECONDS))
