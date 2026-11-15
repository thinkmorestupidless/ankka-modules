package io.eitheror.casino.backoffice

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, TimerRuntime}
import io.eitheror.casino.backoffice.preferences.Preferences
import io.eitheror.casino.backoffice.session.{KeycloakRealmClient, Session, SessionExpiry, SessionRefresh, SessionsBySubject}

/** Every component of the service (data-model.md), registered by the service and by its test kit alike. */
object BackofficeStateService:

  def descriptors: Seq[ComponentDescriptor] =
    Seq(Session.descriptor, SessionRefresh.descriptor, SessionExpiry.descriptor, SessionsBySubject.descriptor, Preferences.descriptor)

  /** Filled by T033: the session and preferences endpoints, admitting only the brand's `backoffice`. */
  def endpoints(settings: Settings): Seq[EndpointClients => HttpEndpoint] = Seq.empty

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(problem => { System.err.println(problem); sys.exit(2) }, identity)
  val log      = org.slf4j.LoggerFactory.getLogger("backoffice-state")
  val timers   = TimerRuntime()
  StateRuntime.realm = KeycloakRealmClient(settings)
  val builder = Ankka.service
    .registerAll(BackofficeStateService.descriptors)
    .withExtension(timers)
    .withErasureHandler(Erasure.handler)
    .withExtension(HttpServer.of(BackofficeStateService.endpoints(settings)*))
  // The installation names its keyring for a service with personal fields; a local run keeps its keys in its own database.
  val keyed =
    if sys.env.get("ANKKA_KEYRING_ADDRESS").exists(_.trim.nonEmpty) then builder
    else
      log.warn("ANKKA_KEYRING_ADDRESS is not set: personal fields are sealed under keys in this service's database (a local run)")
      builder.withLocalKeyring()
  val service = keyed.start(name = "backoffice-state")
  StateRuntime.views = () => service.viewClient
  StateRuntime.timers = () => timers.timerScheduler
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
