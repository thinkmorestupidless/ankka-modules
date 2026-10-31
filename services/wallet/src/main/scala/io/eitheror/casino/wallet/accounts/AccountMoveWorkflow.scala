package io.eitheror.casino.wallet.accounts

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.wallet.WalletCodecs
import io.eitheror.casino.wallet.entity.{ChargeReservationToAccount, CreditFromAccount, DebitToAccount, MoneyCommand, Outcome, Refusals, WalletEntity}

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*

/**
 * One move between a player's wallet and a non-player account (spec 022 FR-005, FR-006, FR-008).
 * `direction` is `to-account` (a split; a clearing move from the wallet) or `from-account` (a merge,
 * a resolution payment, a clearing move to the wallet). `caller` is the moving service's principal,
 * which owns the account (R7). A split with `orderId` is charged from that order's reservation,
 * releasing `unused` from it (FR-008a).
 */
final case class AccountMoveStart(accountId: String, direction: String, purpose: String, player: String, amount: Long, key: String,
    reference: String, caller: String, at: Instant, orderId: Option[String] = None, unused: Long = 0L):
  def kind: String     = AccountState.kindOf(accountId)
  def name: String     = AccountState.nameOf(accountId)
  def currency: String = accountId.drop(accountId.lastIndexOf('/') + 1)

  /** The wallet's key for its side: the account and the caller's key, so it never meets an order's or a bet's. */
  def walletKey: String = s"$kind/$name/$key"

/** The flow's state. `first` is the debit side's outcome once written; `outcome` the answer. */
final case class AccountMoveFlow(status: String = "new", request: Option[AccountMoveStart] = None, digest: String = "",
    first: Option[Outcome] = None, outcome: Option[Outcome] = None)

/**
 * A move as a workflow whose id is `<kind>/<name>/<currency>/<key>` (data-model.md). Every step
 * before the first write can refuse and end the flow with nothing written; the debit side is
 * written, then the credit side, which nothing refuses: the claim (to an account) or the wallet
 * check (from one) came first. Each step's call is keyed, so a re-run is answered, not repeated.
 */
final class AccountMoveWorkflow(context: WorkflowContext) extends Workflow[AccountMoveFlow]:

  private val client = context.componentClient

  def emptyState: AccountMoveFlow = AccountMoveFlow()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(5.seconds)
      .stepRecovery(AccountMoveWorkflow.debitWallet, RecoverStrategy.maxRetries(3))
      .stepRecovery(AccountMoveWorkflow.creditAccount, RecoverStrategy.maxRetries(3))
      .stepRecovery(AccountMoveWorkflow.debitAccount, RecoverStrategy.maxRetries(3))
      .stepRecovery(AccountMoveWorkflow.creditWallet, RecoverStrategy.maxRetries(3))
      .build

  def start(s: AccountMoveStart): Effect[Done] =
    if currentState.status != "new" then effects.error("move already started", ErrorCode.Conflict)
    else if s.direction == AccountMoveWorkflow.ToAccount then
      effects.updateState(AccountMoveFlow("claiming", Some(s), AccountMoveWorkflow.digest(s)))
        .transitionTo(AccountMoveWorkflow.claim.ref).thenReply(Done)
    else
      effects.updateState(AccountMoveFlow("checking", Some(s), AccountMoveWorkflow.digest(s)))
        .transitionTo(AccountMoveWorkflow.check.ref).thenReply(Done)

  private def request: AccountMoveStart = currentState.request.getOrElse(throw IllegalStateException("no move request"))
  private def wallet(r: AccountMoveStart)  = client.forEventSourcedEntity(WalletEntity.id(r.player, r.currency))
  private def account(r: AccountMoveStart) = client.forEventSourcedEntity(EntityId(r.accountId))

  private def refused(o: Outcome): StepEffect =
    stepEffects.updateState(currentState.copy(status = "refused", outcome = Some(o.copy(commandType = request.purpose)))).thenEnd

  // ── to an account: claim, debit the wallet, credit the account ────────────

  def claimStep: StepEffect =
    AccountMoveWorkflow.faults.get()("claim")
    val r = request
    val o = account(r).call(AccountEntity.claim).invoke(Claim(r.caller, r.at))
    if !o.accepted then refused(o.copy(amount = Some(r.amount)))
    else stepEffects.updateState(currentState.copy(status = "debiting")).thenTransitionTo(AccountMoveWorkflow.debitWallet.ref)

  def debitWalletStep: StepEffect =
    AccountMoveWorkflow.faults.get()("debit-wallet")
    val r = request
    val o = r.orderId match
      case Some(order) =>
        wallet(r).call(WalletEntity.chargeReservationToAccount)
          .invoke(ChargeReservationToAccount(r.walletKey, order, r.kind, r.name, r.purpose, r.reference, r.amount, r.unused, r.at))
      case None =>
        wallet(r).call(WalletEntity.debitToAccount).invoke(DebitToAccount(r.walletKey, r.kind, r.name, r.purpose, r.reference, r.amount, r.at))
    if !o.accepted then refused(o)
    else stepEffects.updateState(currentState.copy(status = "crediting", first = Some(o))).thenTransitionTo(AccountMoveWorkflow.creditAccount.ref)

  def creditAccountStep: StepEffect =
    AccountMoveWorkflow.faults.get()("credit-account")
    val r     = request
    val moved = account(r).call(AccountEntity.credit).invoke(Credit(r.key, r.caller, r.amount, r.purpose, r.player, r.reference, r.at))
    val first = currentState.first.getOrElse(throw IllegalStateException("credit before debit"))
    val done  = first.copy(commandType = r.purpose, accountBalance = moved.accountBalance, counterpartyTransactionId = moved.transactionId)
    stepEffects.updateState(currentState.copy(status = "done", outcome = Some(done))).thenEnd

  // ── from an account: check the wallet, debit the account, credit the wallet ─

  def checkStep: StepEffect =
    AccountMoveWorkflow.faults.get()("check")
    val r = request
    if wallet(r).call(WalletEntity.exists).invoke() then
      stepEffects.updateState(currentState.copy(status = "debiting")).thenTransitionTo(AccountMoveWorkflow.debitAccount.ref)
    else
      refused(Outcome(code = Some(Refusals.WalletNotFound), message = Some(Refusals.walletNotFound), details = Map("player" -> r.player),
        amount = Some(r.amount)))

  def debitAccountStep: StepEffect =
    AccountMoveWorkflow.faults.get()("debit-account")
    val r = request
    val o = account(r).call(AccountEntity.debit).invoke(Debit(r.key, r.caller, r.amount, r.purpose, r.player, r.reference, r.at))
    if !o.accepted then refused(o)
    else stepEffects.updateState(currentState.copy(status = "crediting", first = Some(o))).thenTransitionTo(AccountMoveWorkflow.creditWallet.ref)

  def creditWalletStep: StepEffect =
    AccountMoveWorkflow.faults.get()("credit-wallet")
    val r     = request
    val o     = wallet(r).call(WalletEntity.creditFromAccount).invoke(CreditFromAccount(r.walletKey, r.kind, r.name, r.purpose, r.reference, r.amount, r.at))
    val first = currentState.first.getOrElse(throw IllegalStateException("credit before debit"))
    val done  = o.copy(commandType = r.purpose, accountBalance = first.accountBalance, counterpartyTransactionId = first.transactionId)
    stepEffects.updateState(currentState.copy(status = "done", outcome = Some(done))).thenEnd

  def status: ReadOnlyEffect[AccountMoveFlow] = effects.reply(currentState)

object AccountMoveWorkflow
    extends Workflow.Companion[AccountMoveWorkflow, AccountMoveFlow](
      componentId = ComponentId("account-move-flow"),
      stateSerializer = Serializer.json[AccountMoveFlow]("wallet.account-move-flow")(using WalletCodecs.stored[AccountMoveFlow])
    ):

  val ToAccount   = "to-account"
  val FromAccount = "from-account"

  /** The settlement flow's test hook, for the move flow's steps. A no-op in production. */
  private[wallet] val faults: AtomicReference[String => Unit] = AtomicReference(_ => ())

  given Serializer[AccountMoveStart] = Serializer.json[AccountMoveStart]("wallet.account-move-start")(using WalletCodecs.stored[AccountMoveStart])

  def create(context: WorkflowContext) = new AccountMoveWorkflow(context)

  def id(accountId: String, key: String): EntityId = EntityId(s"$accountId/$key")

  /**
   * What `KEY_REUSED` compares: every field of the move but its time. The order and the unused part
   * are left out while unset, so a move started before FR-008a keeps its digest.
   */
  def digest(s: AccountMoveStart): String = MoneyCommand.digestOf(s.purpose, s, Set("orderId" -> "None", "unused" -> "0"))

  val claim         = step("claim")(_.claimStep)
  val debitWallet   = step("debit-wallet")(_.debitWalletStep)
  val creditAccount = step("credit-account")(_.creditAccountStep)
  val check         = step("check")(_.checkStep)
  val debitAccount  = step("debit-account")(_.debitAccountStep)
  val creditWallet  = step("credit-wallet")(_.creditWalletStep)

  val start  = command("start")(_.start)
  val status = query("status")(_.status)
