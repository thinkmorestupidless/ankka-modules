package io.eitheror.casino.simulator.core

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.*

/** A load profile's figures (US5): rounds sent, answers by word, answer-time percentiles, every answer that was not accepted. */
final case class LoadReport(
    roundsSent: Int,
    callbacksSent: Int,
    answersByWord: Map[String, Int],
    p50Millis: Long,
    p95Millis: Long,
    p99Millis: Long,
    notAccepted: Vector[String]
)

final case class LoadState(
    profileId: String,
    brand: String,
    sessions: Vector[String],
    roundsPerMinute: Int,
    durationMillis: Long,
    seed: Long,
    startedAt: Long,
    tick: Int,
    state: String,
    rounds: Int,
    callbacks: Int,
    words: Map[String, Int],
    took: Vector[Long],
    notAccepted: Vector[String],
    report: Option[LoadReport]
)

/**
 * A load profile (US5; research R9): sessions the brand launched are checked, then each plays a
 * round every interval for the duration (a bet, then on the next tick a win or a loss drawn from
 * the seed, so a round is open between ticks), and on the deadline or a stop every open round is
 * settled as a loss before the profile ends. The tick issues each send itself; a profile plays the
 * default scenario's pass-through sends (research R9, implementation note).
 *
 * The report is kept in the workflow's state as the sends answer, not read back from the run view.
 */
final class LoadProfile(context: WorkflowContext) extends Workflow[LoadState]:

  def emptyState: LoadState =
    LoadState("", "", Vector.empty, 0, 0L, 0L, 0L, 0, LoadProfile.Idle, 0, 0, Map.empty, Vector.empty, Vector.empty, None)

  override def settings: WorkflowSettings = WorkflowSettings.builder.defaultStepTimeout(5.minutes).build

  private lazy val play = Play(context.componentClient)

  def start(request: LoadProfile.Start): Effect[LoadState] =
    if currentState.state != LoadProfile.Idle then effects.error("a load profile with this id has started", ErrorCode.Conflict)
    else
      val s = emptyState.copy(
        profileId = context.workflowId, brand = request.brand, sessions = request.sessions, roundsPerMinute = request.roundsPerMinute,
        durationMillis = request.durationMillis, seed = request.seed, startedAt = request.at, state = LoadProfile.Launching
      )
      effects.updateState(s).transitionTo(LoadProfile.launch.ref).thenReply(s)

  def get: ReadOnlyEffect[LoadState] = effects.reply(currentState)

  // --- steps ---

  def launchStep: StepEffect =
    var s = currentState
    s.sessions.foreach { token =>
      play.session(token).filter(_.walletToken.isEmpty).foreach { session =>
        val sent = play.send(session, Wiring.protocol.check(token), "stub", "current", Wiring.brands.answerWait(s.brand), None)
        play.end(sent.callbackId, sent)
        s = count(s, sent)
        sent.reading.filter(Wiring.protocol.accepted).flatMap(_.token).foreach { wallet =>
          context.componentClient.forEventSourcedEntity(EntityId(token)).call(SessionEntity.check).invoke(wallet)
        }
      }
    }
    stepEffects.updateState(s.copy(state = LoadProfile.Playing)).thenTransitionTo(LoadProfile.tick.ref)

  /**
   * A stop is recorded on `LoadStop`, not on this workflow: a workflow answers commands while a step
   * runs, and a stop that moved this workflow could be overwritten by the tick in flight. The tick
   * reads it at its start and settles; nothing then runs beside the settle.
   */
  private def stopRequested: Boolean =
    context.componentClient.forKeyValueEntity(EntityId(context.workflowId)).call(LoadStop.get).invoke()

  def tickStep: StepEffect =
    var s = currentState
    if s.state != LoadProfile.Playing then stepEffects.thenEnd
    else if stopRequested || Wiring.now() - s.startedAt >= s.durationMillis then
      stepEffects.updateState(s.copy(state = LoadProfile.Settling)).thenTransitionTo(LoadProfile.settle.ref)
    else
      val seeded = Seeded(s.seed)
      s.sessions.zipWithIndex.foreach { (token, i) =>
        play.session(token).filter(_.checked).foreach { session =>
          val step = s.tick * 100000 + i
          val wait = Wiring.brands.answerWait(s.brand)
          // settle the round the last tick opened
          session.openRounds.foreach { round =>
            val win  = seeded.chance(step, 40)
            val call =
              if win then Wiring.protocol.win(session.walletToken, round.roundId, session.currency, session.gameCode, seeded.id(step, 1), seeded.amount(step, "0.10", "5.00"))
              else Wiring.protocol.loss(session.walletToken, round.roundId, session.currency, session.gameCode, seeded.id(step, 1))
            val sent = play.send(session, call, "stub", "current", wait, None)
            play.end(sent.callbackId, sent)
            s = count(s, sent)
          }
          // open the next
          val stake = Wiring.protocol.stake(session.walletToken, seeded.id(step, 2), session.currency, session.gameCode, seeded.id(step, 3), seeded.amount(step, "0.10", "1.00"))
          val sent = play.send(play.session(token).getOrElse(session), stake, "stub", "current", wait, None)
          play.end(sent.callbackId, sent)
          s = count(s, sent).copy(rounds = s.rounds + 1)
        }
      }
      // The next tick is due at a fixed point from the start, so the time a tick takes does not
      // slow the rate; a workflow pause resolves to about a second, the floor of any interval.
      val interval = 60000L / s.roundsPerMinute.max(1)
      val due      = s.startedAt + (s.tick + 1) * interval
      stepEffects.updateState(s.copy(tick = s.tick + 1)).thenPause((due - Wiring.now()).max(0L).millis, LoadProfile.tick.ref)

  def settleStep: StepEffect =
    var s = currentState
    val seeded = Seeded(s.seed)
    s.sessions.zipWithIndex.foreach { (token, i) =>
      play.session(token).foreach { session =>
        session.openRounds.zipWithIndex.foreach { (round, j) =>
          val call = Wiring.protocol.loss(session.walletToken, round.roundId, session.currency, session.gameCode, seeded.id(900000 + i, j))
          val sent = play.send(session, call, "stub", "current", Wiring.brands.answerWait(s.brand), None)
          play.end(sent.callbackId, sent)
          s = count(s, sent)
        }
      }
    }
    val sorted = s.took.sorted
    def pct(p: Double): Long = if sorted.isEmpty then 0L else sorted(((sorted.size - 1) * p).round.toInt)
    val report = LoadReport(s.rounds, s.callbacks, s.words, pct(0.50), pct(0.95), pct(0.99), s.notAccepted.take(1000))
    val ended  = if stopRequested then LoadProfile.Stopped else LoadProfile.Done
    stepEffects.updateState(s.copy(state = ended, report = Some(report))).thenEnd

  private def count(s: LoadState, sent: Play.Sent): LoadState =
    val word = sent.reading.flatMap(_.status).orElse(sent.attempt.error.map(_ => "no_answer")).getOrElse("unreadable")
    val accepted = sent.reading.exists(Wiring.protocol.accepted)
    s.copy(
      callbacks = s.callbacks + 1,
      words = s.words.updated(word, s.words.getOrElse(word, 0) + 1),
      took = s.took :+ sent.attempt.tookMillis,
      notAccepted = if accepted then s.notAccepted else s.notAccepted :+ s"${sent.callbackId}: $word"
    )

object LoadProfile
    extends Workflow.Companion[LoadProfile, LoadState](
      componentId = ComponentId("load-profile"),
      stateSerializer = Codecs.serializer[LoadState]("load-state")
    ):
  val Idle      = "idle"
  val Launching = "launching"
  val Playing   = "playing"
  val Settling  = "settling"
  val Done      = "done"
  val Stopped   = "stopped"

  final case class Start(brand: String, sessions: Vector[String], roundsPerMinute: Int, durationMillis: Long, seed: Long, at: Long)

  given Serializer[Start]     = Codecs.serializer[Start]("load-start")
  given Serializer[LoadState] = Codecs.serializer[LoadState]("load-state")

  def create(context: WorkflowContext) = new LoadProfile(context)

  val start  = command("start")(_.start)
  val get    = query("get")(_.get)
  val launch = step("launch")(_.launchStep)
  val tick   = step("tick")(_.tickStep)
  val settle = step("settle")(_.settleStep)

/** A stop asked of a load profile, keyed by the profile's id (read by its tick). */
final class LoadStop extends KeyValueEntity[Boolean]:
  def emptyState: Boolean            = false
  def request: Effect[Done]          = effects.updateState(true).thenReply(_ => Done)
  def get: ReadOnlyEffect[Boolean]   = effects.reply(currentState)

object LoadStop
    extends KeyValueEntity.Companion[LoadStop, Boolean](
      componentId = ComponentId("load-stop"),
      stateSerializer = Codecs.serializer[Boolean]("load-stop")
    ):
  def create(context: KeyValueEntityContext) = new LoadStop
  val request = command("request")(_.request)
  val get     = query("get")(_.get)
