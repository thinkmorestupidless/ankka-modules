package io.eitheror.casino.markets

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.predictions.markets.components.{MarketPorts, MarketsComponents}
import io.eitheror.casino.markets.api.{OperatorEndpoint, PlayerEndpoint}
import io.eitheror.casino.markets.drive.ExchangeDriverCompanion
import io.eitheror.casino.markets.facts.MarketFactsPublisher
import io.eitheror.casino.markets.terms.MarketTerms

/**
 * Every component of the service (plan.md's table): the markets library's, built from this service's
 * resolution port and the timer runtime's scheduler, beside the terms, the facts and the driver.
 * Registered by the service and by its test kit alike.
 */
final class MarketsService(timers: TimerRuntime):
  val components: MarketsComponents = MarketsComponents(MarketPorts(Resolution, () => timers.timerScheduler))

  val descriptors: Seq[ComponentDescriptor] =
    components.descriptors ++ Seq(MarketTerms.descriptor, MarketFactsPublisher.descriptor, ExchangeDriverCompanion(components).descriptor)

  /** One endpoint per audience (contracts/routes.md), none sharing a prefix. */
  def endpoints(authenticate: () => Acl): Seq[EndpointClients => HttpEndpoint] =
    Seq[EndpointClients => HttpEndpoint](
      clients => OperatorEndpoint(components, clients, authenticate),
      clients => PlayerEndpoint(components, clients, authenticate)
    )

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(e => throw IllegalStateException(e), identity)
  MarketsRuntime.wire(settings)
  val timers = TimerRuntime()
  // A local run with no broker reads and publishes in memory, so the service starts without Kafka; an installation sets one.
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      org.slf4j.LoggerFactory.getLogger("markets")
        .warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are published in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  val markets = MarketsService(timers)
  val service = Ankka.service
    .registerAll(markets.descriptors)
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(markets.endpoints(() => Oidc.authenticate())*))
    .start(name = "markets")
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
