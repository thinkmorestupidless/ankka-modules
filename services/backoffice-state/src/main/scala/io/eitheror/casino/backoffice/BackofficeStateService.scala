package io.eitheror.casino.backoffice

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, TimerRuntime}

/** Every component of the service (data-model.md), registered by the service and by its test kit alike. */
object BackofficeStateService:

  /** Filled by T031 and T032: `Session`, `SessionsBySubject`, `Preferences`. */
  def descriptors: Seq[ComponentDescriptor] = Seq.empty

  /** Filled by T033: the session and preferences endpoints, admitting only the brand's `backoffice`. */
  def endpoints(settings: Settings): Seq[EndpointClients => HttpEndpoint] = Seq.empty

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(problem => { System.err.println(problem); sys.exit(2) }, identity)
  val timers   = TimerRuntime()
  val service = Ankka.service
    .registerAll(BackofficeStateService.descriptors)
    .withExtension(timers)
    .withExtension(HttpServer.of(BackofficeStateService.endpoints(settings)*))
    .start()
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
