package io.eitheror.casino.wallet.deposits

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.wallet.WalletCodecs
import io.eitheror.casino.wallet.api.ResponsibleGambling
import io.eitheror.casino.wallet.entity.{Deposit, DepositSumsAt, Outcome, WalletEntity}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/** What `merchant` sent: the wallet and the deposit command, with the brand's terms and the time already resolved. */
final case class DepositStart(player: String, currency: String, deposit: Deposit)

/** The flow's state: what it has decided. `outcome` is present once there is an answer. */
final case class DepositFlow(status: String = "new", request: Option[DepositStart] = None, outcome: Option[Outcome] = None)

/**
 * One deposit (FR-022, research R17): the workflow whose id is the payment id, under the wallet.
 *
 * `start` is the atomic claim: a second start of the same payment is `Conflict`, and the route answers
 * both from this state. The `check-rg` step asks `responsible-gambling`'s `may-deposit` with the
 * wallet's own sums and fails closed: a refusal is this flow's outcome, and the wallet is never
 * called. The `credit` step is the wallet's `deposit`, whose outcome becomes the flow's. A step runs
 * at least once, which is why the wallet answers a repeated recent key with the first outcome, and why
 * `credit` may retry.
 *
 * One refusal is not final: `RG_UNAVAILABLE` says nothing about the player, only that the check did
 * not happen, and `merchant` retries it as transient (spec 004 FR-022). A `start` of a flow refused
 * that way runs the check again, from the beginning (FR-022; clarification 2026-10-21).
 */
final class DepositWorkflow(context: WorkflowContext) extends Workflow[DepositFlow]:

  private val client = context.componentClient

  def emptyState: DepositFlow = DepositFlow()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(5.seconds)
      .stepRecovery(DepositWorkflow.credit, RecoverStrategy.maxRetries(2))
      .build

  def start(s: DepositStart): Effect[Done] =
    if currentState.status == "new" || DepositWorkflow.unchecked(currentState) then
      effects
        .updateState(DepositFlow("checking", Some(s)))
        .transitionTo(DepositWorkflow.checkRg.withInput(s))
        .thenReply(Done)
    else effects.error("deposit already seen", ErrorCode.Conflict)

  def checkRgStep(s: DepositStart): StepEffect =
    val sums   = wallet(s).call(WalletEntity.depositSums).invoke(DepositSumsAt(s.deposit.at))
    val answer = DepositWorkflow.rg.get()(context.services).mayDeposit(s.player, s.currency, s.deposit.amount, sums)
    ResponsibleGambling.refusal(answer) match
      case None =>
        stepEffects.updateState(currentState.copy(status = "crediting")).thenTransitionTo(DepositWorkflow.credit.withInput(s))
      case Some((code, message, details)) =>
        val refused = Outcome(code = Some(code), message = Some(message), details = details, commandType = "deposit",
          amount = Some(s.deposit.amount))
        stepEffects.updateState(currentState.copy(status = "refused", outcome = Some(refused))).thenEnd

  def creditStep(s: DepositStart): StepEffect =
    val outcome = wallet(s).call(WalletEntity.deposit).invoke(s.deposit)
    stepEffects.updateState(currentState.copy(status = "done", outcome = Some(outcome))).thenEnd

  def status: ReadOnlyEffect[DepositFlow] = effects.reply(currentState)

  private def wallet(s: DepositStart) = client.forEventSourcedEntity(WalletEntity.id(s.player, s.currency))

object DepositWorkflow
    extends Workflow.Companion[DepositWorkflow, DepositFlow](
      componentId = ComponentId("deposit-flow"),
      stateSerializer = Serializer.json[DepositFlow]("wallet.deposit-flow")(using WalletCodecs.stored[DepositFlow])
    ):

  /**
   * How a step reaches `responsible-gambling`: over HTTP as this service in a cluster
   * (`ResponsibleGambling.http`), a fake in the integration suites. Set by `WalletService.components`
   * before the descriptor is registered; a workflow is constructed by its companion, so this is where
   * what it needs arrives.
   */
  private[wallet] val rg: AtomicReference[ServiceClients => ResponsibleGambling] =
    AtomicReference(services => ResponsibleGambling.http(services, ""))

  /** The refusal that is not an answer: the check did not happen, so the next `start` runs it again. */
  val Unavailable = "RG_UNAVAILABLE"

  /** A flow that ended refused without `responsible-gambling` ever answering. */
  def unchecked(flow: DepositFlow): Boolean =
    flow.status == "refused" && flow.outcome.exists(_.code.contains(Unavailable))

  given Serializer[DepositStart] = Serializer.json[DepositStart]("wallet.deposit-start")(using WalletCodecs.stored[DepositStart])

  def create(context: WorkflowContext) = new DepositWorkflow(context)

  def id(player: String, currency: String, paymentId: String): EntityId = EntityId(s"$player/$currency/$paymentId")

  val checkRg = step("check-rg")(_.checkRgStep)
  val credit  = step("credit")(_.creditStep)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
