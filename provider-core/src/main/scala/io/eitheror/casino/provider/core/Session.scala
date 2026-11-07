package io.eitheror.casino.provider.core

/**
 * One launch (FR-002): the brand and the player id it passed, the currency and game, the launch
 * ticket it is keyed by and the session ticket the brand returned at the check, the scenario, the
 * run, the rounds and, for a scripted scenario, its cursor and verdicts. The player id is the only
 * thing held about a player (FR-023).
 *
 * The house provider (spec 026) adds whether the launch was in fun mode (`demo`) and the brand
 * entry's language; its scenario fields hold `default`, `0` and none.
 */
final case class Session(
    launchToken: String,
    brand: String,
    playerId: String,
    currency: String,
    gameCode: String,
    walletToken: Option[String],
    scenario: String,
    scenarioSelected: Boolean,
    runId: String,
    seed: Long,
    rounds: Map[String, Round],
    roundOrder: Vector[String],
    sent: Vector[String],
    verdicts: Vector[Verdict],
    lastBalance: Option[String],
    launchedAt: Long,
    state: String,
    demo: Boolean = false,
    language: String = ""
):
  def launched: Boolean = state != Session.Empty
  def checked: Boolean  = walletToken.isDefined

  def openRounds: Vector[Round] = roundOrder.flatMap(rounds.get).filter(_.state == Round.Open)

  def round(id: String): Option[Round] = rounds.get(id)

  def refused: Boolean = state == Session.Refused

  def lastRound: Option[Round] = roundOrder.lastOption.flatMap(rounds.get)

  def onLaunched(e: SessionEvent.Launched): Session =
    copy(
      brand = e.brand, playerId = e.playerId, currency = e.currency, gameCode = e.gameCode,
      scenario = if scenarioSelected then scenario else e.scenario,
      runId = e.runId, seed = if scenarioSelected && seed != 0L then seed else e.seed, launchedAt = e.at, state = Session.Launched,
      demo = e.demo, language = e.language
    )

  /**
   * A stake planned as awaiting its answer leaves its round `pending`; the answer opens it, or a
   * refusal removes it, as though the round had never been opened (spec 026 data-model, Round).
   */
  def onAnswered(e: SessionEvent.RoundAnswered): Session =
    rounds.get(e.roundId).filter(_.state == Round.Pending) match
      case None => this
      case Some(r) if e.accepted => copy(rounds = rounds.updated(r.roundId, r.copy(state = Round.Open)))
      case Some(r) => copy(rounds = rounds - r.roundId, roundOrder = roundOrder.filterNot(_ == r.roundId))

  def onPlanned(e: SessionEvent.CallbackPlanned): Session =
    val withSent = copy(sent = sent ++ Vector(e.transactionId, e.cancelId.getOrElse("")).filter(_.nonEmpty))
    e.roundId.filter(_.nonEmpty) match
      case None => withSent
      case Some(id) =>
        val existing = rounds.get(id)
        val opened   = if e.awaitingAnswer then Round.Pending else Round.Open
        val round    = existing.getOrElse(Round(id, opened, Vector.empty, Vector.empty, Vector.empty, free = e.free))
        val moved = RoundRole.valueOf(e.role) match
          case RoundRole.Stake   => round.copy(debits = round.debits :+ e.transactionId)
          case RoundRole.Win     => round.copy(credits = round.credits :+ e.transactionId, state = Round.Won)
          case RoundRole.Loss    => round.copy(credits = round.credits :+ e.transactionId, state = Round.Lost)
          case RoundRole.Cancel  => round.copy(cancels = round.cancels :+ e.cancelId.getOrElse(""), state = Round.Cancelled)
          case RoundRole.Outside => round
        withSent.copy(
          rounds = withSent.rounds.updated(id, moved),
          roundOrder = if existing.isDefined then withSent.roundOrder else withSent.roundOrder :+ id
        )

object Session:
  val Empty    = "empty"
  val Launched = "launched"
  val Checked  = "checked"
  val Refused  = "refused"

  def empty(token: String): Session =
    Session(token, "", "", "", "", None, "default", scenarioSelected = false, token, 0L, Map.empty, Vector.empty, Vector.empty, Vector.empty, None, 0L, Empty)

/** One round in a session (FR-003): its state and the transaction ids sent for it. */
final case class Round(
    roundId: String,
    state: String,
    debits: Vector[String],
    credits: Vector[String],
    cancels: Vector[String],
    free: Boolean
)

object Round:
  val Open      = "open"
  val Pending   = "pending"
  val Won       = "won"
  val Lost      = "lost"
  val Cancelled = "cancelled"

/** Expected against observed for one `expect` step of a scripted scenario. */
final case class Verdict(
    step: Int,
    of: Int,
    expected: String,
    observed: String,
    passed: Boolean
)

enum SessionEvent:
  case Launched(brand: String, playerId: String, currency: String, gameCode: String, scenario: String, runId: String, seed: Long, at: Long,
      demo: Boolean = false, language: String = "")
  case ScenarioSelected(name: String, seed: Option[Long])
  case Checked(walletToken: String)
  /** The brand refused the launch ticket at the check; the word is the brand's (spec 026 FR-009). */
  case CheckRefused(word: String)
  case CallbackPlanned(
      callbackId: String,
      kind: String,
      roundId: Option[String],
      transactionId: String,
      cancelId: Option[String],
      role: String,
      free: Boolean,
      step: Option[Int],
      awaitingAnswer: Boolean = false
  )
  /** A stake planned as awaiting its answer was answered: accepted, or refused. */
  case RoundAnswered(roundId: String, accepted: Boolean)
  case BalanceSeen(balance: String)
  case StepJudged(verdict: Verdict)
  case Discarded
