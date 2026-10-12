package io.eitheror.casino.gameprovider.rounds

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.gameprovider.Metrics
import io.eitheror.casino.gameprovider.clients.*
import io.eitheror.casino.gameprovider.provider.st8.{Kind, Status}

import java.time.Instant
import scala.concurrent.duration.*

/**
 * One provider transaction, as the endpoint resolved it before starting its workflow: who, which wallet,
 * how much, which round, and the body it came with (kept for an operator's replay and for telling a
 * conflicting repeat apart). `accountLocked` is the account's lock from the players view, which a step
 * cannot query; cooling off and exclusion are the workflow's own `may-bet`.
 */
final case class BetJob(
    provider: String,
    callback: String,
    kind: String,
    key: String,
    roundId: String,
    roundEntity: String,
    player: String,
    currency: String,
    amount: Long,
    gameId: String,
    sessionId: String,
    gameCategory: Option[String],
    freeRoundsGrant: Option[String],
    references: Option[String],
    accountLocked: Boolean,
    raw: String,
    opening: RoundOpening
):
  /** A stake the player places: asked of `responsible-gambling` first (FR-012, FR-013, FR-016). */
  def placesAStake: Boolean =
    (kind == Kind.Debit || kind == Kind.CorrectionDebit || kind == Kind.Buyin) && amount > 0

/**
 * The workflow's state (data-model.md *BetWorkflow*): the transaction, what the wallet step decided for the
 * round to record, and the outcome, present only once the round has recorded it (research R18).
 */
final case class BetFlow(
    status: String = BetFlow.New,
    job: Option[BetJob] = None,
    pending: Option[RecordTransaction] = None,
    countsBet: Boolean = false,
    outcome: Option[StoredAnswer] = None,
    error: Option[String] = None,
    clearing: Option[Cleared] = None
):
  def started: Boolean = status != BetFlow.New

object BetFlow:
  val New            = "new"
  val Asking         = "asking"
  val Moving         = "moving"
  val Recording      = "recording"
  val Done           = "done"
  val Unacknowledged = "unacknowledged"
  val Resolved       = "resolved"

/** What a step needs that is the service's, not the workflow's: set when the service is wired. */
object BetRuntime:
  @volatile var brand: String          = ""
  @volatile var now: () => Instant     = () => Instant.now()
  /** Attempts of the wallet step before the callback is recorded unacknowledged (research R18). */
  @volatile var walletAttempts: Int    = 3
  @volatile var walletBackoff: Duration = 20.millis
  /** A pause before `record-round`, zero but in a test that shows nothing is answered before the round knows. */
  @volatile var recordDelay: Duration = Duration.Zero

/**
 * A bet workflow per provider transaction (research R18, FR-011, FR-012, FR-020), id
 * `<provider>/<transaction id>` (the cancel id for a cancel). `start` is the claim: a second start is
 * refused `Conflict` and the caller answers from `status`. Its steps: `may-bet` for a stake, `wallet`
 * for the money (the rules of FR-012 to FR-016, in `MoneyMoves`), and `record-round`, after which the
 * outcome is written, so nothing is answered before the round knows the transaction. A wallet that cannot
 * be reached leaves no outcome: the callback is recorded unacknowledged and the workflow pauses until a
 * provider retry or an operator's replay resumes it, or a resolve ends it.
 */
final class BetWorkflow(context: WorkflowContext) extends Workflow[BetFlow]:

  private def brand      = BetRuntime.brand
  private def now()      = BetRuntime.now()
  private val components = context.componentClient
  private def wallet     = WalletClient(context.services, brand)
  private def rg         = ResponsibleGamblingClient(context.services, brand)

  def emptyState: BetFlow = BetFlow()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(30.seconds)
      // A step that throws (a crash mid-call) is run again: the wallet answers a key it has seen with the
      // first outcome, so re-running the money is safe; past that the callback is left unacknowledged.
      .stepRecovery(BetWorkflow.wallet, RecoverStrategy.maxRetries(2).failoverTo(BetWorkflow.unacknowledged))
      .stepRecovery(BetWorkflow.recordRound, RecoverStrategy.maxRetries(5))
      // `may-bet` fails closed: no answer within 500 ms ends the transaction `error`, the wallet never called.
      .stepTimeout(BetWorkflow.mayBet, ResponsibleGamblingClient.MayBetWithin)
      .stepRecovery(BetWorkflow.mayBet, RecoverStrategy.maxRetries(0).failoverTo(BetWorkflow.rgUnavailable))
      .build

  // ── Commands ─────────────────────────────────────────────────────────────────────────────────

  def start(job: BetJob): Effect[Done] =
    if currentState.started then effects.error("transaction already started", ErrorCode.Conflict)
    else if job.accountLocked then
      // A suspended, closed or blocked account: no money and no round (FR-012); the answer is the outcome.
      effects.updateState(BetFlow(BetFlow.Done, Some(job), outcome = Some(StoredAnswer(Status.PlayerLocked)))).thenReply(Done)
    else if job.placesAStake then
      effects.updateState(BetFlow(BetFlow.Asking, Some(job))).transitionTo(BetWorkflow.mayBet.ref).thenReply(Done)
    else effects.updateState(BetFlow(BetFlow.Moving, Some(job))).transitionTo(BetWorkflow.wallet.ref).thenReply(Done)

  def status: ReadOnlyEffect[BetFlow] = effects.reply(currentState)

  /** A provider retry or an operator's replay of an unacknowledged transaction: the wallet step runs again. */
  def retry(cleared: Cleared): Effect[Boolean] =
    if currentState.status != BetFlow.Unacknowledged then effects.reply(false)
    else
      effects.updateState(currentState.copy(status = BetFlow.Moving, clearing = Some(cleared), error = None))
        .transitionTo(BetWorkflow.wallet.ref).thenReply(true)

  /** An operator settled it by other means; the round's entry is cleared by the caller (FR-020). */
  def resolve(cleared: Cleared): Effect[Boolean] =
    if currentState.status != BetFlow.Unacknowledged then effects.reply(false)
    else
      effects.updateState(currentState.copy(status = BetFlow.Resolved, clearing = Some(cleared)))
        .transitionTo(BetWorkflow.resolved.ref).thenReply(true)

  // ── Steps ────────────────────────────────────────────────────────────────────────────────────

  private def job: BetJob = currentState.job.get

  def mayBetStep: StepEffect =
    val j = job
    rg.mayBet(j.player, j.currency, j.amount) match
      case Right(MayBet.Allowed) =>
        stepEffects.updateState(currentState.copy(status = BetFlow.Moving)).thenTransitionTo(BetWorkflow.wallet.ref)
      case Right(MayBet.Refused(_)) =>
        // Cooling off or excluded: answered with the balance and recorded on the round, the wallet never asked.
        val balance = wallet.balance(j.player, j.currency).toOption.map(_.total)
        val tx      = ProviderTransaction(j.key, j.kind, j.amount, None, Vector.empty, StoredAnswer(Status.PlayerLocked, balance, Some(j.currency)), now())
        stepEffects.updateState(currentState.copy(status = BetFlow.Recording, pending = Some(RecordTransaction(j.opening, tx))))
          .thenTransitionTo(BetWorkflow.recordRound.ref)
      case Left(unavailable) =>
        // Fails closed: `error`, the wallet never called, and that is the outcome (FR-012).
        BetWorkflow.log.warn(s"${j.callback} ${j.key}: ${unavailable.reason}")
        stepEffects.updateState(currentState.copy(status = BetFlow.Done, outcome = Some(StoredAnswer(Status.Error)))).thenEnd

  def walletStep: StepEffect =
    val j     = job
    val moves = MoneyMoves(j.provider, components, wallet, now)
    def attempt(n: Int): Either[String, MoneyMoves.Moved] =
      moves.move(j) match
        case Left(_) if n < BetRuntime.walletAttempts =>
          Thread.sleep(BetRuntime.walletBackoff.toMillis)
          attempt(n + 1)
        case result => result
    attempt(1) match
      case Right(moved) =>
        val cmd = moved.cmd.copy(clearedBy = currentState.clearing)
        stepEffects.updateState(currentState.copy(status = BetFlow.Recording, pending = Some(cmd), countsBet = moved.countsBet))
          .thenTransitionTo(BetWorkflow.recordRound.ref)
      case Left(error) =>
        stepEffects.updateState(currentState.copy(error = Some(error))).thenTransitionTo(BetWorkflow.unacknowledged.ref)

  def recordRoundStep: StepEffect =
    val cmd    = currentState.pending.get
    if BetRuntime.recordDelay > Duration.Zero then Thread.sleep(BetRuntime.recordDelay.toMillis)
    val answer =components.forEventSourcedEntity(EntityId(job.roundEntity)).call(Round.record).invoke(cmd).answer
    if currentState.countsBet then Metrics.betPlaced(brand)
    stepEffects.updateState(currentState.copy(status = BetFlow.Done, outcome = Some(answer))).thenEnd

  def unacknowledgedStep: StepEffect =
    val j     = job
    val error = currentState.error.getOrElse("the wallet step failed")
    components.forEventSourcedEntity(EntityId(j.roundEntity)).call(Round.recordUnacknowledged)
      .invoke(RecordUnacknowledged(j.opening, UnacknowledgedEntry(j.key, j.callback, j.kind, j.amount, error, j.raw, now()))): Unit
    BetWorkflow.log.warn(s"${j.callback} ${j.key} unacknowledged: $error")
    stepEffects.updateState(currentState.copy(status = BetFlow.Unacknowledged, error = Some(error))).thenPause()

  def rgUnavailableStep: StepEffect =
    BetWorkflow.log.warn(s"${job.callback} ${job.key}: responsible-gambling gave no answer within ${ResponsibleGamblingClient.MayBetWithin}")
    stepEffects.updateState(currentState.copy(status = BetFlow.Done, outcome = Some(StoredAnswer(Status.Error)))).thenEnd

  def resolvedStep: StepEffect = stepEffects.thenEnd

object BetWorkflow
    extends Workflow.Companion[BetWorkflow, BetFlow](
      componentId = ComponentId("bet"),
      stateSerializer = Codecs.serializer[BetFlow]("bet-flow")
    ):
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[BetWorkflow])

  given Serializer[BetJob]  = Codecs.serializer[BetJob]("bet-job")
  given Serializer[Cleared] = Codecs.serializer[Cleared]("cleared")

  def create(context: WorkflowContext) = new BetWorkflow(context)

  def id(provider: String, key: String): EntityId = EntityId(s"$provider/$key")

  val mayBet         = step("may-bet")(_.mayBetStep)
  val wallet         = step("wallet")(_.walletStep)
  val recordRound    = step("record-round")(_.recordRoundStep)
  val unacknowledged = step("unacknowledged")(_.unacknowledgedStep)
  val resolved       = step("resolved")(_.resolvedStep)
  val rgUnavailable  = step("rg-unavailable")(_.rgUnavailableStep)

  val start   = command("start")(_.start)
  val status  = query("status")(_.status)
  val retry   = command("retry")(_.retry)
  val resolve = command("resolve")(_.resolve)
