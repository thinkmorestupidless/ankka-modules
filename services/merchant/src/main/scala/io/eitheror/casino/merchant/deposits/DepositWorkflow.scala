package io.eitheror.casino.merchant.deposits

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.merchant.{MerchantCodecs, Settings}
import io.eitheror.casino.merchant.gateway.*
import io.eitheror.casino.merchant.payments.*
import io.eitheror.casino.merchant.security.Secrets
import io.eitheror.casino.merchant.terminals.Terminal
import io.eitheror.casino.merchant.wallet.*

import java.time.{Duration as JDuration, Instant}
import scala.concurrent.duration.*

/**
 * What the submit resolved for one payment (FR-015): who, how much, which provider and terminal, and
 * the deadline. Nothing personal: the customer fields are read from `PaymentCustomer` by the initiate
 * step, and a terminal's secret properties from the store, so neither enters this journal.
 */
final case class DepositJob(
    paymentId: String,
    subBrandId: String,
    brand: Option[String],
    customerId: String,
    playerId: String,
    amount: Long,
    currency: String,
    psp: String,
    scheme: String,
    terminalId: String,
    terminalEntity: Option[String],
    transactionId: String,
    redirectUrl: String,
    createdAt: Instant,
    deadline: Instant
)

/** A capture as the gateway reported it, checked: the variance and what the wallet is to be credited. */
final case class CaptureInput(variance: Option[Variance], creditedAmount: Long, at: Instant)

/** A failure or an abandon. */
final case class FailInput(code: Option[String], reason: String, received: Option[Received], at: Instant)

/** Why an initiate did not happen, as the submit answers it (contracts/commands.md). */
final case class InitiateRefusal(status: Int, error: String, message: Option[String])

/**
 * The workflow's state (data-model.md): its status, the job, the capture or failure it was given, the
 * credit's attempts and the wallet's last answer, and why an initiate was refused.
 */
final case class DepositFlow(
    status: String = DepositFlow.New,
    job: Option[DepositJob] = None,
    capture: Option[CaptureInput] = None,
    failure: Option[FailInput] = None,
    attempts: Int = 0,
    lastAnswer: Option[String] = None,
    refusal: Option[InitiateRefusal] = None
):
  def started: Boolean = status != DepositFlow.New
  /** Awaiting the provider's outcome, before or after a deadline: a capture, a failure or an abandon is taken. */
  def open: Boolean = status == DepositFlow.Awaiting || status == DepositFlow.Waiting

object DepositFlow:
  val New             = "new"
  val Initiating      = "initiating"
  val Awaiting        = "awaiting"
  val Expiring        = "expiring"
  val Waiting         = "waiting"
  val Capturing       = "capturing"
  val Crediting       = "crediting"
  val CreditWaiting   = "credit-waiting"
  val Credited        = "credited"
  val CreditRefused   = "credit-refused"
  val CapturedNoBrand = "captured-no-brand"
  val Failing         = "failing"
  val Failed          = "failed"
  val Expired         = "expired"
  val Refused         = "refused"

  /** Where a capture is settled for the gateway: credited, captured with nothing to credit, or refused finally. */
  val CaptureSettled: Set[String] = Set(Credited, CapturedNoBrand, CreditRefused)

/** How long `credit-wait` pauses: 1 s doubling to 30 s, without bound on the attempts (SC-002, research R7). */
final case class Backoff(base: FiniteDuration = 1.second, ceiling: FiniteDuration = 30.seconds):
  def after(attempts: Int): FiniteDuration =
    val shift = math.min(attempts, 30)
    val d     = base * (1L << shift)
    if d > ceiling then ceiling else d

/**
 * What a step needs that is the service's, not the workflow's (the wallet's `DepositWorkflow.rg`
 * pattern): the settings, the clock, the backoff, and how the gateway and the wallet are reached.
 * Set when the service is wired; a workflow is constructed by its companion.
 */
object DepositRuntime:
  @volatile var settings: Settings                       = Settings()
  @volatile var now: () => Instant                       = () => Instant.now()
  @volatile var backoff: Backoff                         = Backoff()
  @volatile var gateway: ServiceClients => GatewayClient = GatewayClient.http
  @volatile var wallet: ServiceClients => WalletClient   = WalletClient.http

/**
 * One deposit (FR-020, research R7): the workflow whose id is the payment id, started by the submit.
 * `initiate` asks the gateway and records the payment; `await-outcome` pauses until a capture, a
 * failure or an abandon, or the deadline; `expire` asks the gateway whether it holds the outcome
 * undelivered, and waits another deadline if it does (or cannot say), else expires the payment;
 * `record-capture` records the capture and `credit` asks the brand's wallet, keyed by the payment id,
 * pausing with backoff while the wallet cannot answer and stopping at a final refusal until an operator
 * retries (FR-021, FR-023). Every step records its decision on the payment before moving on. The
 * outcomes other than the capture reach the brand from `OutcomeReporter`, not a step (correction 3).
 */
final class DepositWorkflow(context: WorkflowContext) extends Workflow[DepositFlow]:

  private val components = context.componentClient
  private def payment     = components.forEventSourcedEntity(EntityId(context.workflowId))
  private def now()       = DepositRuntime.now()

  def emptyState: DepositFlow = DepositFlow()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder
      .defaultStepTimeout(30.seconds)
      // The gateway answers a repeated payment id with its first answer (spec 005 FR-008), so running
      // the initiate again after a crash is safe; past that the submit is answered unavailable.
      .stepRecovery(DepositWorkflow.initiate, RecoverStrategy.maxRetries(2).failoverTo(DepositWorkflow.initiateFailed))
      .stepRecovery(DepositWorkflow.expire, RecoverStrategy.maxRetries(3).failoverTo(DepositWorkflow.awaitOutcome))
      .stepRecovery(DepositWorkflow.recordCapture, RecoverStrategy.maxRetries(5))
      .stepRecovery(DepositWorkflow.recordFailure, RecoverStrategy.maxRetries(5))
      // A credit that throws is a wallet that did not answer: wait and ask again, never give up.
      .stepRecovery(DepositWorkflow.credit, RecoverStrategy.maxRetries(1).failoverTo(DepositWorkflow.creditWait))
      .build

  private def job: DepositJob = currentState.job.get

  // ── Commands ─────────────────────────────────────────────────────────────────────────────────

  /** The atomic claim: a second start is `Conflict`, and the caller answers from `status`. */
  def start(j: DepositJob): Effect[Done] =
    if currentState.started then effects.error("deposit already started", ErrorCode.Conflict)
    else effects.updateState(DepositFlow(DepositFlow.Initiating, Some(j))).transitionTo(DepositWorkflow.initiate).thenReply(Done)

  /** The gateway's capture: taken while the outcome is awaited; anything else answers the state unchanged (FR-019). */
  def capture(c: CaptureInput): Effect[DepositFlow] =
    if !currentState.open then effects.reply(currentState)
    else effects.updateState(currentState.copy(status = DepositFlow.Capturing, capture = Some(c))).transitionTo(DepositWorkflow.recordCapture).thenReplyState

  /** The gateway's failure, or the player's abandon: taken while the outcome is awaited. */
  def fail(f: FailInput): Effect[DepositFlow] =
    if !currentState.open then effects.reply(currentState)
    else effects.updateState(currentState.copy(status = DepositFlow.Failing, failure = Some(f))).transitionTo(DepositWorkflow.recordFailure).thenReplyState

  /** An operator's retry of a credit the wallet refused finally (FR-023). */
  def retry(by: String): Effect[DepositFlow] =
    if currentState.status != DepositFlow.CreditRefused then effects.reply(currentState)
    else effects.updateState(currentState.copy(status = DepositFlow.Crediting, lastAnswer = Some(s"retried by $by")))
      .transitionTo(DepositWorkflow.retried).thenReplyState

  def status: ReadOnlyEffect[DepositFlow] = effects.reply(currentState)

  // ── Steps ────────────────────────────────────────────────────────────────────────────────────

  def initiateStep: StepEffect =
    val j        = job
    val customer = components.forKeyValueEntity(EntityId(j.paymentId)).call(PaymentCustomer.get).invoke().customer
    val props = j.terminalEntity.fold(Map.empty[String, String]) { t =>
      val terminal = components.forEventSourcedEntity(EntityId(t)).call(Terminal.get).invoke()
      val secrets  = Secrets(context.secrets).terminal(t).fold(e => throw IllegalStateException(e.reason), identity)
      terminal.properties ++ secrets
    }
    val page = HostedPage(j.paymentId, j.amount, j.currency, j.scheme, s"/v1/payments/${j.paymentId}/capture", j.psp, j.subBrandId,
      j.customerId, j.redirectUrl, j.terminalId, props, customer)
    DepositRuntime.gateway(context.services).initiate(page) match
      case Right(initiated) =>
        val crypto = initiated.crypto.map(c => CryptoDetails(c.paymentAddress, c.cryptoAmount, c.cryptoCurrency, c.validUntil))
        val status = if crypto.isDefined then Status.AwaitingCrypto else Status.AwaitingRedirect
        payment.call(Payment.initiate).invoke(Open(Opened(j.subBrandId, j.brand, j.customerId, j.playerId, j.amount, j.currency, j.psp,
          j.scheme, j.terminalId, j.transactionId, initiated.pspTransactionId, initiated.redirectUrl, crypto, j.createdAt, j.deadline), status)): Unit
        stepEffects.updateState(currentState.copy(status = DepositFlow.Awaiting)).thenTransitionTo(DepositWorkflow.awaitOutcome)
      case Left(GatewayRefusal.Player(message)) =>
        refused(InitiateRefusal(400, "PAYMENT_REJECTED", Some(message)))
      case Left(GatewayRefusal.Failure(status, detail)) =>
        DepositWorkflow.log.warn(s"initiate ${j.paymentId}: the gateway answered $status: $detail")
        refused(InitiateRefusal(502, "psp_error", None))
      case Left(GatewayRefusal.Unreachable(reason)) =>
        DepositWorkflow.log.warn(s"initiate ${j.paymentId}: the gateway could not be reached: $reason")
        refused(InitiateRefusal(503, "psp_unavailable", None))

  private def refused(r: InitiateRefusal): StepEffect =
    stepEffects.updateState(currentState.copy(status = DepositFlow.Refused, refusal = Some(r))).thenEnd

  def initiateFailedStep: StepEffect = refused(InitiateRefusal(503, "psp_unavailable", None))

  def awaitOutcomeStep: StepEffect =
    val left = JDuration.between(now(), job.deadline).toMillis
    stepEffects.updateState(currentState.copy(status = if currentState.status == DepositFlow.Waiting then DepositFlow.Waiting else DepositFlow.Awaiting))
      .thenPause(math.max(1L, left).millis, DepositWorkflow.expire)

  def expireStep: StepEffect =
    DepositRuntime.gateway(context.services).session(job.paymentId) match
      case SessionStatus.Undelivered    => waitAgain("the gateway holds the provider's outcome undelivered")
      case SessionStatus.Unreadable(why) => waitAgain(s"the gateway could not say whether it holds an outcome: $why")
      case _ =>
        payment.call(Payment.expire).invoke(At(now())): Unit
        stepEffects.updateState(currentState.copy(status = DepositFlow.Expired)).thenEnd

  /** The payment is not expired while its outcome may still arrive: it waits another deadline and says why (FR-020, SC-003). */
  private def waitAgain(reason: String): StepEffect =
    payment.call(Payment.waiting).invoke(RecordWaiting(reason, now())): Unit
    val again = DepositRuntime.settings.paymentDeadline.toMillis.millis
    stepEffects.updateState(currentState.copy(status = DepositFlow.Waiting)).thenPause(again, DepositWorkflow.expire)

  def recordCaptureStep: StepEffect =
    val c = currentState.capture.get
    payment.call(Payment.capture).invoke(RecordCapture(c.variance, c.creditedAmount, c.at)): Unit
    if job.brand.isEmpty then stepEffects.updateState(currentState.copy(status = DepositFlow.CapturedNoBrand)).thenEnd
    else stepEffects.updateState(currentState.copy(status = DepositFlow.Crediting)).thenTransitionTo(DepositWorkflow.credit)

  def creditStep: StepEffect =
    val j = job
    val c = currentState.capture.get
    val read = payment.call(Payment.get).invoke()
    val reference = read.opened.map(_.pspTransactionId).getOrElse("")
    DepositRuntime.wallet(context.services).credit(Credit(j.brand.get, j.playerId, j.currency, j.paymentId, c.creditedAmount, j.terminalId,
      j.psp, j.scheme, reference)) match
      case CreditAnswer.Credited(txnId) =>
        payment.call(Payment.credited).invoke(RecordCredited(txnId, now())): Unit
        stepEffects.updateState(currentState.copy(status = DepositFlow.Credited, attempts = currentState.attempts + 1, lastAnswer = Some("credited"))).thenEnd
      case CreditAnswer.Final(code, message, details) =>
        payment.call(Payment.creditRefused).invoke(RecordCreditRefused(code, message, details, now())): Unit
        stepEffects.updateState(currentState.copy(status = DepositFlow.CreditRefused, attempts = currentState.attempts + 1,
          lastAnswer = Some(s"$code: $message"))).thenPause()
      case CreditAnswer.Retry(answer) =>
        payment.call(Payment.creditAttempted).invoke(RecordAttempt(answer, now())): Unit
        val attempts = currentState.attempts + 1
        stepEffects.updateState(currentState.copy(status = DepositFlow.CreditWaiting, attempts = attempts, lastAnswer = Some(answer)))
          .thenPause(DepositRuntime.backoff.after(attempts - 1), DepositWorkflow.credit)

  /** A credit step that threw: the wallet gave no answer the client could read; wait and ask again. */
  def creditWaitStep: StepEffect =
    val attempts = currentState.attempts + 1
    stepEffects.updateState(currentState.copy(status = DepositFlow.CreditWaiting, attempts = attempts, lastAnswer = Some("no answer")))
      .thenPause(DepositRuntime.backoff.after(attempts - 1), DepositWorkflow.credit)

  def retriedStep: StepEffect =
    payment.call(Payment.creditRetried).invoke(RecordRetry(currentState.lastAnswer.getOrElse("operator"), now())): Unit
    stepEffects.updateState(currentState.copy(status = DepositFlow.Crediting)).thenTransitionTo(DepositWorkflow.credit)

  def recordFailureStep: StepEffect =
    val f = currentState.failure.get
    payment.call(Payment.fail).invoke(RecordFailure(f.code, f.reason, f.received, f.at)): Unit
    stepEffects.updateState(currentState.copy(status = DepositFlow.Failed)).thenEnd

object DepositWorkflow
    extends Workflow.Companion[DepositWorkflow, DepositFlow](
      componentId = ComponentId("deposit"),
      stateSerializer = MerchantCodecs.serializer[DepositFlow]("deposit-flow")
    ):
  private val log = org.slf4j.LoggerFactory.getLogger(classOf[DepositWorkflow])

  given Serializer[DepositJob]   = MerchantCodecs.serializer[DepositJob]("deposit-job")
  given Serializer[CaptureInput] = MerchantCodecs.serializer[CaptureInput]("capture-input")
  given Serializer[FailInput]    = MerchantCodecs.serializer[FailInput]("fail-input")

  def create(context: WorkflowContext) = new DepositWorkflow(context)

  val initiate       = step("initiate")(_.initiateStep)
  val initiateFailed = step("initiate-failed")(_.initiateFailedStep)
  val awaitOutcome   = step("await-outcome")(_.awaitOutcomeStep)
  val expire         = step("expire")(_.expireStep)
  val recordCapture  = step("record-capture")(_.recordCaptureStep)
  val credit         = step("credit")(_.creditStep)
  val creditWait     = step("credit-wait")(_.creditWaitStep)
  val retried        = step("retried")(_.retriedStep)
  val recordFailure  = step("record-failure")(_.recordFailureStep)

  val start   = command("start")(_.start)
  val capture = command("capture")(_.capture)
  val fail    = command("fail")(_.fail)
  val retry   = command("retry")(_.retry)
  val status  = query("status")(_.status)
