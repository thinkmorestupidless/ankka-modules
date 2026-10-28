package io.eitheror.casino.wallet.features

import com.thinkmorestupidless.ankka.testkit.{CommandResult, EventSourcedTestKit, GherkinSuite, LogCapturing}
import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.funding.{Clawback, Record, Status}
import io.eitheror.casino.wallet.views.TransactionRows

import java.time.Instant

/** What one action answered and wrote. */
final case class Result(outcome: Outcome, events: Vector[WalletEvent])

/**
 * The living features of the wallet bound to the entity (constitution VII), one suite per file.
 *
 * Givens describe a wallet; the first action opens it with one `open-migrated` carrying exactly what
 * was described (a real balance, records in any status, open bets, a pending request), and every
 * further Given or When is a real command. Expected values come from the scenario's words.
 */
abstract class WalletFeatures(file: String) extends GherkinSuite(s"../../features/wallet/$file") with LogCapturing:

  /** The refusals as the features name them. */
  protected val refusals: Map[String, String] = Map(
    "insufficient funds"                 -> "INSUFFICIENT_FUNDS",
    "round not found"                    -> "ROUND_NOT_FOUND",
    "scope blocked"                      -> "SCOPE_BLOCKED",
    "maximum stake exceeded"             -> "MAX_STAKE_EXCEEDED",
    "no active restricted funds"         -> "NO_ACTIVE_BONUS",
    "not pending cap"                    -> "NOT_PENDING_CAP",
    "award not active"                   -> "GRANT_NOT_ACTIVE",
    "award not found"                    -> "GRANT_NOT_FOUND",
    "restricted funds already active"    -> "BONUS_ALREADY_ACTIVE",
    "restricted funds unmigrated"        -> "RESTRICTED_FUNDS_UNMIGRATED",
    "withdrawal request already pending" -> "WITHDRAWAL_REQUEST_ALREADY_PENDING",
    "withdrawal blocked by turnover"     -> "WITHDRAWAL_BLOCKED_TURNOVER",
    "insufficient after forfeit"         -> "WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT",
    "request not pending"                -> "REQUEST_NOT_PENDING",
    "debit exceeds balance"              -> "DEBIT_EXCEEDS_BALANCE",
    "reason required"                    -> "REASON_REQUIRED",
    "no open restricted funds"           -> "NO_OPEN_RESTRICTED_FUNDS",
    "key reused"                         -> "KEY_REUSED"
  )

  protected val t0 = Instant.parse("2026-10-09T10:00:00Z")

  protected type Kit = EventSourcedTestKit[WalletEntity, WalletState, WalletEvent]

  protected final case class Seed(
      real: Long = 0L,
      pending: Long = 0L,
      records: Vector[AwardedRecord] = Vector.empty,
      bets: Vector[Bet] = Vector.empty,
      restrictedWithoutRecords: Long = 0L,
      withdrawals: Vector[WithdrawalRequest] = Vector.empty,
      pendingRequestId: Option[String] = None,
      /**
       * The wallet's pools at opening (spec 025). Absent, a seeded real balance is deposit-origin (a
       * negative one converted): the features before the pools describe deposited money.
       */
      pools: Option[(Long, Long)] = None
  ):
    def openingPools: (Long, Long) = pools.getOrElse(if real >= 0 then (real, 0L) else (0L, real))

  protected var currency                         = "EUR"
  protected var seed                             = Seed()
  /** Clawbacks a scenario says were computed for a record: its baseline is set to give them when the wallet opens. */
  protected var clawbacks                        = Map.empty[String, Long]
  protected var opened                           = false
  protected var noWallet                         = false
  protected var kit: Kit                         = null
  protected var clock                            = t0
  protected var keys                             = 0
  protected var before: WalletState              = null
  protected var lastOutcome: Outcome             = null
  protected var lastEvents: Vector[WalletEvent]  = Vector.empty
  protected var firstOutcome: Outcome            = null
  protected var lastBetKey: Option[String]       = None
  protected var betOutcome: Outcome              = null
  /** The record "the record" means: the last one a Given described. */
  protected var focus: Option[String]            = None
  protected var switches                         = BetSwitches()
  protected var brandTerms                       = TurnoverTerms.Default
  protected var maxConcurrentAwards: Option[Int] = None

  override def beforeEach(context: BeforeEach): Unit =
    currency = "EUR"; seed = Seed(); clawbacks = Map.empty; opened = false; noWallet = false; kit = null
    clock = t0; keys = 0; before = null; lastOutcome = null; lastEvents = Vector.empty; firstOutcome = null
    lastBetKey = None; betOutcome = null; focus = None
    switches = BetSwitches(); brandTerms = TurnoverTerms.Default; maxConcurrentAwards = None

  protected def nextKey(prefix: String): String = { keys += 1; s"$prefix-$keys" }
  protected def now(): Instant                  = { clock = clock.plusSeconds(1); clock }

  protected def walletKit: Kit =
    if kit == null then kit = EventSourcedTestKit.of(WalletEntity, s"player-1/$currency")
    if !opened && !noWallet then
      opened = true
      val records = seed.records.map { a =>
        clawbacks.get(a.id).fold(a)(c => a.copy(baseline = Some(Clawback.Baseline(seed.real - c, 0L))))
      }
      val opening = kit.call(WalletEntity.openMigrated)(OpenMigrated("open", seed.real, seed.pending, records, 0L, 0,
        seed.restrictedWithoutRecords, clock, seed.bets, seed.withdrawals, seed.pendingRequestId,
        depositPool = Some(seed.openingPools._1), convertedPool = Some(seed.openingPools._2)))
      assert(opening.replyValue.accepted, opening.replyValue.toString)
    kit

  protected def state: WalletState = walletKit.currentState

  /** A Given that describes the wallet must come before the wallet is used. */
  protected def seeding(f: Seed => Seed): Unit =
    assert(!opened, "a Given that describes the wallet came after the wallet was used")
    seed = f(seed)

  /** One record, active unless said otherwise; "the record" from now on. */
  protected def record(name: String, balance: Long, status: String = Status.Active, priority: Long = 0L,
      edit: Record => Record = identity): AwardedRecord =
    val r = edit(Record(id = s"rf-$name", grantRef = s"grant-$name", status = status, priority = priority,
      grantedAt = t0.plusSeconds(priority).toString, balance = balance,
      // Far from met, so no bet converts a record unless the scenario says it does.
      releaseRequirement = 1000000L))
    focus = Some(r.id)
    AwardedRecord(r, s"bonus-$name", None, t0.plusSeconds(priority), None)

  protected def addRecord(a: AwardedRecord): Unit = seeding(s => s.copy(records = s.records :+ a))

  protected def editSeedRecord(id: String)(f: Record => Record): Unit =
    seeding(s => s.copy(records = s.records.map(a => if a.id == id then a.copy(record = f(a.record)) else a)))

  protected def recordState(id: String): Record =
    state.records.find(_.id == id).map(_.record).getOrElse(fail(s"no record $id"))

  protected def recordBefore(id: String): Record =
    before.records.find(_.id == id).map(_.record).getOrElse(fail(s"no record $id before"))

  protected def focusId: String = focus.getOrElse(fail("no record was described"))

  protected def focused: Record = recordState(focusId)

  /** Runs a command as the scenario's action: the state before it, the outcome and the events it wrote. */
  protected def act(call: => Result): Result =
    before = walletKit.currentState
    val r = call
    lastOutcome = r.outcome
    lastEvents = r.events
    r

  protected def run(r: CommandResult[WalletState, WalletEvent, Outcome]): Result = Result(r.replyValue, r.events)

  protected def bet(amount: Long, round: String, game: String = "slot-a", allowNegative: Boolean = false): Result =
    val key = nextKey("tx")
    lastBetKey = Some(key)
    val r = run(walletKit.call(WalletEntity.placeBet)(PlaceBet(key, round, game, "s1", "pragmatic", "slots", amount, allowNegative, switches, now())))
    betOutcome = r.outcome
    r

  protected def deposit(amount: Long, key: String): Result =
    run(walletKit.call(WalletEntity.deposit)(Deposit(key, amount, "dreamzpay", "t1", "dreamzpay", "card", brandTerms, now())))

  protected def hold(amount: Long, request: String): Result =
    run(walletKit.call(WalletEntity.hold)(Hold(request, amount, playerConfirmed = true, now())))

  protected def adjust(target: String, amount: Long, reason: String): Result =
    run(walletKit.call(WalletEntity.adjust)(Adjust(nextKey("adj"), target, amount, reason, "goodwill", "op-7", now())))

  /** The view's rows for what the last action wrote: "a … transaction is written". */
  protected def rowsWritten = lastEvents.flatMap(TransactionRows.of(state.walletId, _))

  protected def refusedAs(phrase: String): Unit =
    val code = refusals.getOrElse(phrase, fail(s"no refusal is named '$phrase'"))
    assertEquals(lastOutcome.code, Some(code), lastOutcome.toString)

  protected def long(s: String): Long = s.toLong

  // ── steps more than one feature uses ──────────────────────────────────────

  Given("a player with a wallet in {string}") { (c: String) => currency = c }

  Given("a real balance of {string}") { (r: String) => seeding(_.copy(real = long(r))) }

  Given("the wallet has a real balance of {string}") { (r: String) => seeding(_.copy(real = long(r))) }

  Given("an active restricted funds record of {string}") { (b: String) => addRecord(record("r1", long(b))) }

  Given("no active restricted funds record") { () => () }

  Given("no active restricted funds record and a real balance of {string}") { (r: String) => seeding(_.copy(real = long(r))) }

  When("the player places a bet of {string} in round {string}") { (a: String, r: String) => act(bet(long(a), r)): Unit }

  When("a win of {string} is credited for round {string}") { (a: String, r: String) =>
    act(run(walletKit.call(WalletEntity.creditWin)(CreditWin(nextKey("win"), r, long(a), now())))): Unit
  }

  When("round {string} is settled as a loss") { (r: String) =>
    act(run(walletKit.call(WalletEntity.settleLoss)(SettleLoss(nextKey("loss"), r, now())))): Unit
  }

  Then("the real balance is {string}") { (r: String) => assertEquals(state.realBalance, long(r)) }

  Then("the real balance rises by {string}") { (r: String) => assertEquals(state.realBalance - before.realBalance, long(r)) }

  Then("the restricted balance is {string}") { (r: String) => assertEquals(state.restrictedBalance, long(r)) }

  Then("it is refused as {string}") { (phrase: String) => refusedAs(phrase) }

  Then("the bet is refused as {string}") { (phrase: String) => refusedAs(phrase) }

  Then("the bet's wallet type is {string}") { (w: String) => assertEquals(lastOutcome.walletType, Some(w), lastOutcome.toString) }

  Then("the record is forfeited") { () =>
    // A refused hold applies nothing: it names the records it would have forfeited.
    if lastOutcome != null && lastOutcome.code.contains("WITHDRAWAL_INSUFFICIENT_AFTER_BONUS_FORFEIT") then
      assert(lastOutcome.details.get("forfeited").exists(_.split(',').contains(focusId)), lastOutcome.toString)
    else assertEquals(focused.status, Status.Forfeited)
  }

  Then("no balance changes") { () =>
    assertEquals((state.realBalance, state.restrictedBalance), (before.realBalance, before.restrictedBalance))
  }

  Then("no transaction is written") { () => assertEquals(rowsWritten, Vector.empty) }

  Then("a {string} transaction is written") { (kind: String) =>
    assert(rowsWritten.exists(_.`type` == kind), rowsWritten.toString)
  }
