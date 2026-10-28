package io.eitheror.casino.wallet.funding

import scala.collection.mutable

/**
 * The funding resolver: the original's `internal/funding` (placebet.go, creditwin.go, rollback.go,
 * loss.go, close.go, adjust.go, withdraw.go, rules.go, helpers.go, projection.go) ported line for line.
 *
 * Every function takes the wallet's funding slice and its open records in resolver order and returns
 * a plan or a refusal; none reads or writes anything else. Working copies are arrays, as the Go
 * mutates slices, so each step can be compared with its original.
 */
object Resolver:

  // ── helpers.go ────────────────────────────────────────────────────────────

  private def indexById(recs: IndexedSeq[Record]): Map[String, Int] = recs.iterator.map(_.id).zipWithIndex.toMap

  def sumBalances(recs: Iterable[Record]): Long = recs.foldLeft(0L)(_ + _.balance)

  def sumActiveBalances(recs: Iterable[Record]): Long = recs.filter(_.status == Status.Active).foldLeft(0L)(_ + _.balance)

  def fundableRecords(recs: Seq[Record]): Vector[Record] = recs.filter(r => r.status == Status.Active && r.balance > 0).toVector

  /** The first ACTIVE record: the primary for funding order, contribution and conversion. */
  def firstActiveIndex(recs: Seq[Record]): Int = recs.indexWhere(_.status == Status.Active)

  def legAmount(legs: Seq[Leg], recordId: String): Long = legs.find(_.recordId == recordId).fold(0L)(_.amount)

  def legTotal(legs: Seq[Leg]): Long = legs.foldLeft(0L)(_ + _.amount)

  private def drawInOrder(fundable: Seq[Record], amount: Long): Vector[Leg] =
    val legs      = Vector.newBuilder[Leg]
    var remaining = amount
    val it        = fundable.iterator
    while remaining > 0 && it.hasNext do
      val r   = it.next()
      val leg = math.min(remaining, r.balance)
      if leg > 0 then
        legs += Leg(r.id, leg)
        remaining -= leg
    legs.result()

  private def settlementGuard(w: Wallet, recs: Seq[Record], message: String): Option[Deny] =
    Option.when(recs.isEmpty && w.restrictedBalance > 0)(Deny(Codes.RestrictedFundsUnmigrated, message))

  /** "real" | "bonus" | "split" | "" from a leg shape. */
  def fundingWalletType(realLeg: Long, recordLegs: Seq[Leg]): String =
    if realLeg > 0 && recordLegs.nonEmpty then "split"
    else if recordLegs.nonEmpty then "bonus"
    else if realLeg != 0 then "real"
    else ""

  private def updatesFor(working: Seq[Record], touched: collection.Set[String], closed: String => Boolean = _ => false): Vector[RecordUpdate] =
    working.iterator.filter(r => touched.contains(r.id)).map(r => RecordUpdate.from(r, closed(r.id))).toVector

  // ── projection.go, parse.go ───────────────────────────────────────────────

  def project(recs: Seq[Record], closedLastAs: String): Projection =
    val open = recs.filter(r => Status.isOpen(r.status))
    if open.isEmpty && closedLastAs.isEmpty then Projection() else Projection(present = true, sumBalances(open))

  /** Resolver order: priority ascending, then granted time ascending, stable (SortRecords). */
  def ordered(recs: Seq[Record]): Vector[Record] = recs.toVector.sortBy(r => (r.priority, r.grantedAt))

  private def anyOpen(recs: Seq[Record]): Boolean = recs.exists(r => Status.isOpen(r.status))

  // ── poolstep.go ───────────────────────────────────────────────────────────

  /**
   * The pool step every movement of real balance runs (spec 025): from the wallet's pools, checked
   * to sum to its real balance, to the new pools, checked to sum to `newReal`; `none` on a wallet
   * without pools (only the tables that predate them). A pair that does not sum is `POOLS_INVARIANT`.
   */
  private def poolStep[A](w: Wallet, newReal: Long, none: A)(step: Pools => Either[Deny, (Pools, A)]): Either[Deny, (A, Option[Pools])] =
    w.pools match
      case None => Right((none, None))
      case Some(p) =>
        for
          start          <- Pools.consistent(p, w.realBalance)
          (moved, parts) <- step(start)
          end            <- Pools.checked(moved, newReal)
        yield (parts, Some(end))

  // ── rules.go ──────────────────────────────────────────────────────────────

  def rate(r: Record, g: Game, defaultRate: Long): Long =
    r.contributionOverrides.get("game:" + g.gameId)
      .orElse(r.contributionOverrides.get("provider:" + g.provider))
      .orElse(r.contributionOverrides.get("category:" + g.category))
      .orElse(r.contributionDefault)
      .getOrElse(defaultRate)

  private def scopeKeys(g: Game): Seq[String] = Seq("game:" + g.gameId, "provider:" + g.provider, "category:" + g.category)

  def inScope(r: Record, g: Game): Boolean =
    val keys = scopeKeys(g)
    if keys.exists(r.scopeExclude.contains) then false
    else if r.scopeInclude.isEmpty then true
    else keys.exists(r.scopeInclude.contains)

  /** The smallest max stake over ACTIVE records and its enforcement; a tie favours "block". */
  def bindingMaxStake(recs: Seq[Record]): (Option[Long], String) =
    var m: Option[Long] = None
    var enforcement     = ""
    recs.foreach { r =>
      if r.status == Status.Active then
        r.maxStake.foreach { s =>
          if m.forall(s < _) then
            m = Some(s)
            enforcement = r.maxStakeEnforcement
          else if m.contains(s) && r.maxStakeEnforcement == "block" then enforcement = "block"
        }
    }
    (m, enforcement)

  // ── placebet.go ───────────────────────────────────────────────────────────

  def placeBet(w: Wallet, recs: Vector[Record], s: Stake, f: Flags): Either[Deny, Plan] =
    // Step 1 — guard.
    if recs.isEmpty && w.restrictedBalance > 0 then
      return Left(Deny(Codes.RestrictedFundsUnmigrated, "restricted balance present with no open restricted-funds record"))

    // Step 2 — scope block.
    if recs.exists(r => r.status == Status.Active && r.scopeViolationMode == "block" && !inScope(r, s.game)) then
      return Left(Deny(Codes.ScopeBlocked, "stake is out of scope for a blocking restricted-funds record"))

    // Step 3 — max stake.
    var contribBase = s.amount
    bindingMaxStake(recs) match
      case (Some(m), enforcement) if s.amount > m =>
        if enforcement == "block" then return Left(Deny(Codes.MaxStakeExceeded, "stake exceeds the binding max_stake"))
        if enforcement == "cap" then contribBase = m
      case _ => ()

    // Step 4 — funding.
    val order    = fundingOrder(recs)
    val fundable = fundableRecords(recs)
    val (fundedReal, fundedLegs, funded) = fundBet(order, w.realBalance, fundable, s.amount, f.partialSplits)

    // Step 5 — insufficient / allow_negative.
    var realLeg    = fundedReal
    var recordLegs = fundedLegs
    var negative   = false
    if !funded then
      if !f.allowNegative then return Left(Deny(Codes.InsufficientFunds, Messages.InsufficientFunds))
      realLeg = s.amount; recordLegs = Vector.empty; negative = true

    // Step 6 — contributions.
    val primaryIdx    = firstActiveIndex(recs)
    val primaryId     = if primaryIdx >= 0 then recs(primaryIdx).id else ""
    val contributions = computeContributions(order, recs, primaryId, realLeg, recordLegs, contribBase, s, f, negative)

    val working = recs.toArray
    val idx     = indexById(recs)
    recordLegs.foreach(l => idx.get(l.recordId).foreach(i => working(i) = working(i).copy(balance = working(i).balance - l.amount)))

    val (balanceBefore, balanceAfter) =
      if realLeg > 0 || primaryIdx < 0 then (w.realBalance, w.realBalance - realLeg)
      else (recs(primaryIdx).balance, working(primaryIdx).balance)

    contributions.foreach(c => idx.get(c.recordId).foreach(i => working(i) = working(i).copy(releasedProgress = working(i).releasedProgress + c.amount)))

    // Step 7 — conversion of every ACTIVE record whose progress meets its requirement.
    val converting     = Vector.newBuilder[String]
    var convertedTotal = 0L
    for i <- working.indices do
      val r = working(i)
      if r.status == Status.Active && r.releasedProgress >= r.releaseRequirement then
        converting += r.id
        convertedTotal += r.balance
        working(i) = r.copy(
          remainingAllowance = r.maxConvertible,
          maxConvertible = 0,
          balance = 0,
          status = Status.ConvertedPendingCap,
          releasedProgress = 0,
          releaseRequirement = 0,
          depositsSinceRelease = 0
        )
    val converted = converting.result()

    // Step 8 — open round count on records that drew a leg; the records touched.
    val touched = mutable.LinkedHashSet.empty[String]
    recordLegs.foreach { l =>
      touched += l.recordId
      idx.get(l.recordId).foreach(i => working(i) = working(i).copy(openRoundCount = working(i).openRoundCount + 1))
    }
    contributions.foreach(touched += _.recordId)
    converted.foreach(touched += _)

    // Step 9 — the pools (spec 025 FR-003): conversions join converted, then the leg converted first.
    val newReal = w.realBalance - realLeg + convertedTotal
    poolStep(w, newReal, Parts.zero)(Pools.bet(_, realLeg, convertedTotal, f.allowNegative)).map { (parts, pools) =>
      Plan(
        realLeg = realLeg,
        recordLegs = recordLegs,
        contributions = contributions,
        converting = converted,
        recordUpdates = updatesFor(working.toVector, touched),
        newRealBalance = newReal,
        balanceBefore = balanceBefore,
        balanceAfter = balanceAfter,
        walletType = fundingWalletType(realLeg, recordLegs),
        projection = project(working.toVector, ""),
        parts = parts,
        newPools = pools
      )
    }

  private def fundingOrder(recs: Seq[Record]): String =
    val i = firstActiveIndex(recs)
    if i < 0 then "" else recs(i).fundingOrder

  private def fundBet(order: String, real: Long, fundable: Vector[Record], amount: Long, partialSplits: Boolean): (Long, Vector[Leg], Boolean) =
    order match
      case "restricted_first" => fundRestrictedFirst(real, fundable, amount, partialSplits)
      case "proportional"     => fundProportional(real, fundable, amount)
      case _                  => fundRealFirst(real, fundable, amount, partialSplits)

  private def fundRealFirst(real: Long, fundable: Vector[Record], amount: Long, partialSplits: Boolean): (Long, Vector[Leg], Boolean) =
    val r = sumBalances(fundable)
    if real >= amount then (amount, Vector.empty, true)
    else if partialSplits && real > 0 && real + r >= amount then (real, drawInOrder(fundable, amount - real), true)
    else if r >= amount then (0L, drawInOrder(fundable, amount), true)
    else (0L, Vector.empty, false)

  private def fundRestrictedFirst(real: Long, fundable: Vector[Record], amount: Long, partialSplits: Boolean): (Long, Vector[Leg], Boolean) =
    val r = sumBalances(fundable)
    if r >= amount then (0L, drawInOrder(fundable, amount), true)
    else if partialSplits && r > 0 && real >= amount - r then (amount - r, drawInOrder(fundable, r), true)
    else if real >= amount then (amount, Vector.empty, true)
    else (0L, Vector.empty, false)

  private def fundProportional(real: Long, fundable: Vector[Record], amount: Long): (Long, Vector[Leg], Boolean) =
    val r     = sumBalances(fundable)
    val total = real + r
    if total < amount then (0L, Vector.empty, false)
    else if total == 0 then (0L, Vector.empty, true)
    else
      var realLeg     = math.min(Math.multiplyExact(amount, real) / total, real)
      var recordTotal = amount - realLeg
      if recordTotal > r then
        recordTotal = r
        realLeg = amount - recordTotal
      (realLeg, drawInOrder(fundable, recordTotal), true)

  private def computeContributions(order: String, recs: Seq[Record], primaryId: String, realLeg: Long, recordLegs: Vector[Leg],
      contribBase: Long, s: Stake, f: Flags, negative: Boolean): Vector[Leg] =
    if negative then return Vector.empty
    val allOpenBase = realLeg + (if f.bonusWageringCounts then legTotal(recordLegs) else 0L)
    recs.iterator.filter(_.status == Status.Active).flatMap { r =>
      var base =
        if order == "combined" && r.id == primaryId then contribBase
        else if r.releaseProgressScope == "all_open" then allOpenBase
        else
          val own = if f.bonusWageringCounts then legAmount(recordLegs, r.id) else 0L
          own + (if r.id == primaryId then realLeg else 0L)
      if !inScope(r, s.game) then base = 0
      if base > contribBase then base = contribBase
      val contribution = Math.multiplyExact(base, rate(r, s.game, f.defaultRate)) / 100
      Option.when(contribution != 0)(Leg(r.id, contribution))
    }.toVector

  // ── creditwin.go ──────────────────────────────────────────────────────────

  def creditWin(w: Wallet, recs: Vector[Record], legs: Legs, amount: Long): Either[Deny, WinPlan] =
    settlementGuard(w, recs, "restricted balance present with no open restricted-funds record to credit the win to").toLeft {
      val idx     = indexById(recs)
      val working = recs.toArray
      val (realFromAllocation, rawShares) = winAllocation(recs, legs) match
        case "all_restricted" => allocateAllRestricted(recs, legs, amount)
        case _                => allocateFollowSource(legs, amount)
      var realShare = realFromAllocation
      val kept      = Vector.newBuilder[Leg]
      val orphaned  = Vector.newBuilder[Leg]
      rawShares.foreach { sh =>
        idx.get(sh.recordId) match
          case Some(i) if recs(i).status == Status.Active =>
            kept += sh
            working(i) = working(i).copy(balance = working(i).balance + sh.amount)
          case _ =>
            realShare += sh.amount
            orphaned += sh
      }
      val touched   = decrementRounds(working, idx, legs)
      val keptV     = kept.result()
      val orphanedV = orphaned.result()
      WinPlan(
        realShare = realShare,
        recordShares = keptV,
        newRealBalance = w.realBalance + realShare,
        recordUpdates = updatesFor(working.toVector, touched),
        walletType = fundingWalletType(legs.real, legs.records),
        splitRealAmount = realShare,
        splitBonusAmount = legTotal(keptV),
        orphanedToReal = orphanedV,
        projection = project(working.toVector, "")
      )
    }.flatMap { plan =>
      // The pools (spec 025 FR-004): orphaned shares converted, the rest in the bet's ratio.
      val parts = Pools.winSplit(plan.realShare, legTotal(plan.orphanedToReal), legs.realParts.getOrElse(Parts.zero))
      poolStep(w, plan.newRealBalance, Parts.zero)(Pools.credit(_, parts)).map((p, pools) => plan.copy(parts = p, newPools = pools))
    }

  /** open_round_count − 1 (floored) on every funding record still present; the ids touched. */
  private def decrementRounds(working: Array[Record], idx: Map[String, Int], legs: Legs): mutable.LinkedHashSet[String] =
    val touched = mutable.LinkedHashSet.empty[String]
    legs.records.foreach { l =>
      idx.get(l.recordId).foreach { i =>
        if working(i).openRoundCount > 0 then working(i) = working(i).copy(openRoundCount = working(i).openRoundCount - 1)
        touched += l.recordId
      }
    }
    touched

  private def winAllocation(recs: Seq[Record], legs: Legs): String =
    val funding = legs.records.map(_.recordId).toSet
    recs.find(r => r.status == Status.Active && funding.contains(r.id)).fold("")(_.winAllocation)

  private def allocateFollowSource(legs: Legs, amount: Long): (Long, Vector[Leg]) =
    val total = legs.real + legTotal(legs.records)
    if total == 0 then (amount, Vector.empty)
    else
      val realShare = Math.multiplyExact(amount, legs.real) / total
      val remaining = amount - realShare
      var allocated = 0L
      val shares = legs.records.zipWithIndex.map { (l, i) =>
        val share =
          if i == legs.records.size - 1 then remaining - allocated
          else
            val s = Math.multiplyExact(amount, l.amount) / total
            allocated += s
            s
        Leg(l.recordId, share)
      }
      (realShare, shares)

  private def allocateAllRestricted(recs: Seq[Record], legs: Legs, amount: Long): (Long, Vector[Leg]) =
    val funding = legs.records.map(_.recordId).toSet
    recs.find(r => funding.contains(r.id)).fold((amount, Vector.empty[Leg]))(r => (0L, Vector(Leg(r.id, amount))))

  // ── rollback.go ───────────────────────────────────────────────────────────

  def rollbackBet(w: Wallet, recs: Vector[Record], legs: Legs, contribs: Vector[Leg]): Either[Deny, RollbackPlan] =
    settlementGuard(w, recs, "restricted balance present with no open restricted-funds record to restore the rollback to").toLeft {
      val idx          = indexById(recs)
      val working      = recs.toArray
      var realRestored = legs.real
      val restored     = Vector.newBuilder[Leg]
      legs.records.foreach { l =>
        idx.get(l.recordId) match
          case Some(i) if recs(i).status == Status.Active =>
            restored += l
            working(i) = working(i).copy(balance = working(i).balance + l.amount)
          case _ => realRestored += l.amount
      }
      val reversed = Vector.newBuilder[Leg]
      contribs.foreach { c =>
        idx.get(c.recordId) match
          case Some(i) if recs(i).status == Status.Active =>
            working(i) = working(i).copy(releasedProgress = math.max(0L, working(i).releasedProgress - c.amount))
            reversed += Leg(c.recordId, c.amount)
          case _ => ()
      }
      val touched   = decrementRounds(working, idx, legs)
      val reversedV = reversed.result()
      reversedV.foreach(touched += _.recordId)
      RollbackPlan(
        realRestored = realRestored,
        recordRestored = restored.result(),
        progressReversed = reversedV,
        newRealBalance = w.realBalance + realRestored,
        recordUpdates = updatesFor(working.toVector, touched),
        walletType = fundingWalletType(legs.real, legs.records),
        projection = project(working.toVector, "")
      )
    }.flatMap { plan =>
      // The pools (spec 025 FR-005): the bet's recorded parts, and a gone record's leg to converted.
      val stake = legs.realParts.filter(_.total == legs.real).getOrElse(Parts.ofConverted(legs.real))
      val parts = stake + Parts.ofConverted(plan.realRestored - legs.real)
      poolStep(w, plan.newRealBalance, Parts.zero)(Pools.credit(_, parts)).map((p, pools) => plan.copy(parts = p, newPools = pools))
    }

  // ── loss.go ───────────────────────────────────────────────────────────────

  def settleLoss(w: Wallet, recs: Vector[Record], legs: Legs): Either[Deny, LossPlan] =
    settlementGuard(w, recs, "restricted balance present with no open restricted-funds record to settle against").toLeft {
      val idx       = indexById(recs)
      val working   = recs.toArray
      val forfeited = Vector.newBuilder[String]
      val touched   = mutable.LinkedHashSet.empty[String]
      legs.records.foreach { l =>
        idx.get(l.recordId).foreach { i =>
          touched += l.recordId
          if working(i).openRoundCount > 0 then working(i) = working(i).copy(openRoundCount = working(i).openRoundCount - 1)
          val r = working(i)
          if r.status == Status.Active && r.balance == 0 && r.openRoundCount == 0 then
            working(i) = r.copy(status = Status.Forfeited)
            forfeited += r.id
        }
      }
      val forfeitedV   = forfeited.result()
      val closedLastAs = if forfeitedV.nonEmpty && !anyOpen(working.toVector) then Status.Forfeited else ""
      LossPlan(
        forfeited = forfeitedV,
        recordUpdates = updatesFor(working.toVector, touched, forfeitedV.contains),
        walletType = fundingWalletType(legs.real, legs.records),
        projection = project(working.toVector, closedLastAs)
      )
    }

  // ── close.go ──────────────────────────────────────────────────────────────

  /** Forfeits the primary ACTIVE record, or the one `grantRef` names when given (spec 002 FR-016). */
  def forfeitRecord(@annotation.unused w: Wallet, recs: Vector[Record], grantRef: Option[String] = None): Either[Deny, ClosePlan] =
    val i = grantRef.fold(firstActiveIndex(recs))(g => recs.indexWhere(r => r.grantRef == g && r.status == Status.Active))
    if i < 0 then Left(Deny(Codes.NoActiveBonus, Messages.NoActiveBonusToForfeit))
    else
      val working = recs.updated(i, recs(i).copy(balance = 0, status = Status.Forfeited))
      Right(ClosePlan(Vector(recs(i).id), Vector(RecordUpdate.from(working(i), closedNow = true)), project(working, Status.Forfeited), recs(i).balance))

  /**
   * Cancels: the clawback clamped to real is applied whether or not a record is open, and the
   * primary ACTIVE record, or the one `grantRef` names, is forfeited. A named award that is not
   * active is `GRANT_NOT_OPEN`.
   */
  def cancelRecord(w: Wallet, recs: Vector[Record], clawback: Long, grantRef: Option[String] = None): Either[Deny, CancelPlan] =
    val before  = sumActiveBalances(recs)
    val applied = math.max(0L, math.min(clawback, w.realBalance))
    val newReal = w.realBalance - applied
    val plan = grantRef match
      case Some(g) if !recs.exists(r => r.grantRef == g && r.status == Status.Active) =>
        Left(Deny(Codes.GrantNotOpen, s"Grant $g is not open"))
      case _ =>
        val i = grantRef.fold(firstActiveIndex(recs))(g => recs.indexWhere(r => r.grantRef == g && r.status == Status.Active))
        if i < 0 then Right(CancelPlan(Vector.empty, Vector.empty, project(recs, ""), before, applied, newReal))
        else
          val working = recs.updated(i, recs(i).copy(balance = 0, status = Status.Forfeited))
          Right(CancelPlan(Vector(recs(i).id), Vector(RecordUpdate.from(working(i), closedNow = true)), project(working, Status.Forfeited), before, applied, newReal))
    // The pools (spec 025 FR-009): the clawback converted first.
    plan.flatMap(p => poolStep(w, newReal, Parts.zero)(Pools.debit(_, applied, convertedFirst = true)).map((parts, pools) => p.copy(parts = parts, newPools = pools)))

  def closePendingCap(@annotation.unused w: Wallet, recs: Vector[Record]): Either[Deny, ClosePlan] =
    val i = recs.indexWhere(_.status == Status.ConvertedPendingCap)
    if i < 0 then Left(Deny(Codes.NotPendingCap, Messages.NoPendingCapToClose))
    else
      val working = recs.updated(i, recs(i).copy(status = Status.Closed))
      Right(ClosePlan(Vector(recs(i).id), Vector(RecordUpdate.from(working(i), closedNow = true)), project(working, Status.Closed)))

  def awardCheck(recs: Seq[Record], recordId: String): Option[Deny] =
    Option.unless(recs.exists(r => r.id == recordId && r.status == Status.Active))(
      Deny(Codes.RestrictedFundsUnavailable, "restricted-funds record not found or not active")
    )

  // ── adjust.go ─────────────────────────────────────────────────────────────

  def adjustRestricted(@annotation.unused w: Wallet, recs: Vector[Record], amount: Long): Either[Deny, AdjustPlan] =
    val i = firstActiveIndex(recs)
    if i < 0 then return Left(Deny(Codes.NoOpenRestrictedFunds, "no open restricted-funds record to adjust"))
    val before = recs(i).balance
    if amount < 0 && -amount > before then return Left(Deny(Codes.DebitExceedsBalance, "debit exceeds the record's balance"))
    val after   = before + amount
    val closed  = amount < 0 && after == 0 && recs(i).openRoundCount == 0
    val updated = recs(i).copy(balance = after, status = if closed then Status.Forfeited else recs(i).status)
    val working = recs.updated(i, updated)
    Right(
      AdjustPlan(
        recordId = updated.id,
        balanceBefore = before,
        balanceAfter = after,
        recordUpdates = Vector(RecordUpdate.from(updated, closed)),
        projection = project(working, if closed then Status.Forfeited else ""),
        closedRecords = if closed then Vector(updated.id) else Vector.empty
      )
    )

  /** A provider credit: to the primary record, else to real balance, which is converted money (spec 025 FR-010). */
  def creditPrimary(w: Wallet, recs: Vector[Record], amount: Long): Either[Deny, CreditPlan] =
    val i = firstActiveIndex(recs)
    if i < 0 then
      val newReal = w.realBalance + amount
      poolStep(w, newReal, Parts.zero)(Pools.credit(_, Parts.ofConverted(amount))).map { (parts, pools) =>
        CreditPlan("real", newRealBalance = newReal, projection = project(recs, ""), parts = parts, newPools = pools)
      }
    else Right(creditRecord(recs, i, amount))

  private def creditRecord(recs: Vector[Record], i: Int, amount: Long): CreditPlan =
      val before  = recs(i).balance
      val updated = recs(i).copy(balance = before + amount)
      val working = recs.updated(i, updated)
      CreditPlan("restricted", updated.id, before, updated.balance, Vector(RecordUpdate.from(updated, closedNow = false)), 0L, project(working, ""))

  // ── withdraw.go ───────────────────────────────────────────────────────────

  def forfeitPolicy(r: Record): String = if r.forfeitOnWithdrawal.isEmpty then Policy.FundsAndWinnings else r.forfeitOnWithdrawal

  /** Which ACTIVE records a hold forfeits, and whether any of them calls for the clawback. */
  def forfeitedByHold(recs: Seq[Record]): (Vector[Record], Boolean) =
    val forfeited = recs.filter(r => r.status == Status.Active && forfeitPolicy(r) != Policy.None).toVector
    (forfeited, forfeited.exists(r => Set(Policy.FundsAndWinnings, Policy.CancelPromotion).contains(forfeitPolicy(r))))

  def withdraw(w: Wallet, recs: Vector[Record], in: WithdrawInput): Either[Deny, WithdrawPlan] =
    if in.pendingWithdrawalRequestRef.nonEmpty then
      return Left(Deny(Codes.WithdrawalRequestAlreadyPending, Messages.WithdrawalRequestAlreadyPending))
    if w.realBalance < in.amount then return Left(Deny(Codes.InsufficientFunds, Messages.InsufficientRealBalance))
    if w.depositTurnoverPending > 0 then return Left(Deny(Codes.WithdrawalBlockedTurnover, Messages.WithdrawalBlockedTurnover))

    val working = recs.toArray
    val idx     = indexById(recs)
    val requiresConfirmation = recs.exists(r => r.status == Status.Active && forfeitPolicy(r) != Policy.None)

    val forfeits      = Vector.newBuilder[Forfeit]
    val cancelGrants  = Vector.newBuilder[String]
    var applyClawback = false
    val touched       = mutable.LinkedHashSet.empty[String]
    recs.foreach { r =>
      val policy = forfeitPolicy(r)
      if r.status == Status.Active && policy != Policy.None then
        val cancel = policy == Policy.CancelPromotion
        if cancel then cancelGrants += r.grantRef
        if policy == Policy.FundsAndWinnings || cancel then applyClawback = true
        forfeits += Forfeit(r.id, r.balance, policy, cancel)
        working(idx(r.id)) = working(idx(r.id)).copy(balance = 0, status = Status.Forfeited)
        touched += r.id
    }

    val clawback  = if applyClawback then in.clawback else 0L
    val realAfter = w.realBalance - clawback
    if realAfter < in.amount then
      return Left(Deny(Codes.WithdrawalInsufficientAfterBonusForfeit, Messages.WithdrawalInsufficientAfterBonusForfeit))

    var remaining = if recs.exists(_.status == Status.ConvertedPendingCap) then in.amount else 0L
    var capDraws  = Vector.empty[CapDraw]
    recs.foreach { r =>
      if r.status == Status.ConvertedPendingCap then
        val i             = idx(r.id)
        val fromDeposits  = math.min(remaining, r.depositsSinceRelease)
        remaining -= fromDeposits
        val fromAllowance = math.min(remaining, r.remainingAllowance)
        remaining -= fromAllowance
        working(i) = working(i).copy(
          depositsSinceRelease = r.depositsSinceRelease - fromDeposits,
          remainingAllowance = r.remainingAllowance - fromAllowance
        )
        touched += r.id
        capDraws :+= CapDraw(r.id, fromDeposits, fromAllowance, working(i).remainingAllowance <= 0)
    }
    val voided = remaining
    val paid   = in.amount - voided
    if capDraws.nonEmpty && realAfter - paid <= 0 then capDraws = capDraws.map(_.copy(closes = true))
    capDraws.filter(_.closes).foreach(cd => working(idx(cd.recordId)) = working(idx(cd.recordId)).copy(status = Status.Closed))

    val after   = working.toVector
    val newReal = realAfter - in.amount
    // The pools (spec 025 FR-008): clawback and void converted first, then the payout deposit first.
    val none = (Parts.zero, Parts.zero, Parts.zero)
    poolStep(w, newReal, none) { p0 =>
      for
        (p1, clawbackParts) <- Pools.debit(p0, clawback, convertedFirst = true)
        (p2, voidedParts)   <- Pools.debit(p1, voided, convertedFirst = true)
        (p3, paidParts)     <- Pools.debit(p2, paid, convertedFirst = false)
      yield (p3, (clawbackParts, voidedParts, paidParts))
    }.map { case ((clawbackParts, voidedParts, paidParts), pools) =>
      WithdrawPlan(
        forfeits = forfeits.result(),
        clawback = clawback,
        capDraws = capDraws,
        paid = paid,
        voided = voided,
        newReal = newReal,
        restrictedBalance = sumBalances(after.filter(r => Status.isOpen(r.status))),
        recordUpdates = updatesFor(after, touched, id => after.find(_.id == id).exists(r => r.status == Status.Forfeited || r.status == Status.Closed)),
        cancelGrants = cancelGrants.result(),
        requiresConfirmation = requiresConfirmation,
        clawbackParts = clawbackParts,
        voidedParts = voidedParts,
        paidParts = paidParts,
        newPools = pools
      )
    }

  // ── legs.go ───────────────────────────────────────────────────────────────

  /** "<record>:<amount>|…", trailing "|" (legs.go formatLegList). */
  def formatLegList(legs: Seq[Leg]): String = legs.map(l => s"${l.recordId}:${l.amount}|").mkString

  /** Liberal on empty segments, strict on malformed ones: these values carry money. */
  def parseLegList(s: String): Either[String, Vector[Leg]] =
    s.split('|').iterator.filter(_.nonEmpty).foldLeft[Either[String, Vector[Leg]]](Right(Vector.empty)) { (acc, part) =>
      acc.flatMap { legs =>
        part.split(":", 2) match
          case Array(id, amount) if id.nonEmpty =>
            amount.toLongOption.toRight(s"funding: malformed leg amount \"$part\" in \"$s\"").map(a => legs :+ Leg(id, a))
          case _ => Left(s"funding: malformed leg \"$part\" in \"$s\"")
      }
    }
