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
    language: String = "",
    /** The game service that owned the code when the launch was admitted (spec 026): only it plays the session. */
    owner: String = ""
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
      demo = e.demo, language = e.language, owner = e.owner
    )

  /**
   * Why a callback of `role` may not be planned on round `roundId` now, or none (spec 026 FR-011,
   * contracts/game-contract.md): a stake on a round id already used, a settle of a round not open,
   * a cancel of a round with no debit or already ended. The entity applies it, so two concurrent
   * calls for one round cannot both pass.
   */
  def refusal(role: RoundRole, roundId: Option[String]): Option[String] =
    val r = roundId.filter(_.nonEmpty).flatMap(round)
    role match
      case RoundRole.Stake if r.isDefined => Some(Session.Refusal.RoundUsed)
      case RoundRole.Win | RoundRole.Loss =>
        r.map(_.state) match
          case Some(Round.Open)    => None
          case Some(Round.Pending) => Some(Session.Refusal.Pending)
          case _                   => Some(Session.Refusal.RoundNotOpen)
      case RoundRole.Cancel if !r.exists(x => (x.state == Round.Open || x.state == Round.Pending) && x.debits.nonEmpty) =>
        Some(Session.Refusal.NoOpenDebit)
      case _ => None

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
          case RoundRole.Stake   => round.copy(debits = round.debits :+ e.transactionId, stakes = round.stakes ++ e.amount)
          case RoundRole.Win     => round.copy(credits = round.credits :+ e.transactionId, state = Round.Won, wins = round.wins ++ e.amount, settledAt = Some(e.at))
          case RoundRole.Loss    => round.copy(credits = round.credits :+ e.transactionId, state = Round.Lost, wins = round.wins ++ e.amount, settledAt = Some(e.at))
          case RoundRole.Cancel  => round.copy(cancels = round.cancels :+ e.cancelId.getOrElse(""), state = Round.Cancelled, settledAt = Some(e.at))
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

  /** The words a guarded plan is refused with, as the game contract answers them (409). */
  object Refusal:
    val RoundUsed    = "round_used"
    val Pending      = "pending"
    val RoundNotOpen = "round_not_open"
    val NoOpenDebit  = "no_open_debit"
    val All          = Set(RoundUsed, Pending, RoundNotOpen, NoOpenDebit)

  def empty(token: String): Session =
    Session(token, "", "", "", "", None, "default", scenarioSelected = false, token, 0L, Map.empty, Vector.empty, Vector.empty, Vector.empty, None, 0L, Empty)

  /** One event folded into a session: the entity's fold, and a view's that keeps the session as its row. */
  def applied(s: Session, event: SessionEvent): Session =
    import SessionEvent.{Launched as L, ScenarioSelected, Checked as Ch, CheckRefused, RoundAnswered, CallbackPlanned, BalanceSeen, StepJudged, Discarded}
    event match
      case e: L                      => s.onLaunched(e)
      case ScenarioSelected(n, seed) => s.copy(scenario = n, scenarioSelected = true, seed = seed.getOrElse(s.seed))
      case Ch(w)                     => s.copy(walletToken = Some(w), state = Session.Checked)
      case CheckRefused(_)           => s.copy(state = Session.Refused)
      case e: RoundAnswered          => s.onAnswered(e)
      case e: CallbackPlanned        => s.onPlanned(e)
      case BalanceSeen(b)            => s.copy(lastBalance = Some(b))
      case StepJudged(v)             => s.copy(verdicts = s.verdicts.filterNot(_.step == v.step) :+ v)
      case Discarded                 => s

/** One round in a session (FR-003): its state and the transaction ids sent for it. */
final case class Round(
    roundId: String,
    state: String,
    debits: Vector[String],
    credits: Vector[String],
    cancels: Vector[String],
    free: Boolean,
    /** The amounts sent, as the protocol wrote them: stakes, and the credit that settled it. */
    stakes: Vector[String] = Vector.empty,
    wins: Vector[String] = Vector.empty,
    /** When a win, a loss or a cancel was planned on it. */
    settledAt: Option[Long] = None
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
      demo: Boolean = false, language: String = "", owner: String = "")
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
      awaitingAnswer: Boolean = false,
      /** The amount as the protocol wrote it, and when it was planned (spec 026: the day export). */
      amount: Option[String] = None,
      at: Long = 0L
  )
  /** A stake planned as awaiting its answer was answered: accepted, or refused. */
  case RoundAnswered(roundId: String, accepted: Boolean)
  case BalanceSeen(balance: String)
  case StepJudged(verdict: Verdict)
  case Discarded
