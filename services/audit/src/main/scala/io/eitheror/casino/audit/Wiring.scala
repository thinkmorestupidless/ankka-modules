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
   * The keys ingest digests and seals under. The seed makes them, on whichever node runs the timer;
   * every node reads them from the secret store it is handed (an endpoint's at start, a consumer's
   * context), so a node the seed did not run on reads them too. Until they are made, a message is
   * refused and redelivered.
   */
  val keys: AtomicReference[Option[IngestKeys]] = AtomicReference(None)

  /** This node's secret store, set as the endpoints are built and by each consumer's message. */
  val secretStore: AtomicReference[Option[com.thinkmorestupidless.ankka.sdk.SecretStore]] = AtomicReference(None)

  def requiredKeys: IngestKeys =
    keys.get().getOrElse {
      val store = secretStore.get().getOrElse(throw IllegalStateException("the keys are not read yet: no secret store is wired"))
      val read  = IngestKeys(io.eitheror.casino.audit.registry.RegistryKey.required(store),
        io.eitheror.casino.audit.registry.QuarantineKey.required(store), settings.get().skewBound)
      keys.set(Some(read))
      read
    }

  /** The keys, read from `store` on a node that has not read them yet. */
  def keysFrom(store: com.thinkmorestupidless.ankka.sdk.SecretStore): IngestKeys =
    if secretStore.get().isEmpty then secretStore.set(Some(store))
    requiredKeys

  /** The service's views, set once it has started: a workflow's step reads the store through them. */
  val viewClient: AtomicReference[Option[com.thinkmorestupidless.ankka.runtime.ViewClient]] = AtomicReference(None)

  def views(): com.thinkmorestupidless.ankka.runtime.ViewClient =
    viewClient.get().getOrElse(throw IllegalStateException("the views are not wired yet: the service has not started"))

  /** The realm the roles routes and the daily realm read grant and list roles in; none in a local run. */
  val realm: AtomicReference[io.eitheror.casino.audit.roles.KeycloakAdmin] =
    AtomicReference(io.eitheror.casino.audit.roles.KeycloakAdmin.unconfigured)
