package io.eitheror.casino.ingest.merchant

import scala.concurrent.{Await, Future, TimeoutException}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

/** How a call to `merchant` stood at the answer bound (research R6). */
enum Raced[+A]:
  /** It answered within the bound. */
  case Answered(value: A)
  /** It had not answered: the caller is told to try again, and `late` is still heard (FR-014). */
  case Unanswered(late: Future[A])
  /** It failed before the bound: `merchant` could not be resolved or reached. */
  case Failed(cause: Throwable)

/**
 * The caller's bound on `merchant` (R6): the call is waited for up to the bound and never cancelled, so its
 * answer can still arrive after the caller was told to try again. ankka 0.11's service client has no
 * per-call timeout; its one timeout, `ankka.service-client.timeout`, is the window a late answer is caught in.
 * Handlers run on virtual threads, where the wait parks the thread.
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
