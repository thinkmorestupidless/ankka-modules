package io.eitheror.casino.wallet.funding

/**
 * The deposit-origin and converted-origin amounts of one movement of real balance (spec 025): for a
 * credit, where the money landed; for a debit, where it came from. They sum to the amount moved.
 */
final case class Parts(deposit: Long, converted: Long):
  def total: Long             = Math.addExact(deposit, converted)
  def +(other: Parts): Parts  = Parts(Math.addExact(deposit, other.deposit), Math.addExact(converted, other.converted))

object Parts:
  val zero: Parts                     = Parts(0L, 0L)
  def ofDeposit(amount: Long): Parts   = Parts(amount, 0L)
  def ofConverted(amount: Long): Parts = Parts(0L, amount)

/**
 * A wallet's real balance as two pools (spec 025 FR-001): the deposit pool, never below zero, and
 * the converted pool, below zero only by an allow-negative bet's shortfall. They sum to real balance.
 */
final case class Pools(deposit: Long, converted: Long):
  def total: Long                 = Math.addExact(deposit, converted)
  def credit(parts: Parts): Pools = Pools(Math.addExact(deposit, parts.deposit), Math.addExact(converted, parts.converted))
  def debit(parts: Parts): Pools  = Pools(Math.subtractExact(deposit, parts.deposit), Math.subtractExact(converted, parts.converted))

/**
 * The pool step: the original's `internal/pools/apply.go` (one rule per movement type) and
 * `internal/funding/poolstep.go` (the invariant check every resolver runs before a plan is allowed
 * out) ported. Every movement of real balance asks it how the same amount moves across the pools;
 * the resolver has already decided the movement, so the step only attributes it (FR-014).
 *
 * Two differences from the original, both because there is no unstamped wallet here (spec 025
 * clarification 2026-10-28): a win on a bet with no recorded parts is not split by the pools' ratio
 * (every bet records its parts; a win with no bet at all is converted), and a debit never treats a
 * negative converted pool as money to draw, where the original's `debit` would make the deposit part
 * larger than the amount.
 */
object Pools:
  val zero: Pools = Pools(0L, 0L)

  /** A wallet the original never stamped, as the migration opens it (FR-011). */
  def allConverted(real: Long): Pools = Pools(0L, real)

  private def deny(message: String): Deny = Deny(Codes.PoolsInvariant, s"real-money pools: $message")

  private val Drifted = "deposit+converted would not equal real_balance"

  /** The pools a step starts from: summing to real balance, deposit never negative. */
  def consistent(pools: Pools, realBalance: Long): Either[Deny, Pools] =
    Either.cond(pools.deposit >= 0 && pools.total == realBalance, pools, deny(Drifted))

  /** The pools a step ends with: the same rule against the plan's new real balance. */
  def checked(pools: Pools, newReal: Long): Either[Deny, Pools] = consistent(pools, newReal)

  /** apply.go `credit`. */
  def credit(p: Pools, parts: Parts): Either[Deny, (Pools, Parts)] =
    if parts.deposit < 0 || parts.converted < 0 then Left(deny("negative credit")) else Right((p.credit(parts), parts))

  /**
   * apply.go `debit`: `amount` from converted first (`convertedFirst`) or deposit first; more than
   * both pools hold is refused. A negative converted pool holds nothing to draw.
   */
  def debit(p: Pools, amount: Long, convertedFirst: Boolean): Either[Deny, (Pools, Parts)] =
    val available = math.max(0L, p.converted)
    if amount < 0 then Left(deny("negative debit"))
    else if amount > Math.addExact(p.deposit, available) then Left(deny("debit exceeds deposit+converted"))
    else
      val parts =
        if convertedFirst then
          val c = math.min(amount, available)
          Parts(amount - c, c)
        else
          val d = math.min(amount, p.deposit)
          Parts(d, amount - d)
      Right((p.debit(parts), parts))

  /**
   * A bet's real leg (apply.go `TypeBet`, poolstep.go `stepBetPools`): the conversions the bet
   * completes join the converted pool first, then the leg is taken converted first. When the caller
   * allows real balance to go negative, the shortfall beyond both pools is charged to the converted
   * pool, which may go negative; the deposit pool never does.
   */
  def bet(p: Pools, realLeg: Long, convertedIn: Long, allowNegative: Boolean): Either[Deny, (Pools, Parts)] =
    val withConversions = p.credit(Parts.ofConverted(convertedIn))
    if !allowNegative then debit(withConversions, realLeg, convertedFirst = true)
    else
      val fromConverted = math.min(realLeg, math.max(0L, withConversions.converted))
      val fromDeposit   = math.min(realLeg - fromConverted, withConversions.deposit)
      val parts         = Parts(fromDeposit, realLeg - fromDeposit)
      Right((withConversions.debit(parts), parts))

  /**
   * A win's real share (apply.go `winSplit`): the orphaned part (record money a gone record sent to
   * real) is converted whole; the rest is split in the ratio of the bet's recorded parts, the deposit
   * part rounded down. A bet with no real leg, or no bet at all, gives the rest to converted.
   */
  def winSplit(realShare: Long, orphaned: Long, bet: Parts): Parts =
    val orphan = math.min(math.max(orphaned, 0L), realShare)
    val rest   = realShare - orphan
    val leg    = bet.total
    val deposit = if rest == 0 || leg <= 0 then 0L else mulDiv(rest, bet.deposit, leg)
    Parts(deposit, realShare - deposit)

  /**
   * What a return of money taken from the pools restores (poolstep's return and reversal): the
   * recorded parts when they are exactly the amount credited, else the whole amount to deposit, as
   * the original does for a legacy or mismatched request (`TestReturnPools_LegacyAndMismatchedPartsGoToDeposit`).
   */
  def restored(recorded: Parts, amount: Long): Parts =
    if recorded.deposit >= 0 && recorded.converted >= 0 && recorded.total == amount then recorded else Parts.ofDeposit(amount)

  /** floor(a × b ÷ c) for a, b ≥ 0 and c > 0, without 64-bit overflow (apply.go `mulDiv`). */
  def mulDiv(a: Long, b: Long, c: Long): Long =
    if a <= 0 || b <= 0 then 0L
    else
      val hi = Math.multiplyHigh(a, b)
      if hi == 0 && a * b >= 0 then (a * b) / c
      else (BigInt(a) * BigInt(b) / BigInt(c)).toLong
