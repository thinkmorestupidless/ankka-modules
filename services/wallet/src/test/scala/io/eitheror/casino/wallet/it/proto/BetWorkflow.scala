package io.eitheror.casino.wallet.it.proto

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.wallet.api.{ResponsibleGambling, RgAnswer}
import io.eitheror.casino.wallet.entity.{Outcome, PlaceBet, WalletEntity}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * A prototype, test tree only: one workflow per provider transaction, as the bet path of spec 003
 * would run it. Its id is `<player>/<currency>/<key>`; `start` is the atomic claim (a second start
 * is `Conflict`), the `check-rg` step asks `responsible-gambling`, the `debit` step places the bet
 * with the wallet under the transaction's key, and the outcome is the workflow's state, kept for
 * the life of the ledger. Measured by `BetWorkflowItSuite` against SC-003 before 002 and 003 are
 * rewritten around it.
 */
final case class BetStart(player: String, currency: String, command: PlaceBet)

final case class BetFlow(status: String = "new", request: Option[BetStart] = None, outcome: Option[Outcome] = None)

final class BetWorkflow(context: WorkflowContext) extends Workflow[BetFlow]:

  private val client = context.componentClient

  def emptyState: BetFlow = BetFlow()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(5.seconds)
      // The wallet answers a repeated key with the first outcome, so the debit is safe to run again.
      .stepRecovery(BetWorkflow.debit, RecoverStrategy.maxRetries(2))
      .build

  def start(s: BetStart): Effect[Done] =
    if currentState.status != "new" then effects.error("transaction already seen", ErrorCode.Conflict)
    else
      effects
        .updateState(BetFlow("checking", Some(s)))
        .transitionTo(BetWorkflow.checkRg.withInput(s))
        .thenReply(Done)

  def checkRgStep(s: BetStart): StepEffect =
    BetWorkflow.rg.get.mayBet(s.player, s.currency, s.command.amount) match
      case RgAnswer.Allowed =>
        stepEffects.updateState(currentState.copy(status = "debiting")).thenTransitionTo(BetWorkflow.debit.withInput(s))
      case answer =>
        val (code, message, details) = ResponsibleGambling.refusal(answer)
          .getOrElse(("RG_UNAVAILABLE", "Responsible gambling check unavailable", Map.empty[String, String]))
        val refused = Outcome(code = Some(code), message = Some(message), details = details, commandType = "place-bet", amount = Some(s.command.amount))
        stepEffects.updateState(currentState.copy(status = "refused", outcome = Some(refused))).thenEnd

  def debitStep(s: BetStart): StepEffect =
    val outcome = client.forEventSourcedEntity(WalletEntity.id(s.player, s.currency)).call(WalletEntity.placeBet).invoke(s.command)
    stepEffects.updateState(currentState.copy(status = "done", outcome = Some(outcome))).thenEnd

  def status: ReadOnlyEffect[BetFlow] = effects.reply(currentState)

object BetWorkflow
    extends Workflow.Companion[BetWorkflow, BetFlow](
      componentId = ComponentId("bet-flow"),
      stateSerializer = Serializer.json[BetFlow]("proto.bet-flow")(using io.eitheror.casino.wallet.WalletCodecs.stored[BetFlow])
    ):

  /** The prototype's `responsible-gambling`: a step reads it as the service would read `context.services`. */
  val rg: AtomicReference[ResponsibleGambling] = AtomicReference()

  given Serializer[BetStart] = Serializer.json[BetStart]("proto.bet-start")(using io.eitheror.casino.wallet.WalletCodecs.stored[BetStart])

  def create(context: WorkflowContext) = new BetWorkflow(context)

  def id(player: String, currency: String, key: String): EntityId = EntityId(s"$player/$currency/$key")

  val checkRg = step("check-rg")(_.checkRgStep)
  val debit   = step("debit")(_.debitStep)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
