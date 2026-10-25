package io.eitheror.casino.ingest

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, AnkkaService, InMemoryBroker, ProjectionRuntime}
import io.eitheror.casino.ingest.api.*
import io.eitheror.casino.ingest.audit.RequestAudit
import io.eitheror.casino.ingest.keys.{Key, KeyFacts, KeyIndex, KeyRefresh}
import io.eitheror.casino.ingest.merchant.MerchantClient
import io.eitheror.casino.ingest.rate.RateWindow
import io.eitheror.casino.ingest.requests.{Ownership, Request, RequestRecord, RequestsView}

/** Every component of the service (data-model.md), registered by the service and by its test kit alike. */
object IngestService:

  val descriptors: Seq[ComponentDescriptor] = Seq(
    Key.descriptor,
    KeyIndex.descriptor,
    RateWindow.descriptor,
    Request.descriptor,
    Ownership.descriptor,
    RequestRecord.descriptor,
    RequestsView.descriptor,
    KeyFacts.descriptor,
    RequestAudit.descriptor
  )

  /** One endpoint per prefix (contracts/routes.md), none sharing one. */
  def endpoints(authenticate: () => Acl): Seq[EndpointClients => HttpEndpoint] =
    def ctx(c: EndpointClients) = Ctx(authenticate, c)
    Seq[EndpointClients => HttpEndpoint](
      c => CashierEndpoint(ctx(c)),
      c => PaymentsEndpoint(ctx(c)),
      c => IngestEndpoint(ctx(c))
    )

  /** Once the service has started: the key index refreshed from `merchant` (research R3). */
  def started(service: AnkkaService): Int =
    KeyRefresh.run(MerchantClient.http(service.services, IngestRuntime.settings.merchantService), service.componentClient, IngestRuntime.now())

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(e => throw IllegalStateException(e), identity)
  IngestRuntime.wire(settings)
  // fromEnv: on an installation the broker is a TLS listener known by the service's own certificate. A local run
  // with no broker reads and publishes in memory, so the service starts without Kafka; an installation sets one.
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      org.slf4j.LoggerFactory.getLogger("ingest").warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are read and published in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  val service = Ankka.service
    .registerAll(IngestService.descriptors)
    .withExtension(broker)
    .withExtension(HttpServer.of(IngestService.endpoints(() => Oidc.authenticate())*))
    .start(name = "ingest") // the name the local console and `ankka mcp` list it under
  IngestService.started(service): Unit
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
