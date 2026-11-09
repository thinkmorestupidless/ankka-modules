package io.eitheror.casino.audit

import io.eitheror.casino.audit.ingest.IngestKeys

import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/**
 * What the consumers and timed actions are constructed without, as the programme's `Wiring`: their
 * companions construct them, so `AuditService` sets these before registering and the suites set fakes.
 */
object Wiring:
  val settings: AtomicReference[Settings] = AtomicReference(Settings())

  /** The clock records are received by: the system's in a cluster, the kit's movable one in the suites. */
  val clock: AtomicReference[Clock] = AtomicReference(Clock.systemUTC())

  /**
   * The keys ingest digests and seals under, set by the seed once it has read or made them. A consumer's
   * context has no secret store, so until the seed has run a message is refused and redelivered.
   */
  val keys: AtomicReference[Option[IngestKeys]] = AtomicReference(None)

  def requiredKeys: IngestKeys =
    keys.get().getOrElse(throw IllegalStateException("the registry and quarantine keys are not read yet: the seed has not run"))

  /** The service's views, set once it has started: a workflow's step reads the store through them. */
  val viewClient: AtomicReference[Option[com.thinkmorestupidless.ankka.runtime.ViewClient]] = AtomicReference(None)

  def views(): com.thinkmorestupidless.ankka.runtime.ViewClient =
    viewClient.get().getOrElse(throw IllegalStateException("the views are not wired yet: the service has not started"))
