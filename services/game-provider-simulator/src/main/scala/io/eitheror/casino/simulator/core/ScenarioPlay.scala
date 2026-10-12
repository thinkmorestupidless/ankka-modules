package io.eitheror.casino.simulator.core

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.*

/** What came back for one attempt, as the scenario judges it. */
final case class AnswerSeen(attempt: Int, httpStatus: Option[Int], status: Option[String], balance: Option[String], error: Option[String])

/** One send step's callback and everything its attempts answered. */
final case class SendRecord(
    step: Int,
    callbackId: String,
    kind: String,
    transactionId: String,
    cancelId: Option[String],
    round: Option[String],
    amount: Option[String],
    role: String,
    accepted: Boolean,
    balanceBefore: Option[String],
    firstSentAt: Long,
    answers: Vector[AnswerSeen],
    done: Boolean
):
  def answered: Vector[AnswerSeen]   = answers.filter(_.httpStatus.isDefined)
  def last: Option[AnswerSeen]       = answered.lastOption
  def first: Option[AnswerSeen]      = answered.headOption

/** An instruction a tester gave while the scenario waits for one (FR-014 under a scripted scenario). */
final case class Pending(kind: String, fields: Map[String, String], callbackId: String)

final case class PlayState(
    launchToken: String,
    scenario: String,
    cursor: Int,
    records: Map[String, SendRecord],
    inFlight: Option[Int],
    pending: Option[Pending],
    finished: Boolean
):
  def record(step: Int): Option[SendRecord] = records.get(step.toString)
  def withRecord(r: SendRecord): PlayState  = copy(records = records.updated(r.step.toString, r))

/**
 * Walks a scripted scenario for one session (FR-016, FR-017; research R4): one workflow per
 * session, keyed by its launch ticket, started when the game page's check succeeds. Each scenario
 * step is one workflow step, so a step's time is bounded by one send. A send that the scenario's
 * schedule says to retry pauses until the next interval and is sent again under the same callback;
 * a wait pauses; an expectation is judged and written to the session (read there, never from the
 * run view). Sends are sequential within a session, which is what lets one workflow replace the
 * plan's per-callback workflow (implementation note in research R4).
 *
 * Every id and draw comes from the session's seed and the step's index (research R6), so a step
 * that runs twice sends the same thing, and the brand's idempotency is what is under test.
 */
final class ScenarioPlay(context: WorkflowContext) extends Workflow[PlayState]:

  def emptyState: PlayState = PlayState("", "", 0, Map.empty, None, None, finished = false)

  override def settings: WorkflowSettings =
    WorkflowSettings.builder.defaultStepTimeout(2.minutes).defaultRecovery(RecoverStrategy.maxRetries(1)).build

  private lazy val play = Play(context.componentClient)

  def start(request: ScenarioPlay.Start): Effect[Done] =
    if currentState.launchToken.nonEmpty then effects.reply(Done)
    else
      effects.updateState(PlayState(request.launchToken, request.scenario, 0, Map.empty, None, None, finished = false))
        .transitionTo(ScenarioPlay.advance.ref).thenReply(Done)

  /** A tester's instruction, accepted only while the scenario waits for one of its kind. */
  def instruct(pending: Pending): Effect[Done] =
    val waiting = scenario().steps.lift(currentState.cursor) match
      case Some(s: Step.Send) if s.on == "instruction" && currentState.pending.isEmpty && currentState.inFlight.isEmpty => Some(s)
      case _ => None
    waiting match
      case Some(s) if s.callback == pending.kind =>
        effects.updateState(currentState.copy(pending = Some(pending))).transitionTo(ScenarioPlay.advance.ref).thenReply(Done)
      case Some(s) =>
        effects.error(s"scenario_waits_for: step ${currentState.cursor} of '${currentState.scenario}' waits for ${s.callback}, not ${pending.kind}", ErrorCode.Conflict)
      case None =>
        effects.error(s"scenario_not_waiting: '${currentState.scenario}' is not waiting for an instruction at step ${currentState.cursor}", ErrorCode.Conflict)

  def state: ReadOnlyEffect[PlayState] = effects.reply(currentState)

  // --- steps ---

  def advance: StepEffect =
    val s       = currentState
    val steps   = scenario().steps
    if s.cursor >= steps.size then stepEffects.updateState(s.copy(finished = true)).thenEnd
    else
      steps(s.cursor) match
        case Step.PassThrough => stepEffects.updateState(s.copy(finished = true)).thenEnd
        case send: Step.Send if send.on == "instruction" && s.pending.isEmpty =>
          stepEffects.thenPause()
        case send: Step.Send => sendStep(send)
        case expect: Step.Expect =>
          judge(expect)
          next(s)
        case Step.Wait(fixed, range, until) =>
          val session = sessionNow()
          val pause = fixed
            .orElse(range.map((lo, hi) => Seeded(session.seed).duration(s.cursor, lo, hi)))
            .orElse(until.collect { case "brand-bound" => Wiring.brands.keyRefreshBound(session.brand) })
            .orElse(until.collect { case "window" => Wiring.brands.answerWait(session.brand) })
            .getOrElse(Duration.Zero)
          stepEffects.updateState(s.copy(cursor = s.cursor + 1)).thenPause(pause, ScenarioPlay.advance.ref)
        case Step.SettleOpenRounds =>
          settleOpenRounds(s.cursor)
          next(s)
        case Step.RotateKey(_) =>
          Wiring.keys.publishNext()
          stepEffects.thenPause(Wiring.brands.keyRefreshBound(sessionNow().brand), ScenarioPlay.promote.ref)

  def retry: StepEffect =
    val s = currentState
    (s.inFlight.flatMap(s.record), scenario().steps.lift(s.inFlight.getOrElse(-1))) match
      case (Some(record), Some(send: Step.Send)) =>
        val session = sessionNow()
        val sent    = play.resend(session, record.callbackId, send.key, schedule(send, session).answerWait, record.answers.size + 1)
        decide(send, record, sent, session)
      case _ => next(s.copy(inFlight = None))

  def promote: StepEffect =
    Wiring.keys.promote()
    Wiring.keys.withdrawOld()
    next(currentState)

  // --- the work of a step ---

  private def next(s: PlayState): StepEffect =
    stepEffects.updateState(s.copy(cursor = s.cursor + 1, inFlight = None, pending = None)).thenTransitionTo(ScenarioPlay.advance.ref)

  private def sendStep(send: Step.Send): StepEffect =
    val s       = currentState
    val session = sessionNow()
    val seeded  = Seeded(session.seed)
    val step    = s.cursor
    val supplied = s.pending.map(_.fields).getOrElse(Map.empty)
    def ref(text: String): Option[String] =
      text match
        case "new"     => Some(seeded.id(step, 0))
        case "unknown" => Some(seeded.id(step, 7))
        case other if other.startsWith("same-as ") => s.record(other.stripPrefix("same-as ").toInt).map(_.transactionId)
        case _         => None
    val round = supplied.get("round").orElse(send.round match
      case "new"  => Some(seeded.id(step, 2))
      case "same" => session.lastRound.map(_.roundId).orElse(Some(seeded.id(step, 2)))
      case _      => None
    )
    val protocol = Wiring.protocol
    val transactionId = supplied.get("transactionId").orElse(ref(send.transaction))
    val call = Call(
      kind = send.callback,
      token = send.token match
        case "session" => session.walletToken
        case "unknown" => Some(seeded.id(step, 3))
        case _         => None,
      transactionId = transactionId,
      round = round,
      currency = supplied.get("currency").orElse(Some(session.currency)),
      amount = supplied.get("amount").orElse(send.amount.map(_.draw(seeded, step))),
      providerKind = send.providerKind.orElse(protocol.defaultProviderKind(send.callback)),
      gameCode = supplied.get("gameCode").orElse(send.gameCode).orElse(Some(session.gameCode)),
      cancelId = supplied.get("cancelId").orElse(Some(seeded.id(step, 1))).filter(_ => protocol.roleOf(send.callback, None, None) == RoundRole.Cancel),
      player = Some(session.playerId),
      site = Some(session.brand),
      extra = send.extra,
      malformed = send.malformed
    )
    val sched = schedule(send, session)
    // Unique to the session and stable across a rerun of this step: the brand's idempotency, not ours, is under test.
    val sent  = play.send(session, call, scenario().shape, send.key, sched.answerWait, Some(step), s.pending.map(_.callbackId).getOrElse(s"${s.launchToken}-$step"))
    val record = SendRecord(
      step, sent.callbackId, call.kind, call.transactionId.getOrElse(""), call.cancelId, call.round, call.amount,
      protocol.roleOf(call.kind, call.providerKind, call.amount).toString, accepted = false,
      balanceBefore = session.lastBalance, firstSentAt = sent.attempt.sentAt, answers = Vector.empty, done = false
    )
    decide(send, record, sent, session)

  /** After an attempt: retry on the schedule, or finish the callback and go on. */
  private def decide(send: Step.Send, record: SendRecord, sent: Play.Sent, session: Session): StepEffect =
    val a       = sent.attempt
    val seen    = AnswerSeen(a.attempt, a.httpStatus, a.status, a.balance, a.error)
    val updated = record.copy(answers = record.answers.filterNot(_.attempt == a.attempt) :+ seen, accepted = sent.reading.exists(Wiring.protocol.accepted))
    val sched   = schedule(send, session)
    val retryable = sent.reading match
      case None    => true
      case Some(r) => !r.malformedBody && r.status.exists(sched.retryOn.contains)
    val elapsed = (Wiring.now() - record.firstSentAt).max(0L).millis
    sched.nextInterval(elapsed).filter(_ => retryable) match
      case Some(interval) =>
        stepEffects.updateState(currentState.withRecord(updated).copy(inFlight = Some(record.step))).thenPause(interval, ScenarioPlay.retry.ref)
      case None =>
        play.end(record.callbackId, sent)
        next(currentState.withRecord(updated.copy(done = true)))

  private def schedule(send: Step.Send, session: Session): Schedule =
    send.schedule.orElse(scenario().schedule)
      .map(_.resolve(Wiring.protocol.defaultRetryOn))
      .getOrElse(Schedule.once(Wiring.brands.answerWait(session.brand)))

  private def settleOpenRounds(step: Int): Unit =
    val session = sessionNow()
    val seeded  = Seeded(session.seed)
    session.openRounds.zipWithIndex.foreach { (round, i) =>
      val call = Wiring.protocol.loss(session.walletToken, round.roundId, session.currency, session.gameCode, seeded.id(step, 10 + i))
      val sent = play.send(session, call, scenario().shape, "current", Wiring.brands.answerWait(session.brand), Some(step), s"${session.launchToken}-$step-settle-$i")
      play.end(sent.callbackId, sent)
    }

  private def judge(expect: Step.Expect): Unit =
    val s       = currentState
    val record  = s.record(expect.of)
    val last    = record.flatMap(_.last)
    val checks  = Vector.newBuilder[(String, String, Boolean)]
    if expect.status.nonEmpty then
      checks += ((s"status ${expect.status.mkString(" or ")}", s"status ${last.flatMap(_.status).getOrElse("none")}", last.flatMap(_.status).exists(expect.status.contains)))
    expect.httpStatus.foreach(h => checks += ((s"http $h", s"http ${last.flatMap(_.httpStatus).getOrElse("none")}", last.flatMap(_.httpStatus).contains(h))))
    expect.balance match
      case "unchanged" =>
        val before = record.flatMap(_.balanceBefore)
        val after  = last.flatMap(_.balance)
        checks += ((s"balance unchanged from ${before.getOrElse("?")}", s"balance ${after.getOrElse("none")}", before.isDefined && decimal(before) == decimal(after)))
      case "exact" =>
        checks += ((s"balance ${expect.exact.getOrElse("?")}", s"balance ${last.flatMap(_.balance).getOrElse("none")}", decimal(expect.exact) == decimal(last.flatMap(_.balance)) && expect.exact.isDefined))
      case "moved_once" =>
        val r       = record
        val before  = r.flatMap(_.balanceBefore).flatMap(b => decimal(Some(b)))
        val amount  = r.flatMap(_.amount).flatMap(a => decimal(Some(a))).getOrElse(BigDecimal(0))
        val sign    = r.map(_.role) match
          case Some("Stake")  => -1
          case Some("Win")    => 1
          case _              => 0
        val expected = before.map(_ + amount * sign)
        val observed = decimal(last.flatMap(_.balance))
        val balances = r.map(_.answered.flatMap(_.balance).map(b => BigDecimal(b)).distinct).getOrElse(Vector.empty)
        checks += ((s"balance moved once to ${expected.map(_.setScale(2).toString).getOrElse("?")}", s"balance ${observed.map(_.toString).getOrElse("none")} over ${balances.size} distinct answers", expected.isDefined && expected == observed && balances.size <= 1))
      case _ =>
    expect.sameAnswerAs.foreach { other =>
      val theirs = s.record(other).flatMap(_.first).map(a => (a.status, a.balance))
      val ours   = last.map(a => (a.status, a.balance))
      checks += ((s"the answer of step $other", s"${ours.getOrElse("none")}", theirs.isDefined && theirs == ours))
    }
    val attempts = record.map(_.answers.size).getOrElse(0)
    expect.minAttempts.foreach(m => checks += ((s"at least $m attempts", s"$attempts attempts", attempts >= m)))
    expect.maxAttempts.foreach(m => checks += ((s"at most $m attempts", s"$attempts attempts", attempts <= m)))
    expect.roundSettledAs.foreach { state =>
      val round = record.flatMap(_.round).flatMap(id => sessionNow().rounds.get(id))
      checks += ((s"round $state and accepted", s"round ${round.map(_.state).getOrElse("none")}, accepted ${record.exists(_.accepted)}", round.exists(_.state == state) && record.exists(_.accepted)))
    }
    val all = checks.result()
    val verdict = Verdict(
      step = s.cursor, of = expect.of,
      expected = all.map(_._1).mkString("; "),
      observed = (if record.isEmpty then Vector(s"step ${expect.of} sent nothing") else all.map(_._2)).mkString("; "),
      passed = record.isDefined && all.forall(_._3)
    )
    context.componentClient.forEventSourcedEntity(EntityId(s.launchToken)).call(SessionEntity.judge).invoke(verdict): Unit

  private def decimal(text: Option[String]): Option[BigDecimal] =
    text.flatMap(t => scala.util.Try(BigDecimal(t).setScale(2, BigDecimal.RoundingMode.HALF_EVEN)).toOption)

  private def sessionNow(): Session =
    context.componentClient.forEventSourcedEntity(EntityId(currentState.launchToken)).call(SessionEntity.get).invoke()

  private def scenario(): Scenario =
    val stored = context.componentClient.forKeyValueEntity(EntityId(currentState.scenario)).call(ScenarioEntity.get).invoke()
    Scenario.parse(stored.json).fold(e => throw IllegalStateException(s"scenario '${stored.name}': $e"), identity)

object ScenarioPlay
    extends Workflow.Companion[ScenarioPlay, PlayState](
      componentId = ComponentId("scenario-play"),
      stateSerializer = Codecs.serializer[PlayState]("scenario-play-state")
    ):

  final case class Start(launchToken: String, scenario: String)

  given Serializer[Start]     = Codecs.serializer[Start]("scenario-play-start")
  given Serializer[Pending]   = Codecs.serializer[Pending]("scenario-play-pending")
  given Serializer[PlayState] = Codecs.serializer[PlayState]("scenario-play-state")

  def create(context: WorkflowContext) = new ScenarioPlay(context)

  val start    = command("start")(_.start)
  val instruct = command("instruct")(_.instruct)
  val state    = query("state")(_.state)

  val advance = step("advance")(_.advance)
  val retry   = step("retry")(_.retry)
  val promote = step("promote")(_.promote)
