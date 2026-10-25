package io.eitheror.casino.ingest

import java.time.Instant

/**
 * What the entities and the endpoints share that is the service's: the settings and the clock. A suite
 * wires its own, with a short answer bound and a clock it moves.
 */
object IngestRuntime:
  @volatile var settings: Settings = Settings()
  @volatile var now: () => Instant = () => Instant.now()

  def wire(s: Settings, clock: () => Instant = () => Instant.now()): Unit =
    settings = s
    now = clock

/** Where a late answer is heard after its caller was answered: a virtual thread each, as a handler runs on. */
object Background:
  given ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.fromExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
