package io.eitheror.casino.wallet.settlement

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.wallet.WalletCodecs
import io.eitheror.casino.wallet.entity.{MoneyCommand, Outcome, Refusals, SettleCredit, SettleDebit, WalletEntity}

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/** One trade as the exchange sends it (FR-004): the buyer's order pays the seller `amount`; `unused` is released. */
final case class SettlementStart(tradeId: String, currency: String, buyer: String, orderId: String, seller: String, amount: Long,
    unused: Long, at: Instant)

/** The flow's state: what it has decided. `debit` is the buyer's side once written; `outcome` the answer. */
final case class SettlementFlow(status: String = "new", request: Option[SettlementStart] = None, digest: String = "",
    debit: Option[Outcome] = None, outcome: Option[Outcome] = None)

/**
 * A trade settled between two players' wallets in one keyed command (spec 022 FR-004, research R3):
 * the workflow whose id is `<currency>/<tradeId>`. `start` is the atomic claim; a second start is
 * `Conflict` and the route answers both from this state (R4).
 *
 * `check` reads that the seller's wallet exists before anything is written, so no later step can be
 * refused for it; `debit` is the buyer's `settle-debit` and `credit` the seller's `settle-credit`,
 * both under the trade id, so a step run again is answered by the wallet, not repeated. A debit
 * refused ends the flow with that refusal and nothing written; once the debit is written the credit
 * is retried, and on a restart the flow resumes at the step it reached (FR-004's restart scenario).
 */
final class SettlementWorkflow(context: WorkflowContext) extends Workflow[SettlementFlow]:

  private val client = context.componentClient

  def emptyState: SettlementFlow = SettlementFlow()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(5.seconds)
      .stepRecovery(SettlementWorkflow.debit, RecoverStrategy.maxRetries(3))
      .stepRecovery(SettlementWorkflow.credit, RecoverStrategy.maxRetries(3))
      .build

  def start(s: SettlementStart): Effect[Done] =
    if currentState.status != "new" then effects.error("settlement already started", ErrorCode.Conflict)
    else
      effects
        .updateState(SettlementFlow("checking", Some(s), SettlementWorkflow.digest(s)))
        .transitionTo(SettlementWorkflow.check.ref)
        .thenReply(Done)

  private def request: SettlementStart = currentState.request.getOrElse(throw IllegalStateException("no settlement request"))

  private def wallet(player: String) = client.forEventSourcedEntity(WalletEntity.id(player, request.currency))

  def checkStep: StepEffect =
    SettlementWorkflow.faults.get()("check")
    val r = request
    if wallet(r.seller).call(WalletEntity.exists).invoke() then
      stepEffects.updateState(currentState.copy(status = "debiting")).thenTransitionTo(SettlementWorkflow.debit.ref)
    else
      val refused = Outcome(code = Some(Refusals.WalletNotFound), message = Some(Refusals.walletNotFound),
        details = Map("player" -> r.seller), commandType = "settle", amount = Some(r.amount))
      stepEffects.updateState(currentState.copy(status = "refused", outcome = Some(refused))).thenEnd

  def debitStep: StepEffect =
    SettlementWorkflow.faults.get()("debit")
    val r = request
    val o = wallet(r.buyer).call(WalletEntity.settleDebit).invoke(SettleDebit(r.tradeId, r.orderId, r.amount, r.unused, r.seller, r.at))
    if !o.accepted then stepEffects.updateState(currentState.copy(status = "refused", outcome = Some(o.copy(commandType = "settle")))).thenEnd
    else stepEffects.updateState(currentState.copy(status = "crediting", debit = Some(o))).thenTransitionTo(SettlementWorkflow.credit.ref)

  def creditStep: StepEffect =
    SettlementWorkflow.faults.get()("credit")
    val r      = request
    val credit = wallet(r.seller).call(WalletEntity.settleCredit).invoke(SettleCredit(r.tradeId, r.amount, r.buyer, r.at))
    val debit  = currentState.debit.getOrElse(throw IllegalStateException("credit before debit"))
    val done = debit.copy(commandType = "settle", amount = Some(r.amount), counterpartyTransactionId = credit.transactionId,
      counterpartyRealBalance = Some(credit.realBalance))
    stepEffects.updateState(currentState.copy(status = "done", outcome = Some(done))).thenEnd

  def status: ReadOnlyEffect[SettlementFlow] = effects.reply(currentState)

object SettlementWorkflow
    extends Workflow.Companion[SettlementWorkflow, SettlementFlow](
      componentId = ComponentId("settlement-flow"),
      stateSerializer = Serializer.json[SettlementFlow]("wallet.settlement-flow")(using WalletCodecs.stored[SettlementFlow])
    ):

  /**
   * A test's way to make a step fail or stop where it stands (the restart scenario): run at the top
   * of each step with the step's name. A no-op in production; the integration suites set it, as
   * `DepositWorkflow.rg` is set.
   */
  private[wallet] val faults: AtomicReference[String => Unit] = AtomicReference(_ => ())

  given Serializer[SettlementStart] = Serializer.json[SettlementStart]("wallet.settlement-start")(using WalletCodecs.stored[SettlementStart])

  def create(context: WorkflowContext) = new SettlementWorkflow(context)

  def id(currency: String, tradeId: String): EntityId = EntityId(s"$currency/$tradeId")

  /** What `KEY_REUSED` compares (R4): every field of the trade but its time. */
  def digest(s: SettlementStart): String = MoneyCommand.digestOf("settle", s)

  val check  = step("check")(_.checkStep)
  val debit  = step("debit")(_.debitStep)
  val credit = step("credit")(_.creditStep)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
