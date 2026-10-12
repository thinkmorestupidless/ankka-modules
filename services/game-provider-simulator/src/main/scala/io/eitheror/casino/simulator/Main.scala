package io.eitheror.casino.simulator

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, EntityId}
import com.thinkmorestupidless.ankka.http.{EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, AnkkaService, ProjectionRuntime, TimerRuntime}
import io.eitheror.casino.simulator.api.{Access, SimulatorEndpoint}
import io.eitheror.casino.simulator.brands.{BrandTable, Sender}
import io.eitheror.casino.simulator.core.*
import io.eitheror.casino.simulator.st8.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * The service definition: every component, the six endpoints at their own prefixes (research R4,
 * plan's component table), and what happens at start: a key in the secret store, the shipped
 * scenarios seeded, the key pushed to any original adapter, the retention sweep scheduled.
 */
object Simulation:

  val Version: String = Option(getClass.getPackage.getImplementationVersion).getOrElse("0.1.0-SNAPSHOT")

  val descriptors: Seq[ComponentDescriptor] = Seq(
    SessionEntity.descriptor, CallbackEntity.descriptor, ScenarioEntity.descriptor, BrandDefaults.descriptor,
    ActiveProfile.descriptor, SimulatorStatusEntity.descriptor, BonusBook.descriptor,
    ScenarioPlay.descriptor, LoadProfile.descriptor, LoadStop.descriptor, KeyRotation.descriptor,
    RunCallbacks.descriptor, ScenarioLibrary.descriptor, SweepAction.descriptor
  )

  /** The endpoints, over the collaborators `install` builds. */
  def server(table: BrandTable, access: Access, at: Option[(String, Int)] = None, holder: java.util.concurrent.atomic.AtomicReference[Simulator]): HttpServer =
    // One factory per endpoint; all six share one Simulator, built from the first clients offered.
    def sim(clients: EndpointClients): Simulator =
      holder.compareAndSet(null, Simulator(clients.componentClient, clients.viewClient, table, Catalogue.load(table.gamesExtra), Keys(clients.secrets)))
      holder.get()
    val factories: Seq[EndpointClients => HttpEndpoint] = Seq(
      c => OperatorApi(sim(c)), c => GamePage(sim(c)), c => Instructions(sim(c)),
      c => KeyDocument(sim(c)), c => Health(sim(c)), c => SimulatorEndpoint(sim(c), access, Version)
    )
    at.fold(HttpServer.of(factories*))((host, port) => HttpServer.at(host, port)(factories*))

  /** Installs the core's collaborators. Called once the service (and so its secret store) has started. */
  def install(service: AnkkaService, table: BrandTable, retention: FiniteDuration = 30.days): Keys =
    val keys = Keys(service.secrets)
    Wiring.install(St8Protocol, Sender(table, keys), keys, table)
    Sweep.retention = retention
    Sweep.views = Some(service.viewClient)
    keys

  /** The work done once at start (research R8, R10). */
  def prepare(service: AnkkaService, table: BrandTable, keys: Keys, timers: Option[TimerRuntime], sim: => Option[Simulator]): Unit =
    keys.ensureCurrent()
    Scenario.shipped.foreach { s =>
      service.componentClient.forKeyValueEntity(EntityId(s.name)).call(ScenarioEntity.seed).invoke(StoredScenario(s.name, s.version, s.json, shipped = true))
    }
    table.brands.flatMap(b => b.registerKeyAt.map(b.slug -> _)).foreach((_, at) => registerKey(at, keys.currentPublicText))
    timers.foreach(_.timerScheduler.createRecurringTimer(SweepAction.TimerName, 1.minute, 1.hour, SweepAction.sweep.deferred))
    sim.foreach(_.ready.set(true))

  /** The stand-in's `registerPublicKey` (`main.go:530-548`): up to 60 attempts 2 s apart until HTTP 200. */
  def registerKey(at: URI, publicKey: String, attempts: Int = 60, pause: FiniteDuration = 2.seconds): Boolean =
    val client = HttpClient.newHttpClient()
    val body   = GoJson.marshal(GoJson.strings("publicKey" -> publicKey))
    (1 to attempts).exists { i =>
      val ok =
        try client.send(HttpRequest.newBuilder(at).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
          HttpResponse.BodyHandlers.discarding()).statusCode() == 200
        catch case NonFatal(_) => false
      if !ok && i < attempts then Thread.sleep(pause.toMillis)
      ok
    }

@main def run(): Unit =
  val table = BrandTable.fromEnvironment().fold(problem => throw IllegalStateException(problem), identity)
  val holder = java.util.concurrent.atomic.AtomicReference[Simulator]()
  val timers = TimerRuntime()
  val builder = Simulation.descriptors.foldLeft(Ankka.service)((b, d) => b.register(d))
  val service = builder
    .withExtension(ProjectionRuntime())
    .withExtension(timers)
    .withExtension(Simulation.server(table, Access(), holder = holder))
    .start(name = "game-provider-simulator")
  val keys = Simulation.install(service, table, table.retentionDays.days)
  Simulation.prepare(service, table, keys, Some(timers), Option(holder.get()))

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
