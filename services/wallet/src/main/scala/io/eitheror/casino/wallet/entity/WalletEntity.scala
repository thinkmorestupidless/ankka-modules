package io.eitheror.casino.wallet.entity

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*
import io.eitheror.casino.contract.money.{Answer, Key}
import io.eitheror.casino.contract.personal.Personal
import io.eitheror.casino.wallet.entity.WalletEvent.*
import io.eitheror.casino.wallet.funding.*

import java.time.Instant

/**
 * A wallet: one player's money in one currency (spec 002 FR-001). Every rule of the spec is a check
 * here followed by the event it guards; the resolver (`funding`) decides, this entity records.
 *
 * Every money command first consults the key store (FR-002): a repeat of a recent key is answered
 * with the first outcome and persists nothing; another command under a recent key is refused as
 * `KEY_REUSED`. A refusal of a new key is persisted as `CommandRefused`, so its repeat is refused the
 * same way and the audit consumer sees it (research R3). The store holds the last `RecentKeys` keys;
 * the lifetime record is the caller's workflow (R17). Handlers call nothing and read no clock.
 */
final class WalletEntity(context: EventSourcedEntityContext) extends EventSourcedEntity[WalletState, WalletEvent]:
  import WalletEntity.*

  private val walletId = context.entityId

  def emptyState: WalletState = WalletState(walletId)

  // ── the fold ──────────────────────────────────────────────────────────────

  def applyEvent(event: WalletEvent): WalletState =
    val s = currentState
    event match
      case e: WalletOpenedFromMigration =>
        s.copy(created = true, realBalance = e.realBalance, depositTurnoverPending = e.depositTurnoverPending,
          records = e.records, totalDeposits = e.totalDeposits, depositOrdinal = e.depositOrdinal,
          restrictedWithoutRecords = e.restrictedWithoutRecords, openBets = e.openBets,
          withdrawals = e.withdrawals.map(w => w.requestId -> w).toMap, pendingRequestId = e.pendingRequestId)
          .remember(e.key, e.digest, e.outcome)

      case e: DepositConfirmed =>
        val cleared = e.requiredTurnover == 0
        val capped = s.records.map { a =>
          if a.status == Status.ConvertedPendingCap then
            a.copy(record = a.record.copy(depositsSinceRelease = a.record.depositsSinceRelease + e.amount))
          else a
        }
        s.copy(created = true, realBalance = e.realAfter, depositTurnoverPending = s.depositTurnoverPending + e.requiredTurnover,
          records = capped, totalDeposits = s.totalDeposits + e.amount, depositOrdinal = e.depositOrdinal)
          .withDeposit(DepositRecord(e.key, e.amount, e.terms, e.requiredTurnover, if cleared then "CLEARED" else "PENDING", e.at))
          .remember(e.key, e.digest, e.outcome)

      case e: BetPlaced =>
        val pending = s.depositTurnoverPending - e.turnoverReduced
        val deposits = if pending == 0 then s.recentDeposits.map(_.copy(status = "CLEARED")) else s.recentDeposits
        s.withUpdates(e.recordUpdates)
          .copy(realBalance = e.realAfter, depositTurnoverPending = pending, recentDeposits = deposits, openBets = s.openBets :+ e.bet)
          .remember(e.key, e.digest, e.outcome)

      case e: WinCredited =>
        s.withUpdates(e.recordUpdates).copy(realBalance = e.realAfter).settle(e.settledBets, BetStatus.Settled).remember(e.key, e.digest, e.outcome)

      case e: BetRolledBack =>
        s.withUpdates(e.recordUpdates).copy(realBalance = e.realAfter).settle(e.voidedBets, BetStatus.Voided).remember(e.key, e.digest, e.outcome)

      case e: RoundSettledAsLoss =>
        s.withUpdates(e.recordUpdates).settle(e.lostBets, BetStatus.Loss).remember(e.key, e.digest, e.outcome)

      case e: RestrictedFundsAwarded =>
        s.copy(created = true, records = s.records :+ e.awarded).remember(e.key, e.digest, e.outcome)

      case e: RestrictedFundsForfeited =>
        s.withUpdates(e.recordUpdates).copy(realBalance = e.realAfter).remember(e.key, e.digest, e.outcome)

      case e: RestrictedFundsCancelled =>
        s.withUpdates(e.recordUpdates).copy(realBalance = e.realAfter).remember(e.key, e.digest, e.outcome)

      case e: PendingCapClosed =>
        s.withUpdates(e.recordUpdates).remember(e.key, e.digest, e.outcome)

      case e: PrimarySwitched =>
        s.copy(records = s.records.map(a => if a.id == e.recordId then a.copy(record = a.record.copy(priority = e.newPriority)) else a))
          .remember(e.key, e.digest, e.outcome)

      case e: HoldPlaced =>
        val request = WithdrawalRequest(e.requestId, e.transactionId, e.amount, RequestStatus.Pending, e.at,
          forfeited = e.forfeits.map(_.recordId), clawbackApplied = e.clawback, voided = e.voided,
          confirmationRequired = e.confirmationRequired)
        s.withUpdates(e.recordUpdates)
          .copy(realBalance = e.realAfter, withdrawals = s.withdrawals.updated(e.requestId, request), pendingRequestId = Some(e.requestId))
          .remember(e.key, e.digest, e.outcome)

      case e: HoldReleased =>
        s.copy(realBalance = e.realAfter, pendingRequestId = None,
          withdrawals = s.withdrawals.updatedWith(e.requestId)(_.map(_.copy(status = RequestStatus.Released, closedAt = Some(e.at)))))
          .remember(e.key, e.digest, e.outcome)

      case e: HoldConfirmed =>
        s.copy(pendingRequestId = None,
          withdrawals = s.withdrawals.updatedWith(e.requestId)(_.map(_.copy(status = RequestStatus.Confirmed, closedAt = Some(e.at)))))
          .remember(e.key, e.digest, e.outcome)

      case e: WithdrawalReversed =>
        s.copy(realBalance = e.realAfter,
          withdrawals = s.withdrawals.updatedWith(e.requestId)(_.map(_.copy(status = RequestStatus.Reversed))))
          .remember(e.key, e.digest, e.outcome)

      case e: AdminAdjusted =>
        s.withUpdates(e.recordUpdates).copy(realBalance = e.realAfter).remember(e.key, e.digest, e.outcome)

      case e: ProviderCredited =>
        s.withUpdates(e.recordUpdates).copy(created = true, realBalance = e.realAfter).remember(e.key, e.digest, e.outcome)

      case e: TurnoverOverrideSet =>
        s.copy(turnoverOverride = e.terms)

      case e: CommandRefused =>
        s.remember(e.key, e.digest, e.outcome)

  // ── the key store, refusals, ids ──────────────────────────────────────────

  /**
   * The prelude every money command runs (FR-002): a seen key with the same digest answers the
   * first outcome and persists nothing; with another digest it is `KEY_REUSED`, recorded under a
   * synthetic key so the original key's outcome is untouched; a new key is decided by `decide`.
   */
  private def keyed(command: MoneyCommand, wireName: String)(decide: String => Effect[Outcome]): Effect[Outcome] =
    keyedWithDigest(command, wireName, MoneyCommand.digest(wireName, command))(decide)

  private def keyedWithDigest(command: MoneyCommand, wireName: String, digest: String)(decide: String => Effect[Outcome]): Effect[Outcome] =
    currentState.seen.check(Key.unsafe(command.key), digest) match
      case Some(Answer.Repeated(first)) => effects.reply(first)
      case Some(Answer.Reused(first)) =>
        val outcome = refusal(Refusals.KeyReused, Refusals.keyReused(command.key, first.outcome), Map("key" -> command.key), wireName, amountOf(command))
        effects.persist(CommandRefused(s"${command.key}#reused#$digest", digest, wireName, Refusals.KeyReused,
          outcome.message.getOrElse(""), amountOf(command), command.at, outcome)).thenReply(_ => outcome)
      case Some(Answer.First(first)) => effects.reply(first)
      case None                      => decide(digest)

  private def refusal(code: String, message: String, details: Map[String, String], wireName: String, amount: Option[Long]): Outcome =
    val s = currentState
    Outcome(code = Some(code), message = Some(message), details = details, commandType = wireName, amount = amount,
      realBalance = s.realBalance, restrictedBalance = s.restrictedBalance)

  private def refuse(command: MoneyCommand, wireName: String, digest: String, code: String, message: String,
      details: Map[String, String] = Map.empty): Effect[Outcome] =
    val outcome = refusal(code, message, details, wireName, amountOf(command))
    effects.persist(CommandRefused(command.key, digest, wireName, code, message, amountOf(command), command.at, outcome))
      .thenReply(_ => outcome)

  private def refuseDeny(command: MoneyCommand, wireName: String, digest: String, deny: Deny): Effect[Outcome] =
    refuse(command, wireName, digest, deny.code, deny.message)

  /** A transaction id derived from the wallet and the key: the same on every replay and retry. */
  private def transactionId(key: String): String = io.eitheror.casino.wallet.Ids.transactionId(walletId, key)

  /** The open bets a settlement closes: the one named, or every open bet of the round. */
  private def betsToSettle(s: WalletState, roundId: String, betKey: Option[String]): Vector[Bet] =
    betKey.fold(s.openBetsOf(roundId))(k => s.openBetsOf(roundId).filter(_.key == k))

  /**
   * A settlement transaction's balance before and after, as the original wrote it: real for a real
   * or split bet (or when no record took a share), else the first record's that did.
   */
  private def transactionBalances(s: WalletState, updates: Vector[RecordUpdate], walletType: String, shares: Vector[Leg],
      realAfter: Long): (Long, Long) =
    shares.headOption.filter(_ => walletType == "bonus").flatMap(l => s.record(l.recordId).map(a => (a.record.balance,
      updates.find(_.id == l.recordId).fold(a.record.balance)(_.balance)))).getOrElse((s.realBalance, realAfter))

  private def accepted(wireName: String, command: MoneyCommand, txId: Option[String], real: Long, restricted: Long): Outcome =
    Outcome(commandType = wireName, amount = amountOf(command), transactionId = txId, realBalance = real, restrictedBalance = restricted)

  // ── migration ─────────────────────────────────────────────────────────────

  def openMigrated(c: OpenMigrated): Effect[Outcome] = keyed(c, "open-migrated") { digest =>
    if currentState.created then refuse(c, "open-migrated", digest, Refusals.WalletAlreadyOpen, Refusals.walletAlreadyOpen)
    else
      val after   = WalletState(walletId, created = true, realBalance = c.realBalance, records = c.records, restrictedWithoutRecords = c.restrictedWithoutRecords)
      val outcome = accepted("open-migrated", c, None, c.realBalance, after.restrictedBalance)
      effects.persist(WalletOpenedFromMigration(c.key, digest, c.realBalance, c.depositTurnoverPending, c.records, c.totalDeposits,
        c.depositOrdinal, c.restrictedWithoutRecords, c.at, outcome, c.openBets, c.withdrawals, c.pendingRequestId)).thenReply(_ => outcome)
  }

  // ── deposits (FR-022) ─────────────────────────────────────────────────────

  def deposit(c: Deposit): Effect[Outcome] = keyed(c, "deposit") { digest =>
    val s        = currentState
    val terms    = s.turnoverOverride.getOrElse(c.brandTerms)
    val required = Math.multiplyExact(c.amount, terms.multiplier)
    val ordinal  = s.depositOrdinal + 1
    val txId     = transactionId(c.key)
    val real     = Math.addExact(s.realBalance, c.amount)
    val sums     = s.withDeposit(DepositRecord(c.key, c.amount, terms, required, "", c.at)).depositSums(c.at)
    val outcome = accepted("deposit", c, Some(txId), real, s.restrictedBalance)
      .copy(depositOrdinal = Some(ordinal), turnoverRequired = Some(required), depositSums = Some(sums),
        balanceBefore = Some(s.realBalance), balanceAfter = Some(real))
    effects.persist(DepositConfirmed(c.key, digest, txId, c.amount, c.paymentProvider, c.terminal, c.psp, c.scheme, terms, required,
      ordinal, real, s.restrictedBalance, c.at, outcome)).thenReply(_ => outcome)
  }

  // ── bets (FR-005 to FR-014) ───────────────────────────────────────────────

  def placeBet(c: PlaceBet): Effect[Outcome] = keyed(c, "place-bet") { digest =>
    val s = currentState
    if !s.created then refuse(c, "place-bet", digest, Refusals.WalletNotFound, Refusals.walletNotFound)
    else
      val flags = Flags(c.switches.partialSplitsAllowed, c.switches.bonusWageringCounts, c.allowNegative, c.switches.contributionRate)
      val open  = s.openRecords
      Resolver.placeBet(s.fundingWallet, open, Stake(c.amount, Game(c.provider, c.gameId, c.category)), flags) match
        case Left(deny) => refuseDeny(c, "place-bet", digest, deny)
        case Right(plan) =>
          val txId        = transactionId(c.key)
          val reduced     = math.max(0L, math.min(plan.realLeg, s.depositTurnoverPending))
          val conversions = plan.converting.map(id => Leg(id, open.find(_.id == id).fold(0L)(_.balance) - Resolver.legAmount(plan.recordLegs, id)))
          val restricted  = s.withUpdates(plan.recordUpdates).restrictedBalance
          val bet = Bet(c.key, c.roundId, txId, c.amount, plan.realLeg, plan.recordLegs, plan.contributions, plan.walletType,
            BetStatus.Open, c.sessionId, c.gameId, c.at)
          val outcome = accepted("place-bet", c, Some(txId), plan.newRealBalance, restricted).copy(
            walletType = Some(plan.walletType), realLeg = Some(plan.realLeg), recordLegs = plan.recordLegs,
            conversions = plan.converting, balanceBefore = Some(plan.balanceBefore), balanceAfter = Some(plan.balanceAfter))
          effects.persist(BetPlaced(c.key, digest, bet, c.provider, conversions, plan.recordUpdates, reduced, plan.balanceBefore,
            plan.balanceAfter, plan.newRealBalance, restricted, c.at, outcome)).thenReply(_ => outcome)
  }

  def creditWin(c: CreditWin): Effect[Outcome] = keyed(c, "credit-win") { digest =>
    val s    = currentState
    val bets = betsToSettle(s, c.roundId, c.betKey)
    if bets.isEmpty then refuse(c, "credit-win", digest, Refusals.RoundNotFound, Refusals.roundNotFound(c.roundId))
    else
      val legs = mergedLegs(bets)
      Resolver.creditWin(s.fundingWallet, s.openRecords, legs, c.amount) match
        case Left(deny) => refuseDeny(c, "credit-win", digest, deny)
        case Right(plan) =>
          val updates    = extraRoundDecrements(plan.recordUpdates, bets)
          val restricted = s.withUpdates(updates).restrictedBalance
          val txId       = transactionId(c.key)
          val (before, after) = transactionBalances(s, updates, plan.walletType, plan.recordShares, plan.newRealBalance)
          val outcome = accepted("credit-win", c, Some(txId), plan.newRealBalance, restricted)
            .copy(walletType = Some(plan.walletType), recordLegs = plan.recordShares, realLeg = Some(plan.realShare),
              balanceBefore = Some(before), balanceAfter = Some(after))
          effects.persist(WinCredited(c.key, digest, txId, c.roundId, bets.head.gameId, c.amount, plan.realShare, plan.recordShares,
            plan.walletType, bets.map(_.key), updates, plan.newRealBalance, restricted, c.at, outcome)).thenReply(_ => outcome)
  }

  def rollbackBet(c: RollbackBet): Effect[Outcome] = keyed(c, "rollback-bet") { digest =>
    val s = currentState
    val (targets, late) = c.betKey match
      case Some(k) =>
        s.openBets.find(b => b.key == k && b.roundKey == c.roundId).map(b => (Vector(b), false))
          .orElse(s.settledBets.findLast(b => b.key == k && b.roundKey == c.roundId && b.status == BetStatus.Settled).map(b => (Vector(b), true)))
          .getOrElse((Vector.empty, false))
      case None =>
        val open = s.openBetsOf(c.roundId)
        if open.nonEmpty then (open, false)
        else s.settledBets.findLast(b => b.roundKey == c.roundId && b.status == BetStatus.Settled).fold((Vector.empty[Bet], false))(b => (Vector(b), true))
    if targets.isEmpty then refuse(c, "rollback-bet", digest, Refusals.RoundNotFound, Refusals.roundNotFound(c.roundId))
    else
      // Each bet is restored in turn against the records as the previous one left them.
      val start: Either[Deny, (Wallet, Vector[Record], Long, Vector[Leg], Set[String])] =
        Right((s.fundingWallet, s.openRecords, 0L, Vector.empty, Set.empty))
      targets.foldLeft(start) { (acc, bet) =>
        acc.flatMap { (w, recs, realRestored, restored, touched) =>
          Resolver.rollbackBet(w, recs, Legs(bet.realLeg, bet.recordLegs), bet.contributions).map { plan =>
            // A late rollback's round was already closed on its records: its count must not fall again.
            val updates = if late then plan.recordUpdates.map(u => restoreCount(u, recs, bet)) else plan.recordUpdates
            val next    = recs.map(r => updates.find(_.id == r.id).fold(r)(_.applyTo(r)))
            (w.copy(realBalance = plan.newRealBalance), next, realRestored + plan.realRestored, restored ++ plan.recordRestored, touched ++ updates.map(_.id))
          }
        }
      } match
        case Left(deny) => refuseDeny(c, "rollback-bet", digest, deny)
        case Right((w, recs, realRestored, restored, touched)) =>
          val updates    = recs.filter(r => touched(r.id)).map(RecordUpdate.from(_, closedNow = false))
          val restricted = s.withUpdates(updates).restrictedBalance
          val txId       = transactionId(c.key)
          val amount     = targets.map(_.amount).sum
          val walletType      = Resolver.fundingWalletType(targets.map(_.realLeg).sum, targets.flatMap(_.recordLegs))
          val (before, after) = transactionBalances(s, updates, walletType, restored, w.realBalance)
          val outcome = accepted("rollback-bet", c, Some(txId), w.realBalance, restricted).copy(amount = Some(amount),
            realLeg = Some(realRestored), recordLegs = restored, walletType = Some(walletType),
            balanceBefore = Some(before), balanceAfter = Some(after))
          effects.persist(BetRolledBack(c.key, digest, txId, c.roundId, amount, realRestored, restored, targets.map(_.key), updates,
            w.realBalance, restricted, c.at, outcome)).thenReply(_ => outcome)
  }

  def settleLoss(c: SettleLoss): Effect[Outcome] = keyed(c, "settle-loss") { digest =>
    val s    = currentState
    val bets = betsToSettle(s, c.roundId, c.betKey)
    if bets.isEmpty then refuse(c, "settle-loss", digest, Refusals.RoundNotFound, Refusals.roundNotFound(c.roundId))
    else
      val start: Either[Deny, (Vector[Record], Vector[String], Set[String])] = Right((s.openRecords, Vector.empty, Set.empty))
      bets.foldLeft(start) { (acc, bet) =>
        acc.flatMap { (recs, forfeited, touched) =>
          Resolver.settleLoss(s.fundingWallet, recs, Legs(bet.realLeg, bet.recordLegs)).map { plan =>
            val next = recs.map(r => plan.recordUpdates.find(_.id == r.id).fold(r)(_.applyTo(r))).filter(r => Status.isOpen(r.status))
            (next, forfeited ++ plan.forfeited, touched ++ plan.recordUpdates.map(_.id))
          }
        }
      } match
        case Left(deny) => refuseDeny(c, "settle-loss", digest, deny)
        case Right((_, forfeitedIds, touched)) =>
          // The records as the losses left them, forfeited ones included.
          val updates = replayLosses(s, bets).filter(u => touched(u.id))
          val restricted = s.withUpdates(updates).restrictedBalance
          val txId       = transactionId(c.key)
          val forfeits   = forfeitedIds.map(id => Forfeit(id, 0L, "", cancelGrant = false))
          val walletType = Resolver.fundingWalletType(bets.map(_.realLeg).sum, bets.flatMap(_.recordLegs))
          val outcome    = accepted("settle-loss", c, Some(txId), s.realBalance, restricted)
            .copy(forfeited = forfeitedIds, walletType = Some(walletType))
          effects.persist(RoundSettledAsLoss(c.key, digest, txId, c.roundId, bets.map(_.key), forfeits, updates, s.realBalance,
            restricted, c.at, outcome)).thenReply(_ => outcome)
  }

  private def replayLosses(s: WalletState, bets: Vector[Bet]): Vector[RecordUpdate] =
    val finalRecs = bets.foldLeft(s.openRecords.map(r => r.id -> r).toMap) { (byId, bet) =>
      val open = Resolver.ordered(byId.values.filter(r => Status.isOpen(r.status)).toVector)
      Resolver.settleLoss(s.fundingWallet, open, Legs(bet.realLeg, bet.recordLegs)).fold(_ => byId, plan =>
        plan.recordUpdates.foldLeft(byId)((m, u) => m.updatedWith(u.id)(_.map(u.applyTo))))
    }
    finalRecs.values.toVector.map(r => RecordUpdate.from(r, closedNow = r.status == Status.Forfeited))

  def providerCredit(c: ProviderCredit): Effect[Outcome] = keyed(c, "provider-credit") { digest =>
    val s = currentState
    if !s.created then refuse(c, "provider-credit", digest, Refusals.WalletNotFound, Refusals.walletNotFound)
    else
      val plan       = Resolver.creditPrimary(s.fundingWallet, s.openRecords, c.amount)
      val real       = if plan.target == "real" then plan.newRealBalance else s.realBalance
      val restricted = s.withUpdates(plan.recordUpdates).restrictedBalance
      val txId       = transactionId(c.key)
      val (before, after) = if plan.target == "real" then (s.realBalance, real) else (plan.balanceBefore, plan.balanceAfter)
      val outcome = accepted("provider-credit", c, Some(txId), real, restricted).copy(landedOn = Some(plan.target),
        recordId = Option(plan.recordId).filter(_.nonEmpty), balanceBefore = Some(before), balanceAfter = Some(after))
      effects.persist(ProviderCredited(c.key, digest, txId, c.amount, c.providerRef, c.reason, c.actorId, plan.target,
        Option(plan.recordId).filter(_.nonEmpty), plan.recordUpdates, real, restricted, c.at, outcome)).thenReply(_ => outcome)
  }

  // ── restricted funds (FR-015 to FR-021) ───────────────────────────────────

  def award(c: Award): Effect[Outcome] = keyed(c, "award") { digest =>
    val s = currentState
    if c.bonusRef.trim.isEmpty then refuse(c, "award", digest, Refusals.MissingBonusRef, Refusals.missingBonusRef)
    else if c.maxConcurrentAwards.exists(limit => s.openRecords.count(_.status == Status.Active) >= limit) then
      refuse(c, "award", digest, Refusals.BonusAlreadyActive, Refusals.bonusAlreadyActive)
    else
      val txId = transactionId(c.key)
      val r    = c.rules
      val record = Record(
        id = txId, grantRef = c.key, status = Status.Active, priority = c.priority, grantedAt = c.at.toString,
        balance = c.amount, releaseRequirement = Math.multiplyExact(c.amount, c.wageringMultiplier),
        maxConvertible = c.maxConvertible, maxStake = r.maxStake, maxStakeEnforcement = r.maxStakeEnforcement,
        contributionDefault = r.contributionDefault, contributionOverrides = r.contributionOverrides,
        scopeInclude = r.scopeInclude, scopeExclude = r.scopeExclude, scopeViolationMode = r.scopeViolationMode,
        fundingOrder = r.fundingOrder, winAllocation = r.winAllocation, releaseProgressScope = r.releaseProgressScope,
        forfeitOnWithdrawal = r.forfeitOnWithdrawal
      )
      val awarded    = AwardedRecord(record, c.bonusRef, c.expiresAt, c.at, Some(Clawback.Baseline(s.realBalance, s.totalDeposits)))
      val restricted = s.restrictedBalance + c.amount
      val outcome = accepted("award", c, Some(txId), s.realBalance, restricted)
        .copy(recordId = Some(txId), wageringRequirement = Some(record.releaseRequirement))
      effects.persist(RestrictedFundsAwarded(c.key, digest, txId, awarded, s.realBalance, restricted, c.at, outcome)).thenReply(_ => outcome)
  }

  def forfeitAward(c: ForfeitAward): Effect[Outcome] = keyed(c, "forfeit") { digest =>
    val s = currentState
    Resolver.forfeitRecord(s.fundingWallet, s.openRecords, c.grantRef) match
      case Left(deny) => refuseDeny(c, "forfeit", digest, deny)
      case Right(plan) =>
        val restricted = s.withUpdates(plan.recordUpdates).restrictedBalance
        val txId       = transactionId(c.key)
        val forfeits   = plan.closedRecords.map(id => Forfeit(id, plan.forfeitedAmount, "", cancelGrant = false))
        val outcome    = accepted("forfeit", c, Some(txId), s.realBalance, restricted).copy(forfeited = plan.closedRecords)
        effects.persist(RestrictedFundsForfeited(c.key, digest, txId, forfeits, "forfeit", plan.recordUpdates, s.realBalance,
          restricted, c.at, outcome)).thenReply(_ => outcome)
  }

  def cancelAward(c: CancelAward): Effect[Outcome] = keyed(c, "cancel") { digest =>
    val s = currentState
    // The baseline: the named award's, else the primary active record's, else the latest award's (FR-017).
    val source = c.grantRef.flatMap(s.recordByGrant)
      .orElse(s.openRecords.find(_.status == Status.Active).flatMap(r => s.record(r.id)))
      .orElse(s.records.maxByOption(_.awardedAt))
    val clawback = Clawback.of(source.flatMap(_.baseline), s.realBalance, s.totalDeposits)
    Resolver.cancelRecord(s.fundingWallet, s.openRecords, clawback, c.grantRef) match
      case Left(deny) => refuseDeny(c, "cancel", digest, deny)
      case Right(plan) =>
        val restricted = s.withUpdates(plan.recordUpdates).restrictedBalance
        val txId       = transactionId(c.key)
        val forfeits = plan.closedRecords.map(id => Forfeit(id, s.openRecords.find(_.id == id).fold(0L)(_.balance), "", cancelGrant = false))
        val outcome = accepted("cancel", c, Some(txId), plan.newRealBalance, restricted)
          .copy(forfeited = plan.closedRecords, clawbackApplied = Some(plan.clawbackApplied), balanceBefore = Some(plan.bonusBalanceBefore))
        effects.persist(RestrictedFundsCancelled(c.key, digest, txId, forfeits, plan.clawbackApplied, plan.bonusBalanceBefore,
          plan.recordUpdates, plan.newRealBalance, restricted, c.at, outcome)).thenReply(_ => outcome)
  }

  def closePendingCap(c: ClosePendingCap): Effect[Outcome] = keyed(c, "close-pending-cap") { digest =>
    val s = currentState
    Resolver.closePendingCap(s.fundingWallet, s.openRecords) match
      case Left(deny) => refuseDeny(c, "close-pending-cap", digest, deny)
      case Right(plan) =>
        val txId    = transactionId(c.key)
        val outcome = accepted("close-pending-cap", c, Some(txId), s.realBalance, s.restrictedBalance).copy(closedRecordId = plan.closedRecords.headOption)
        effects.persist(PendingCapClosed(c.key, digest, txId, plan.closedRecords.head, plan.recordUpdates, c.at, outcome)).thenReply(_ => outcome)
  }

  def switchPrimary(c: SwitchPrimary): Effect[Outcome] = keyed(c, "switch") { digest =>
    val s = currentState
    s.recordByGrant(c.grantRef) match
      case None => refuse(c, "switch", digest, Refusals.GrantNotFound, Refusals.grantNotFound(c.grantRef))
      case Some(a) if a.status != Status.Active =>
        refuse(c, "switch", digest, Refusals.GrantNotActive, Refusals.grantNotActive(c.grantRef))
      case Some(a) =>
        val open           = s.openRecords
        val alreadyPrimary = open.find(_.status == Status.Active).exists(_.id == a.id)
        val priority       = if alreadyPrimary then a.record.priority else open.map(_.priority).minOption.fold(0L)(_ - 1)
        val outcome = accepted("switch", c, None, s.realBalance, s.restrictedBalance)
          .copy(recordId = Some(a.id), priority = Some(priority), alreadyPrimary = Some(alreadyPrimary))
        effects.persist(PrimarySwitched(c.key, digest, a.id, priority, c.at, outcome)).thenReply(_ => outcome)
  }

  // ── holds (FR-023 to FR-025) ──────────────────────────────────────────────

  /** The clawback a hold would apply: from the earliest awarded of the records it forfeits (FR-023). */
  private def holdClawback(s: WalletState): Long =
    val (forfeited, calls) = Resolver.forfeitedByHold(s.openRecords)
    if !calls then 0L
    else
      val inAwardOrder = forfeited.flatMap(r => s.record(r.id)).sortBy(_.awardedAt)
      Clawback.ofEarliest(inAwardOrder.map(_.baseline), s.realBalance, s.totalDeposits)

  private def holdInput(s: WalletState, amount: Long): WithdrawInput =
    WithdrawInput(amount, holdClawback(s), pendingWithdrawalRequestRef = s.pendingRequestId.getOrElse(""))

  def hold(c: Hold): Effect[Outcome] = keyed(c, "hold") { digest =>
    val s = currentState
    Resolver.withdraw(s.fundingWallet, s.openRecords, holdInput(s, c.amount)) match
      case Left(deny) if deny.code == Codes.WithdrawalInsufficientAfterBonusForfeit =>
        // Names what the hold computed and why it fell short (withdrawals.feature); nothing is applied.
        val (forfeited, _) = Resolver.forfeitedByHold(s.openRecords)
        val clawback       = holdClawback(s)
        refuse(c, "hold", digest, deny.code, deny.message, Map(
          "forfeited" -> forfeited.map(_.id).mkString(","), "clawback" -> clawback.toString,
          "realBalance" -> (s.realBalance - clawback).toString, "amount" -> c.amount.toString))
      case Left(deny) => refuseDeny(c, "hold", digest, deny)
      case Right(plan) =>
        val txId       = transactionId(c.key)
        val restricted = s.withUpdates(plan.recordUpdates).restrictedBalance
        val outcome = accepted("hold", c, Some(txId), plan.newReal, restricted).copy(
          balanceBefore = Some(s.realBalance - plan.clawback), balanceAfter = Some(plan.newReal),
          confirmationRequired = Some(plan.requiresConfirmation), forfeited = plan.forfeits.map(_.recordId),
          clawbackApplied = Some(plan.clawback), voided = Some(plan.voided), paid = Some(plan.paid), cancelGrants = plan.cancelGrants,
          // An award a cancel_promotion policy forfeits is reported for cancellation with reason "withdrawal" (FR-023).
          details = if plan.cancelGrants.nonEmpty then Map("cancelReason" -> "withdrawal") else Map.empty)
        effects.persist(HoldPlaced(c.key, digest, txId, c.key, c.amount, plan.forfeits, plan.clawback, plan.capDraws, plan.voided,
          plan.paid, plan.cancelGrants, plan.requiresConfirmation, plan.recordUpdates, plan.newReal, restricted, c.at, outcome))
          .thenReply(_ => outcome)
  }

  def previewHold(p: PreviewHold): ReadOnlyEffect[HoldPreview] =
    val s     = currentState
    val (forfeited, _) = Resolver.forfeitedByHold(s.openRecords)
    val bonus = s.openRecords.filter(_.status == Status.Active).map(_.balance).sum
    Resolver.withdraw(s.fundingWallet, s.openRecords, holdInput(s, p.amount)) match
      case Left(deny) =>
        effects.reply(HoldPreview(Some(deny.code), Some(deny.message), forfeited.nonEmpty, bonus, holdClawback(s),
          forfeited.map(_.balance).sum, math.max(0L, s.realBalance - holdClawback(s)), Vector.empty))
      case Right(plan) =>
        effects.reply(HoldPreview(None, None, plan.requiresConfirmation, bonus, plan.clawback, plan.forfeits.map(_.amount).sum,
          math.max(0L, s.realBalance - plan.clawback), plan.forfeits))

  def release(c: Release): Effect[Outcome] = keyed(c, "release") { digest =>
    val s = currentState
    s.pendingRequestId.filter(_ == c.requestId).flatMap(s.withdrawals.get) match
      // The original answers a player's cancel and an operator's reject or failure with different codes.
      case None if c.releasedAs == "cancelled" =>
        refuse(c, "release", digest, Refusals.RequestNotCancellable, Refusals.requestNotCancellable(c.requestId))
      case None => refuse(c, "release", digest, Refusals.RequestNotPending, Refusals.requestNotPending(c.requestId))
      case Some(request) =>
        val txId    = transactionId(c.key)
        val real    = Math.addExact(s.realBalance, request.amount)
        val outcome = accepted("release", c, Some(txId), real, s.restrictedBalance)
          .copy(amount = Some(request.amount), balanceBefore = Some(s.realBalance), balanceAfter = Some(real))
        effects.persist(HoldReleased(c.key, digest, txId, c.requestId, request.amount, c.releasedAs, real, s.restrictedBalance, c.at, outcome))
          .thenReply(_ => outcome)
  }

  def confirm(c: Confirm): Effect[Outcome] = keyed(c, "confirm") { digest =>
    val s = currentState
    if !s.pendingRequestId.contains(c.requestId) then
      refuse(c, "confirm", digest, Refusals.RequestNotPending, Refusals.requestNotPending(c.requestId))
    else
      val outcome = accepted("confirm", c, None, s.realBalance, s.restrictedBalance)
      effects.persist(HoldConfirmed(c.key, digest, c.requestId, c.at, outcome)).thenReply(_ => outcome)
  }

  def reverse(c: Reverse): Effect[Outcome] = keyed(c, "reverse") { digest =>
    val s = currentState
    s.withdrawals.get(c.requestId) match
      // Reversing a reversed withdrawal is answered as done and moves nothing, as the original does.
      case Some(request) if request.status == RequestStatus.Reversed =>
        effects.reply(accepted("reverse", c, None, s.realBalance, s.restrictedBalance)
          .copy(amount = Some(request.amount), details = Map("idempotent" -> "true")))
      case Some(request) if request.status != RequestStatus.Confirmed =>
        refuse(c, "reverse", digest, Refusals.WithdrawalNotCompleted, Refusals.withdrawalNotCompleted(c.requestId))
      case None => refuse(c, "reverse", digest, Refusals.WithdrawalNotCompleted, Refusals.withdrawalNotCompleted(c.requestId))
      case Some(request) =>
        val txId    = transactionId(c.key)
        val real    = Math.addExact(s.realBalance, request.amount)
        val outcome = accepted("reverse", c, Some(txId), real, s.restrictedBalance)
          .copy(amount = Some(request.amount), balanceBefore = Some(s.realBalance), balanceAfter = Some(real))
        effects.persist(WithdrawalReversed(c.key, digest, txId, c.requestId, request.amount, real, s.restrictedBalance, c.at, outcome))
          .thenReply(_ => outcome)
  }

  // ── adjustments (FR-026) ──────────────────────────────────────────────────

  def adjust(c: Adjust): Effect[Outcome] = keyed(c, "adjust") { digest =>
    val s = currentState
    if c.reason.trim.isEmpty then refuse(c, "adjust", digest, Refusals.ReasonRequired, Refusals.reasonRequired)
    else if c.amount == 0 then effects.reply(accepted("adjust", c, None, s.realBalance, s.restrictedBalance))
    else
      val txId = transactionId(c.key)
      def persistAdjusted(real: Long, recordId: Option[String], forfeited: Option[Forfeit], updates: Vector[RecordUpdate],
          before: Long, after: Long) =
        val restricted = s.withUpdates(updates).restrictedBalance
        val outcome = accepted("adjust", c, Some(txId), real, restricted).copy(recordId = recordId,
          forfeited = forfeited.map(_.recordId).toVector, balanceBefore = Some(before), balanceAfter = Some(after))
        effects.persist(AdminAdjusted(c.key, digest, txId, c.target, c.amount, Personal(c.reason), c.category, c.actorId, recordId,
          forfeited, updates, real, restricted, c.at, outcome)).thenReply(_ => outcome)
      c.target match
        case "restricted" =>
          Resolver.adjustRestricted(s.fundingWallet, s.openRecords, c.amount) match
            case Left(deny) if deny.code == Codes.DebitExceedsBalance =>
              val balance = s.openRecords.find(_.status == Status.Active).fold(0L)(_.balance)
              refuse(c, "adjust", digest, deny.code, Refusals.debitExceeds(-c.amount, "bonus", balance), Map("requestedAmount" -> (-c.amount).toString, "currentBalance" -> balance.toString))
            case Left(deny) => refuseDeny(c, "adjust", digest, deny)
            case Right(plan) =>
              val forfeited = plan.closedRecords.headOption.map(id => Forfeit(id, 0L, "", cancelGrant = false))
              persistAdjusted(s.realBalance, Some(plan.recordId), forfeited, plan.recordUpdates, plan.balanceBefore, plan.balanceAfter)
        case _ =>
          if c.amount < 0 && -c.amount > s.realBalance then
            refuse(c, "adjust", digest, Codes.DebitExceedsBalance, Refusals.debitExceeds(-c.amount, "real", s.realBalance),
              Map("requestedAmount" -> (-c.amount).toString, "currentBalance" -> s.realBalance.toString))
          else
            val real = Math.addExact(s.realBalance, c.amount)
            persistAdjusted(real, None, None, Vector.empty, s.realBalance, real)
  }

  def setTurnoverOverride(c: SetTurnoverOverride): Effect[Done] =
    effects.persist(TurnoverOverrideSet(c.terms, c.actorId, c.at)).thenReply(_ => Done)

  // ── reads (FR-028, FR-030) ────────────────────────────────────────────────

  def balance: ReadOnlyEffect[Balance] =
    val s = currentState
    val primary = s.openRecords.find(_.status == Status.Active).map { r =>
      val percent = if r.releaseRequirement == 0 then 100L else math.min(100L, r.releasedProgress * 100 / r.releaseRequirement)
      PrimaryStatus(r.id, r.grantRef, r.status, r.releaseRequirement, r.releasedProgress, percent)
    }
    effects.reply(Balance(s.realBalance, s.restrictedBalance, s.currency, primary))

  def restrictedFundsStatus: ReadOnlyEffect[RestrictedFundsStatus] =
    val s = currentState
    val rows = s.openRecords.flatMap(r => s.record(r.id)).map { a =>
      val r = a.record
      RecordStatus(r.id, r.grantRef, a.bonusRef, r.status, r.priority, r.grantedAt, a.expiresAt, r.balance, r.releaseRequirement,
        r.releasedProgress, r.maxConvertible, r.remainingAllowance, r.depositsSinceRelease, r.openRoundCount,
        Resolver.forfeitPolicy(r))
    }
    effects.reply(RestrictedFundsStatus(rows, rows.find(_.status == Status.Active).orElse(rows.headOption), s.restrictedBalance))

  def depositSums(at: DepositSumsAt): ReadOnlyEffect[DepositSums] = effects.reply(currentState.depositSums(at.asOf))

  /** The first outcome under a key, if any: the deposit route asks before calling `responsible-gambling`. */
  def outcomeOf(key: String): ReadOnlyEffect[Option[Outcome]] = effects.reply(currentState.seen.outcomeOf(Key.unsafe(key)))

  def state: ReadOnlyEffect[WalletState] = effects.reply(currentState)

object WalletEntity
    extends EventSourcedEntity.Companion[WalletEntity, WalletState, WalletEvent](
      componentId = ComponentId("wallet"),
      stateSerializer = Serializer.json[WalletState]("wallet.state")(using io.eitheror.casino.wallet.WalletCodecs.stored[WalletState]),
      eventSerializer = Serializer.json[WalletEvent]("wallet.event")(using io.eitheror.casino.wallet.WalletCodecs.stored[WalletEvent])
    ):

  given Serializer[OpenMigrated]          = Codecs.serializer[OpenMigrated]("wallet.open-migrated")
  given Serializer[Deposit]               = Codecs.serializer[Deposit]("wallet.deposit")
  given Serializer[PlaceBet]              = Codecs.serializer[PlaceBet]("wallet.place-bet")
  given Serializer[CreditWin]             = Codecs.serializer[CreditWin]("wallet.credit-win")
  given Serializer[RollbackBet]           = Codecs.serializer[RollbackBet]("wallet.rollback-bet")
  given Serializer[SettleLoss]            = Codecs.serializer[SettleLoss]("wallet.settle-loss")
  given Serializer[ProviderCredit]        = Codecs.serializer[ProviderCredit]("wallet.provider-credit")
  given Serializer[Award]                 = Codecs.serializer[Award]("wallet.award")
  given Serializer[ForfeitAward]          = Codecs.serializer[ForfeitAward]("wallet.forfeit")
  given Serializer[CancelAward]           = Codecs.serializer[CancelAward]("wallet.cancel")
  given Serializer[ClosePendingCap]       = Codecs.serializer[ClosePendingCap]("wallet.close-pending-cap")
  given Serializer[SwitchPrimary]         = Codecs.serializer[SwitchPrimary]("wallet.switch")
  given Serializer[Hold]                  = Codecs.serializer[Hold]("wallet.hold")
  given Serializer[Release]               = Codecs.serializer[Release]("wallet.release")
  given Serializer[Confirm]               = Codecs.serializer[Confirm]("wallet.confirm")
  given Serializer[Reverse]               = Codecs.serializer[Reverse]("wallet.reverse")
  given Serializer[Adjust]                = Codecs.serializer[Adjust]("wallet.adjust")
  given Serializer[SetTurnoverOverride]   = Codecs.serializer[SetTurnoverOverride]("wallet.set-turnover-override")
  given Serializer[DepositSumsAt]         = Codecs.serializer[DepositSumsAt]("wallet.deposit-sums-at")
  given Serializer[PreviewHold]           = Codecs.serializer[PreviewHold]("wallet.preview-hold")
  given Serializer[Outcome]               = Codecs.serializer[Outcome]("wallet.outcome")
  given Serializer[Option[Outcome]]       = Codecs.serializer[Option[Outcome]]("wallet.outcome-of")
  given Serializer[Balance]               = Codecs.serializer[Balance]("wallet.balance")
  given Serializer[RestrictedFundsStatus] = Codecs.serializer[RestrictedFundsStatus]("wallet.restricted-funds-status")
  given Serializer[DepositSums]           = Codecs.serializer[DepositSums]("wallet.deposit-sums")
  given Serializer[HoldPreview]           = Codecs.serializer[HoldPreview]("wallet.hold-preview")
  given Serializer[WalletState]           = Serializer.json[WalletState]("wallet.state-reply")(using io.eitheror.casino.wallet.WalletCodecs.stored[WalletState])

  def create(context: EventSourcedEntityContext) = new WalletEntity(context)

  /** The wallet's id: the player and the currency; the brand is the project (FR-001). */
  def id(playerId: String, currency: String): EntityId = EntityId(s"$playerId/$currency")

  val openMigrated          = command("open-migrated")(_.openMigrated)
  val deposit               = command("deposit")(_.deposit)
  val placeBet              = command("place-bet")(_.placeBet)
  val creditWin             = command("credit-win")(_.creditWin)
  val rollbackBet           = command("rollback-bet")(_.rollbackBet)
  val settleLoss            = command("settle-loss")(_.settleLoss)
  val providerCredit        = command("provider-credit")(_.providerCredit)
  val award                 = command("award")(_.award)
  val forfeit               = command("forfeit")(_.forfeitAward)
  val cancel                = command("cancel")(_.cancelAward)
  val closePendingCap       = command("close-pending-cap")(_.closePendingCap)
  val switchPrimary         = command("switch")(_.switchPrimary)
  val hold                  = command("hold")(_.hold)
  val release               = command("release")(_.release)
  val confirm               = command("confirm")(_.confirm)
  val reverse               = command("reverse")(_.reverse)
  val adjust                = command("adjust")(_.adjust)
  val setTurnoverOverride   = command("set-turnover-override")(_.setTurnoverOverride)
  val balance               = query("balance")(_.balance)
  val restrictedFundsStatus = query("restricted-funds-status")(_.restrictedFundsStatus)
  val depositSums           = query("deposit-sums")(_.depositSums)
  val previewHold           = query("preview-hold")(_.previewHold)
  val outcomeOf             = query("outcome-of")(_.outcomeOf)
  val state                 = query("state")(_.state)

  /** The amount a command names, for `KEY_REUSED`'s message and the audit record. */
  private[entity] def amountOf(command: MoneyCommand): Option[Long] = command match
    case c: OpenMigrated   => Some(c.realBalance)
    case c: Deposit        => Some(c.amount)
    case c: PlaceBet       => Some(c.amount)
    case c: CreditWin      => Some(c.amount)
    case c: ProviderCredit => Some(c.amount)
    case c: Award          => Some(c.amount)
    case c: Hold           => Some(c.amount)
    case c: Adjust         => Some(c.amount)
    case _                 => None

  /** One round's bets as one funding split: legs summed, records in first-drawn order. */
  private[entity] def mergedLegs(bets: Vector[Bet]): Legs =
    val records = bets.flatMap(_.recordLegs).foldLeft(Vector.empty[Leg]) { (acc, l) =>
      acc.indexWhere(_.recordId == l.recordId) match
        case -1 => acc :+ l
        case i  => acc.updated(i, Leg(l.recordId, acc(i).amount + l.amount))
    }
    Legs(bets.map(_.realLeg).sum, records)

  /**
   * Each bet opened a round on every record it drew from; one win closes them all. The resolver
   * decrements once per record, so a record funding n bets of the round falls n − 1 more.
   */
  private[entity] def extraRoundDecrements(updates: Vector[RecordUpdate], bets: Vector[Bet]): Vector[RecordUpdate] =
    updates.map { u =>
      val extra = bets.count(_.recordLegs.exists(_.recordId == u.id)) - 1
      if extra > 0 then u.copy(openRoundCount = math.max(0L, u.openRoundCount - extra)) else u
    }

  /** A late rollback's record already closed this round: undo the resolver's decrement. */
  private[entity] def restoreCount(u: RecordUpdate, before: Vector[Record], bet: Bet): RecordUpdate =
    val was = before.find(_.id == u.id).fold(0L)(_.openRoundCount)
    if bet.recordLegs.exists(_.recordId == u.id) && was > 0 then u.copy(openRoundCount = was) else u
