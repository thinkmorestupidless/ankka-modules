package io.eitheror.casino.wallet

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime}
import io.eitheror.casino.contract.access.Routes
import io.eitheror.casino.wallet.api.*
import com.thinkmorestupidless.ankka.sdk.ServiceClients
import io.eitheror.casino.wallet.accounts.{AccountEntity, AccountMoveWorkflow}
import io.eitheror.casino.wallet.config.BrandConfigEntity
import io.eitheror.casino.wallet.settlement.SettlementWorkflow
import io.eitheror.casino.wallet.deposits.DepositWorkflow
import io.eitheror.casino.wallet.entity.WalletEntity
import io.eitheror.casino.wallet.payments.PaymentEntity
import io.eitheror.casino.wallet.publish.*
import io.eitheror.casino.wallet.views.*

/**
 * The wallet service: everything it hosts (plan.md's component table). Registration is explicit, so
 * this is the inventory; a component left out fails here at startup.
 */
object WalletService:

  /**
   * The entities, the deposit workflow, views and publishers: what the integration test kit starts
   * too. `rg` is how the deposit workflow's step reaches `responsible-gambling` (research R6, R17).
   */
  def components(rg: ServiceClients => ResponsibleGambling): Seq[ComponentDescriptor] =
    DepositWorkflow.rg.set(rg)
    Seq(
    WalletEntity.descriptor,
    DepositWorkflow.descriptor,
    BrandConfigEntity.descriptor,
    PaymentEntity.descriptor,
    TransactionsView.descriptor,
    SumsView.descriptor,
    LifetimeTotalsView.descriptor,
    MoneyTransactionsPublisher.descriptor,
    PaymentCapturedPublisher.descriptor,
    PaymentsPublisher.descriptor,
    AuditPublisher.descriptor,
    // spec 022
    SettlementWorkflow.descriptor,
    AccountEntity.descriptor,
    AccountMoveWorkflow.descriptor,
    AccountAuditPublisher.descriptor,
    AccountTransactionsPublisher.descriptor
  )

  /** The four audiences' endpoints, for a brand, with `responsible-gambling` as given. */
  def endpoints(brand: String, authenticate: () => Acl = () => Oidc.authenticate(), project: String = ""): Seq[EndpointClients => HttpEndpoint] = Seq(
    clients => ServiceEndpoint(clients.componentClient, clients.viewClient, brand, project = project),
    clients => OperatorEndpoint(clients.componentClient, clients.viewClient, brand, authenticate()),
    clients => PlayerEndpoint(clients.componentClient, clients.viewClient, brand, authenticate())
  )

@main def run(): Unit =
  val brand = Routes.brandFromEnvironment
  val service = Ankka.service
    .registerAll(WalletService.components(services => ResponsibleGambling.http(services, brand)))
    .withExtension(ProjectionRuntime.fromEnv())
    .withExtension(HttpServer.of(WalletService.endpoints(brand)*))
    .start()

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await.result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
