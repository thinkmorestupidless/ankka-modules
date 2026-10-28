package io.eitheror.casino.wallet.entity

import io.eitheror.casino.contract.money.{Key, KeyedOutcomes}
import io.eitheror.casino.wallet.funding.{Pools, Record, RecordUpdate, Resolver, Status, Wallet}

import java.time.{Duration, Instant}

/**
 * One wallet: a player's money in one currency (spec 002 FR-001, data-model.md). The only place a
 * check and the change it guards happen together, so every rule is enforced against this value.
 *
 * Bounded (research R4, R17): records are tens per wallet; open bets are the open rounds; settled
 * bets are the last `SettledBound`, for a late rollback; deposits are the last 30 days; the key store
 * is the last `RecentKeys` keys with their digest and outcome, in the order seen. The lifetime record
 * of a key is the caller's workflow (FR-002); the wallet's keys are what makes a re-run of its step,
 * or two duplicates arriving together, move money once.
 */
final case class WalletState(
    walletId: String,
    created: Boolean = false,
    realBalance: Long = 0L,
    depositTurnoverPending: Long = 0L,
    /** A restricted balance the migration carried over with no record: refuses the bet path (FR-014). */
    restrictedWithoutRecords: Long = 0L,
    turnoverOverride: Option[TurnoverTerms] = None,
    records: Vector[AwardedRecord] = Vector.empty,
    openBets: Vector[Bet] = Vector.empty,
    settledBets: Vector[Bet] = Vector.empty,
    withdrawals: Map[String, WithdrawalRequest] = Map.empty,
    pendingRequestId: Option[String] = None,
    recentDeposits: Vector[DepositRecord] = Vector.empty,
    totalDeposits: Long = 0L,
    depositOrdinal: Int = 0,
    seen: KeyedOutcomes[Outcome] = KeyedOutcomes(),
    /** The keys of `seen`, oldest first: what `remember` evicts past `RecentKeys`. */
    keyOrder: Vector[String] = Vector.empty,
    /**
     * Real balance as two pools (spec 025 FR-001), set from each event's `after` values, never
     * computed by the fold. They sum to `realBalance`: every pool step checks that before and after
     * it moves anything, and refuses (`POOLS_INVARIANT`) rather than write a drifted pair.
     */
    depositPool: Long = 0L,
    convertedPool: Long = 0L
):
  def playerId: String = walletId.takeWhile(_ != '/')
  def currency: String = walletId.drop(walletId.indexOf('/') + 1)

  /** Open records in resolver order: priority, then granted time (FR-020). */
  def openRecords: Vector[Record] =
    Resolver.ordered(records.iterator.map(_.record).filter(r => Status.isOpen(r.status)).toVector)

  /** Σ open records' balances, never stored (FR-021), plus any migrated balance without a record. */
  def restrictedBalance: Long = openRecords.foldLeft(restrictedWithoutRecords)(_ + _.balance)

  def pools: Pools = Pools(depositPool, convertedPool)

  def fundingWallet: Wallet = Wallet(realBalance, depositTurnoverPending, restrictedBalance, Some(pools))

  /**
   * Deposit turnover closed because no deposit-origin money is left and no round is open (spec 025
   * FR-003b; the original's `PlanCloseTurnover` with `AttributableCash`): nothing pending, every
   * pending deposit closed.
   */
  private[entity] def closingTurnover(closed: Boolean): WalletState =
    if !closed then this
    else copy(depositTurnoverPending = 0L, recentDeposits = recentDeposits.map(d => if d.status == "PENDING" then d.copy(status = "CLOSED") else d))

  /** Whether a settlement or adjustment leaving `depositAfter` and `openBetsAfter` closes deposit turnover (FR-003b). */
  def closesTurnover(depositAfter: Long, openBetsAfter: Int): Boolean =
    depositTurnoverPending > 0 && depositAfter == 0 && openBetsAfter == 0

  /** The fold's one rule for the pools: the event's `after` values. */
  private[entity] def withPools(depositAfter: Long, convertedAfter: Long): WalletState =
    copy(depositPool = depositAfter, convertedPool = convertedAfter)

  def record(id: String): Option[AwardedRecord] = records.find(_.id == id)

  def recordByGrant(grantRef: String): Option[AwardedRecord] = records.find(_.grantRef == grantRef)

  def openBetsOf(roundKey: String): Vector[Bet] = openBets.filter(_.roundKey == roundKey)

  def depositSums(asOf: Instant): DepositSums =
    def since(d: Duration) = recentDeposits.iterator.filter(r => r.confirmedAt.isAfter(asOf.minus(d)) && !r.confirmedAt.isAfter(asOf)).map(_.amount).sum
    DepositSums(since(Duration.ofHours(24)), since(Duration.ofDays(7)), since(Duration.ofDays(30)))

  // ── folds ─────────────────────────────────────────────────────────────────

  private[entity] def withUpdates(updates: Vector[RecordUpdate]): WalletState =
    if updates.isEmpty then this
    else
      val byId = updates.map(u => u.id -> u).toMap
      copy(records = records.map(a => byId.get(a.id).fold(a)(u => a.copy(record = u.applyTo(a.record)))))

  private[entity] def remember(key: String, digest: String, outcome: Outcome): WalletState =
    val recorded = seen.record(Key.unsafe(key), digest, outcome)
    val order    = if seen.seen.contains(key) then keyOrder else keyOrder :+ key
    val evicted  = order.dropRight(WalletState.RecentKeys)
    copy(seen = KeyedOutcomes(recorded.seen -- evicted), keyOrder = order.drop(evicted.size))

  private[entity] def settle(betKeys: Vector[String], as: String): WalletState =
    val (closing, staying) = openBets.partition(b => betKeys.contains(b.key))
    val closed             = closing.map(_.copy(status = as))
    // A late rollback voids an already-settled bet: replace it where it lies.
    val resettled = settledBets.map(b => if betKeys.contains(b.key) then b.copy(status = as) else b)
    copy(openBets = staying, settledBets = (resettled ++ closed).takeRight(WalletState.SettledBound))

  private[entity] def withDeposit(d: DepositRecord): WalletState =
    val window = d.confirmedAt.minus(Duration.ofDays(30))
    copy(recentDeposits = (recentDeposits :+ d).filter(_.confirmedAt.isAfter(window)))

object WalletState:
  /**
   * How far back a late rollback can find its bet. Assumed (research R1): ST8's cancel horizon is
   * undocumented in the original; about 2.4 hours at the heaviest autoplay pace. Older is
   * `ROUND_NOT_FOUND`, which spec 003 handles.
   */
  val SettledBound: Int = 1000

  /** The keys the wallet remembers (FR-002, R17): a crash-and-resume of a caller's step, with room to spare. */
  val RecentKeys: Int = 1000
