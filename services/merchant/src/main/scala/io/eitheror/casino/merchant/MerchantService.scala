package io.eitheror.casino.merchant

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime, TimerRuntime}
import io.eitheror.casino.merchant.api.*
import io.eitheror.casino.merchant.cashier.Session
import io.eitheror.casino.merchant.deposits.*
import io.eitheror.casino.merchant.hierarchy.*
import io.eitheror.casino.merchant.keys.*
import io.eitheror.casino.merchant.payments.*
import io.eitheror.casino.merchant.routing.RoutingTree
import io.eitheror.casino.merchant.terminals.*

import java.time.Instant

/** Every component of the service (data-model.md), registered by the service and by its test kit alike. */
object MerchantService:

  val descriptors: Seq[ComponentDescriptor] = Seq(
    Holding.descriptor,
    Company.descriptor,
    SubBrand.descriptor,
    Terminal.descriptor,
    RoutingTree.descriptor,
    Session.descriptor,
    Payment.descriptor,
    PaymentCustomer.descriptor,
    MerchantKeyIndex.descriptor,
    DepositWorkflow.descriptor,
    HoldingsView.descriptor,
    CompaniesView.descriptor,
    SubBrandsView.descriptor,
    TerminalsBySubBrand.descriptor,
    PaymentsView.descriptor,
    ActiveKeys.descriptor,
    KeyIndexer.descriptor,
    MerchantFacts.descriptor,
    OutcomeReporter.descriptor,
    io.eitheror.casino.merchant.operators.Operator.descriptor,
    io.eitheror.casino.merchant.operators.InvitationToken.descriptor,
    io.eitheror.casino.merchant.operators.OperatorsView.descriptor,
    io.eitheror.casino.merchant.operators.OperatorSync.descriptor,
    Seed.descriptor,
    io.eitheror.casino.merchant.cashier.SessionsByCustomer.descriptor
  ) ++ io.eitheror.casino.merchant.audit.AuditConsumers.descriptors

  /** One endpoint per prefix (research R14); each route's audience is its ACL. */
  def endpoints(settings: Settings, authenticate: () => Acl, now: () => Instant = () => Instant.now()): Seq[EndpointClients => HttpEndpoint] =
    def ctx(c: EndpointClients) = Ctx(settings, authenticate, now, c)
    Seq[EndpointClients => HttpEndpoint](
      c => HoldingsEndpoint(ctx(c)),
      c => CompaniesEndpoint(ctx(c)),
      c => SubBrandsEndpoint(ctx(c)),
      c => MerchantsEndpoint(ctx(c)),
      c => CatalogEndpoint(ctx(c)),
      c => PaymentsEndpoint(ctx(c)),
      c => CashierEndpoint(ctx(c)),
      c => InternalEndpoint(ctx(c)),
      c => StatusEndpoint(ctx(c)),
      c => OperatorsEndpoint(ctx(c)),
      c => ErasureEndpoint(ctx(c))
    )

  /** What the workflow's steps and the consumers read that is the service's: the settings and the clock. */
  def wire(settings: Settings, now: () => Instant = () => Instant.now()): Unit =
    DepositRuntime.settings = settings
    DepositRuntime.now = now

  /** What is scheduled once the service has started: the realm's sync (FR-028). */
  def started(timers: TimerRuntime, settings: Settings): Unit =
    Seed.schedule(timers.timerScheduler, settings.brands)
    io.eitheror.casino.merchant.operators.OperatorsRuntime.scheduler = Some(() => timers.timerScheduler)
    io.eitheror.casino.merchant.operators.OperatorSync.schedule(timers.timerScheduler,
      scala.concurrent.duration.FiniteDuration(settings.operatorSyncInterval.toMillis, "millis"))

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(e => throw IllegalStateException(e), identity)
  MerchantService.wire(settings)
  val timers = TimerRuntime()
  // fromEnv: on an installation the broker is a TLS listener known by the service's own certificate (spec 003 T082).
  // A local run with no broker publishes to memory, so the service starts without Kafka; an installation sets one.
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      org.slf4j.LoggerFactory.getLogger("merchant").warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are published to memory (a local run)")
      val memory = com.thinkmorestupidless.ankka.runtime.InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  val service = Ankka.service
    .registerAll(MerchantService.descriptors)
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(MerchantService.endpoints(settings, () => Oidc.authenticate())*))
    .start()
  MerchantService.started(timers, settings)
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
