package io.eitheror.casino.wallet

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.auth.oidc.Oidc
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime}
import io.eitheror.casino.contract.access.{RoleBags, Routes}
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
    io.eitheror.casino.wallet.erasure.ErasedPlayerEntity.descriptor,
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
  ) ++ io.eitheror.casino.wallet.freshness.ViewFollower.descriptors

  /**
   * The four audiences' endpoints, for a brand, with `responsible-gambling` as given. `bags` is the
   * brand's roles the operator routes decide from: by default read from the brand's `players`.
   */
  def endpoints(brand: String, authenticate: () => Acl = () => Oidc.authenticate(), project: String = "",
      bags: Option[EndpointClients => RoleBags] = None)
      : Seq[EndpointClients => HttpEndpoint] =
    def roles(clients: EndpointClients) = bags.fold(BrandRoles.of(clients.services, brand))(_(clients))
    Seq(
    // spec 030 W4 (F): the views' freshness for an operator who may read the wallet, or for the project's services
    clients => ServiceEndpoint(clients.componentClient, clients.viewClient, brand, project = project,
      statusAcl = Acl.anyOf(Routes.operatorMay(brand, "wallet.read", roles(clients), authenticate()), Routes.service())),
    clients => OperatorEndpoint(clients.componentClient, clients.viewClient, brand, authenticate(), bags = roles(clients)),
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
