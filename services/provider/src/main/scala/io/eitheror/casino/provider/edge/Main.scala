package io.eitheror.casino.provider.edge

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import io.eitheror.casino.provider.edge.admin.ProviderEndpoint
import com.thinkmorestupidless.ankka.runtime.{Ankka, AnkkaService, ProjectionRuntime, TimerRuntime}
import io.eitheror.casino.provider.brands.Sender
import io.eitheror.casino.provider.core.*
import io.eitheror.casino.provider.edge.brands.{BrandCache, BrandEntryEntity, BrandIndex, HouseOutbound}
import io.eitheror.casino.provider.edge.games.GamesEndpoint
import io.eitheror.casino.provider.edge.operator.{GameHealth, OperatorApi}
import io.eitheror.casino.provider.edge.registry.{GameEntity, GameIndex}
import io.eitheror.casino.provider.edge.views.SessionsByBrand
import io.eitheror.casino.provider.st8.*

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration.*

/**
 * The house provider (spec 026): the library's core with the ST8 protocol, the registry and the
 * brand entries as entities, and its endpoints each at its own prefix. Nothing of the simulator is
 * here (FR-016, `ProductSuite`): no scenario, no instruction route, no game page.
 */
object Provider:

  val descriptors: Seq[ComponentDescriptor] = Seq(
    SessionEntity.descriptor, CallbackEntity.descriptor, Deliver.descriptor,
    RunCallbacks.descriptor, SweepAction.descriptor,
    GameEntity.descriptor, GameIndex.descriptor, BrandEntryEntity.descriptor, BrandIndex.descriptor, SessionsByBrand.descriptor,
    io.eitheror.casino.provider.edge.operator.BonusIndex.descriptor
  )

  /** What the endpoints share: built once, from the first clients offered. */
  final class Parts(clients: EndpointClients, val settings: Settings, val cache: BrandCache):
    val keys  = Keys(clients.secrets)
    val ready = AtomicBoolean(false)

  def server(settings: Settings, cache: BrandCache, holder: AtomicReference[Parts], at: Option[(String, Int)] = None,
      authenticate: Option[Acl] = None): HttpServer =
    def parts(c: EndpointClients): Parts =
      holder.compareAndSet(null, Parts(c, settings, cache))
      holder.get()
    val factories: Seq[EndpointClients => HttpEndpoint] = Seq(
      c => KeyDocument(parts(c).keys),
      c => { val p = parts(c); Health(() => p.ready.get()) },
      c => GamesEndpoint(c.componentClient),
      c => OperatorApi(c.componentClient, cache, GameHealth(name => c.services(name)).apply, name => c.services(name)),
      c => ProviderEndpoint(c.componentClient, c.viewClient, cache, parts(c).keys, authenticate.getOrElse(Oidc.authenticate()))
    )
    at.fold(HttpServer.of(factories*))((host, port) => HttpServer.at(host, port)(factories*))

  /** The core's collaborators, once the service (and its secret store) has started. */
  def install(service: AnkkaService, settings: Settings, cache: BrandCache, keys: Option[Keys] = None, clock: () => Long = () => System.currentTimeMillis()): Keys =
    val ring = keys.getOrElse(Keys(service.secrets))
    Wiring.install(St8Protocol, HouseOutbound(Sender(cache.apply, ring)), ring, cache, clock)
    Deliver.schedule = settings.schedule
    Sweep.retention = settings.retentionDays.days
    Sweep.views = Some(service.viewClient)
    ring

  /**
   * The work done once at start: a signing key, the brand entries read, the retention sweep, and
   * the wake that brings back a `Deliver` whose pause no timer in memory holds any more.
   */
  def prepare(service: AnkkaService, cache: BrandCache, keys: Keys, timers: Option[TimerRuntime], parts: => Option[Parts],
      wakeEvery: FiniteDuration = 30.seconds): Unit =
    keys.ensureCurrent(): Unit
    cache.refresh(service.componentClient)
    Sweep.wakeEvery = wakeEvery
    timers.foreach { t =>
      t.timerScheduler.createRecurringTimer(SweepAction.TimerName, 1.minute, 1.hour, SweepAction.sweep.deferred)
      t.timerScheduler.createRecurringTimer(SweepAction.WakeTimerName, wakeEvery, wakeEvery, SweepAction.wake.deferred)
    }
    parts.foreach(_.ready.set(true))

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(problem => throw IllegalStateException(problem), identity)
  val cache    = BrandCache()
  val holder   = AtomicReference[Provider.Parts]()
  val timers   = TimerRuntime()
  val service = Provider.descriptors.foldLeft(Ankka.service)((b, d) => b.register(d))
    .withExtension(ProjectionRuntime())
    .withExtension(timers)
    .withExtension(Provider.server(settings, cache, holder))
    .start(name = "provider")
  val keys = Provider.install(service, settings, cache)
  Provider.prepare(service, cache, keys, Some(timers), Option(holder.get()))

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
