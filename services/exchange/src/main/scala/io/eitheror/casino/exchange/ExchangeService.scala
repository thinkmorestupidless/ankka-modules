package io.eitheror.casino.exchange

import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, EntityId}
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, InMemoryBroker, ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.predictions.exchange.MarketId
import com.thinkmorestupidless.predictions.exchange.components.{ExchangeComponents, ExchangePorts}
import io.eitheror.casino.exchange.api.{OperatorEndpoint, PlayerEndpoint, ServiceEndpoint}
import io.eitheror.casino.exchange.ports.{Gates, Ledger, Neighbours, WalletCall, WalletClient}
import io.eitheror.casino.exchange.retry.{RetryAudit, SettlementRetry, StartRetry}
import com.thinkmorestupidless.predictions.exchange.components.LedgerAnswer
import io.eitheror.casino.exchange.erasure.ErasedEntity
import io.eitheror.casino.exchange.facts.{ObservedEntity, OrderFactsPublisher, PriceFactsPublisher, PriceObservations, TradeFactsPublisher}
import io.eitheror.casino.exchange.reads.{OrderRows, TradeRows}
import io.eitheror.casino.exchange.reconcile.*
import io.eitheror.casino.exchange.terms.MarketTerms

import java.time.Duration

/**
 * Every component of the service (plan.md's table): the exchange library's, built from this service's
 * gate and ledger ports, beside the books' terms. Registered by the service and by its test kit alike.
 */
final class ExchangeService(timers: TimerRuntime, settings: Settings):
  /** A book's currency, from its terms; a book that was never opened has none, and a call on it throws. */
  private def currency(market: MarketId): String =
    val t = ExchangeRuntime.components.forKeyValueEntity(EntityId(market.value)).call(MarketTerms.get).invoke()
    if t.known then t.currency else throw IllegalStateException(s"the book of ${market.value} has no terms")

  private def brand = ExchangeRuntime.settings.brand

  val gates  = Gates(() => Neighbours(ExchangeRuntime.services, brand), () => ExchangeRuntime.settings.gates, currency)
  val ledger = Ledger(() => WalletClient(ExchangeRuntime.services, brand), currency, retry)

  /** Starts the retry of a refused call before the refusal is answered (R5); a second start is the same retry. */
  private def retry(call: WalletCall, answer: LedgerAnswer): Unit =
    val refusal = answer match
      case LedgerAnswer.Refused(code) => code
      case LedgerAnswer.Insufficient  => "INSUFFICIENT_FUNDS"
      case LedgerAnswer.Done          => "done"
    ExchangeRuntime.components.forWorkflow(SettlementRetry.id(call)).call(SettlementRetry.start).invoke(StartRetry(call, refusal)): Unit

  val components: ExchangeComponents = ExchangeComponents(ExchangePorts(ledger, gates, ExchangeService.LedgerPatience))

  val descriptors: Seq[ComponentDescriptor] = components.descriptors ++ Seq(MarketTerms.descriptor, SettlementRetry.descriptor, RetryAudit.descriptor, TallyEntity.descriptor,
      MarketOfEntity.descriptor, ExchangeTallyCompanion(() => timers.timerScheduler).descriptor,
      PlacementTallyCompanion(components.placement).descriptor, WalletTally.descriptor, Reconcile.descriptor, ReconcileAudit.descriptor,
      OrderRows.descriptor, TradeRows.descriptor, ErasedEntity.descriptor, OrderFactsPublisher.descriptor, TradeFactsPublisher.descriptor,
      PriceFactsPublisher.descriptor) ++
      // The observations only when the brand's values name a reasoning service (FR-007).
      settings.reasoningService.toSeq.flatMap(_ => Seq(ObservedEntity.descriptor, PriceObservations.descriptor))

  /** One endpoint per audience (contracts/routes.md), none sharing a prefix. */
  def endpoints(authenticate: () => Acl): Seq[EndpointClients => HttpEndpoint] =
    Seq[EndpointClients => HttpEndpoint](
      clients => ServiceEndpoint(components, clients),
      clients => PlayerEndpoint(components, clients, authenticate),
      clients => OperatorEndpoint(clients, authenticate)
    )

object ExchangeService:
  /** How long a reserve the wallet has not answered is retried before the order is refused (R4). */
  val LedgerPatience: Duration = Duration.ofSeconds(5)

@main def run(): Unit =
  val settings = Settings.fromEnvironment().fold(e => throw IllegalStateException(e), identity)
  ExchangeRuntime.wire(settings)
  // A local run with no broker reads and publishes in memory, so the service starts without Kafka; an installation sets one.
  val broker =
    if sys.env.get("ANKKA_KAFKA_BOOTSTRAP_SERVERS").exists(_.nonEmpty) then ProjectionRuntime.fromEnv()
    else
      org.slf4j.LoggerFactory.getLogger("exchange")
        .warn("ANKKA_KAFKA_BOOTSTRAP_SERVERS is not set: facts are published in memory (a local run)")
      val memory = InMemoryBroker()
      ProjectionRuntime.withBroker(memory, memory)
  val timers   = TimerRuntime()
  val exchange = ExchangeService(timers, settings)
  val service = Ankka.service
    .registerAll(exchange.descriptors)
    .withExtension(broker)
    .withExtension(timers)
    .withExtension(HttpServer.of(exchange.endpoints(() => Oidc.authenticate())*))
    .start(name = "exchange")
  ExchangeRuntime.started(() => (service.services, service.componentClient))
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
