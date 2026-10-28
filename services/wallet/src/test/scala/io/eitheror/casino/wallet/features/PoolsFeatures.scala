package io.eitheror.casino.wallet.features

import io.eitheror.casino.wallet.entity.*
import io.eitheror.casino.wallet.entity.WalletEvent.*
import io.eitheror.casino.wallet.funding.{Parts, Pools, Status}
import io.eitheror.casino.wallet.publish.Facts

/**
 * features/wallet/pools.feature (spec 025): real balance as a deposit pool and a converted pool.
 * A wallet a scenario describes opens with the pools it names (`OpenMigrated`), so each scenario
 * starts from exactly the pools it states.
 */
class PoolsFeatures extends WalletFeatures("pools.feature"):

  /** A hold a Given describes by its parts: placed once the pools left after it are known. */
  private var pendingHold: Option[(Long, String, Parts)] = None
  /** The opening the migration scenarios run: real balance and the original's pools, if it had any. */
  private var migrated: Option[(Long, Option[Pools])] = None
  /** The states after each movement of the invariant scenario. */
  private var after: Vector[WalletState] = Vector.empty
  private var balance: Balance           = null

  override def beforeEach(context: BeforeEach): Unit =
    super.beforeEach(context)
    pendingHold = None; migrated = None; after = Vector.empty; balance = null

  private def parts(d: String, c: String): Parts = Parts(long(d), long(c))

  private def pools(d: Long, c: Long): Unit = seeding(_.copy(real = d + c, pools = Some((d, c))))

  private def last[A](pf: PartialFunction[WalletEvent, A]): A =
    lastEvents.collectFirst(pf).getOrElse(fail(s"no such event in $lastEvents ($lastOutcome)"))

  private def placedHold: HoldPlaced = last { case e: HoldPlaced => e }

  private def rolledBack(round: String): Result = run(walletKit.call(WalletEntity.rollbackBet)(RollbackBet(nextKey("rb"), round, None, now())))

  // ── the pools ─────────────────────────────────────────────────────────────

  /**
   * One text, three uses: before the wallet is used it opens it with these pools; after a hold
   * described by its parts it is the pools that hold leaves (so the wallet opens with the hold's
   * parts added back, then holds); after anything else it says the pools are these.
   */
  Given("a deposit pool of {string} and a converted pool of {string}") { (d: String, c: String) =>
    pendingHold match
      case Some((amount, request, paid)) =>
        pendingHold = None
        pools(long(d) + paid.deposit, long(c) + paid.converted)
        val held = hold(amount, request)
        assertEquals(held.events.collectFirst { case e: HoldPlaced => e.paidParts }, Some(paid), held.outcome.toString)
      case None if opened => assertEquals(state.pools, Pools(long(d), long(c)))
      case None           => pools(long(d), long(c))
  }

  Given("a deposit pool of {string} and a converted pool of {string}, no restricted funds and no deposit turnover pending") {
    (d: String, c: String) => pools(long(d), long(c))
  }

  Given("no active restricted funds record, a deposit pool of {string} and a converted pool of {string}") { (d: String, c: String) =>
    pools(long(d), long(c))
  }

  Then("the deposit pool is {string} and the converted pool is {string}") { (d: String, c: String) =>
    assertEquals(state.pools, Pools(long(d), long(c)))
    assertEquals(state.pools.total, state.realBalance)
  }

  Then("the deposit pool rises by {string} and the converted pool rises by {string}") { (d: String, c: String) =>
    assertEquals((state.depositPool - before.depositPool, state.convertedPool - before.convertedPool), (long(d), long(c)))
  }

  Then("the converted pool rises by {string}") { (c: String) =>
    assertEquals((state.depositPool - before.depositPool, state.convertedPool - before.convertedPool), (0L, long(c)))
  }

  Then("the deposit pool and the converted pool are unchanged") { () => assertEquals(state.pools, before.pools) }

  // ── stakes, wins, rollbacks, losses ─────────────────────────────────────

  Then("the bet's parts are deposit {string} and converted {string}") { (d: String, c: String) =>
    assertEquals(last { case e: BetPlaced => e.bet.parts }, Some(parts(d, c)))
    assertEquals(lastOutcome.parts, Some(parts(d, c)))
  }

  When("the player places a bet of {string} funded only from restricted funds") { (a: String) =>
    editSeedRecord(focusId)(_.copy(fundingOrder = "restricted_first"))
    act(bet(long(a), "r1")): Unit
    assertEquals(lastOutcome.realLeg, Some(0L))
  }

  Given("an active restricted funds record of {string} with a wagering requirement of {string} and release progress of {string} at a contribution rate of {string}") {
    (b: String, req: String, progress: String, rate: String) =>
      addRecord(record("r1", long(b), edit = _.copy(releaseRequirement = long(req), releasedProgress = long(progress),
        contributionDefault = Some(long(rate)), maxConvertible = long(b) * 10)))
  }

  When("the player places a bet of {string} funded from real balance") { (a: String) =>
    act(bet(long(a), "r1")): Unit
    assertEquals(lastOutcome.realLeg, Some(long(a)))
  }

  Then("the record converts: its balance is credited to real balance") { () =>
    val was = recordBefore(focusId)
    assertEquals(focused.status, Status.ConvertedPendingCap)
    assertEquals(state.realBalance, before.realBalance - lastOutcome.realLeg.getOrElse(0L) + was.balance)
  }

  Then("the converted pool received {string} before the real leg was split") { (c: String) =>
    val placed = last { case e: BetPlaced => e }
    assertEquals(placed.conversions.map(_.amount).sum, long(c))
    val split = placed.bet.parts.getOrElse(fail("the bet recorded no parts"))
    // The leg was taken from a converted pool that already held the conversion.
    assertEquals(state.convertedPool, before.convertedPool + long(c) - split.converted)
  }

  /** A real-funded bet whose parts come out as given: the converted part is all the converted pool holds. */
  Given("round {string} is open with a bet whose parts are deposit {string} and converted {string}") { (round: String, d: String, c: String) =>
    pools(long(d) + 4000L, long(c))
    val placed = bet(long(d) + long(c), round)
    assertEquals(placed.outcome.parts, Some(parts(d, c)), placed.outcome.toString)
  }


  Then("the win's parts are deposit {string} and converted {string}") { (d: String, c: String) =>
    assertEquals(last { case e: WinCredited => e.parts }, parts(d, c))
  }

  Given("round {string} is open with a bet of {string} funded only from an active restricted funds record") { (round: String, a: String) =>
    addRecord(record("r1", long(a) * 2))
    val placed = bet(long(a), round)
    assertEquals(placed.outcome.realLeg, Some(0L), placed.outcome.toString)
  }

  Then("the record receives {string}") { (a: String) => assertEquals(focused.balance - recordBefore(focusId).balance, long(a)) }

  Given("round {string} is open with a bet of {string} funded only from a restricted funds record that has since been forfeited") {
    (round: String, a: String) =>
      addRecord(record("r1", long(a)))
      val placed = bet(long(a), round)
      assertEquals(placed.outcome.realLeg, Some(0L), placed.outcome.toString)
      // A record emptied by its bet forfeits once its round settles; here it is forfeited while the round is open.
      val forfeited = run(walletKit.call(WalletEntity.forfeit)(ForfeitAward(nextKey("forfeit"), None, now())))
      assert(!Status.isOpen(focused.status), s"the record is still open: $focused (${forfeited.outcome})")
  }

  Given("a deposit of {string} under payment reference {string} was confirmed since") { (a: String, ref: String) =>
    assert(deposit(long(a), ref).outcome.accepted)
  }

  When("the bet in round {string} is rolled back") { (round: String) => act(rolledBack(round)): Unit }

  Then("the rollback's parts are deposit {string} and converted {string}") { (d: String, c: String) =>
    assertEquals(last { case e: BetRolledBack => e.parts }, parts(d, c))
  }

  When("a correction debit of {string} is placed allowing a negative balance") { (a: String) =>
    act(bet(long(a), "r-correction", allowNegative = true)): Unit
  }

  Then("the debit's parts are deposit {string} and converted {string}") { (d: String, c: String) =>
    val p = last {
      case e: BetPlaced     => e.bet.parts.getOrElse(Parts.zero)
      case e: AdminAdjusted => e.parts
    }
    assertEquals(p, parts(d, c))
  }

  // ── money in ──────────────────────────────────────────────────────────────

  When("a deposit of {string} under payment reference {string} is confirmed") { (a: String, ref: String) =>
    act(deposit(long(a), ref)): Unit
  }

  Given("deposit turnover pending of {string}") { (p: String) => seeding(_.copy(pending = long(p))) }

  Then("deposit turnover pending is {string}") { (p: String) => assertEquals(state.depositTurnoverPending, long(p)) }

  When("an operator credits {string} to real balance with reason {string}") { (a: String, reason: String) =>
    act(adjust("real", long(a), reason)): Unit
  }

  When("an operator debits {string} from real balance with reason {string}") { (a: String, reason: String) =>
    act(adjust("real", -long(a), reason)): Unit
  }

  When("a provider credit of {string} arrives") { (a: String) =>
    act(run(walletKit.call(WalletEntity.providerCredit)(ProviderCredit(nextKey("pc"), long(a), "fs-1", "free spins", "st8", now())))): Unit
  }

  // ── holds ─────────────────────────────────────────────────────────────────

  When("a hold of {string} is placed for request {string}") { (a: String, request: String) => act(hold(long(a), request)): Unit }

  Then("the hold's paid parts are deposit {string} and converted {string}") { (d: String, c: String) =>
    assertEquals(placedHold.paidParts, parts(d, c))
  }

  Given("a restricted funds record awaiting its cap with deposits since conversion of {string} and a remaining allowance of {string}") {
    (d: String, a: String) =>
      addRecord(record("cap", 0L, status = Status.ConvertedPendingCap, edit = _.copy(depositsSinceRelease = long(d), remainingAllowance = long(a))))
  }

  Given("an active restricted funds record under withdrawal policy {string} with a clawback of {string} computed for it") {
    (policy: String, c: String) =>
      addRecord(record("active", 1000L, priority = 1L, edit = _.copy(forfeitOnWithdrawal = policy)))
      clawbacks += focusId -> long(c)
  }

  Then("the clawback's parts are deposit {string} and converted {string}") { (d: String, c: String) =>
    val p = last {
      case e: HoldPlaced               => e.clawbackParts
      case e: RestrictedFundsCancelled => e.parts
    }
    assertEquals(p, parts(d, c))
  }

  Then("{string} is voided as {string} with parts deposit {string} and converted {string}") { (v: String, kind: String, d: String, c: String) =>
    assertEquals(lastOutcome.voided, Some(long(v)))
    assert(rowsWritten.exists(r => r.`type` == kind && r.amount == long(v)), rowsWritten.toString)
    assertEquals(placedHold.voidedParts, parts(d, c))
  }

  Then("the paid amount {string} has parts deposit {string} and converted {string}") { (p: String, d: String, c: String) =>
    assertEquals(lastOutcome.paid, Some(long(p)))
    assertEquals(placedHold.paidParts, parts(d, c))
  }

  Given("a hold of {string} is pending for request {string} with paid parts deposit {string} and converted {string}") {
    (a: String, request: String, d: String, c: String) => pendingHold = Some((long(a), request, parts(d, c)))
  }

  When("request {string} is released as {word}") { (request: String, outcome: String) =>
    act(run(walletKit.call(WalletEntity.release)(Release(nextKey("release"), request, outcome, now())))): Unit
  }

  Given("request {string} of {string} was confirmed with paid parts deposit {string} and converted {string}") {
    (request: String, a: String, d: String, c: String) =>
      pools(long(d), long(c))
      val held = hold(long(a), request)
      assertEquals(held.events.collectFirst { case e: HoldPlaced => e.paidParts }, Some(parts(d, c)), held.outcome.toString)
      run(walletKit.call(WalletEntity.confirm)(Confirm(s"confirm-$request", request, now()))): Unit
  }

  When("request {string} is reversed") { (request: String) =>
    act(run(walletKit.call(WalletEntity.reverse)(Reverse(s"reverse-$request", request, now())))): Unit
  }

  // ── cancelling an award ───────────────────────────────────────────────────

  Given("an active restricted funds record whose clawback computed from its baseline is {string}") { (c: String) =>
    addRecord(record("r1", 1000L))
    clawbacks += focusId -> long(c)
  }

  When("the award is cancelled") { () =>
    act(run(walletKit.call(WalletEntity.cancel)(CancelAward(nextKey("cancel"), None, now())))): Unit
  }

  // ── the invariant ─────────────────────────────────────────────────────────

  When("a bet of {string} in round {string}, its win of {string}, a deposit of {string} under payment reference {string} and a hold of {string} are applied in turn") {
    (b: String, round: String, w: String, d: String, ref: String, h: String) =>
      // Turnover is not this scenario's subject: a deposit that set some would refuse the hold.
      brandTerms = TurnoverTerms(0L, "gross_wagered")
      val steps: Vector[() => Result] = Vector(
        () => bet(long(b), round),
        () => run(walletKit.call(WalletEntity.creditWin)(CreditWin(nextKey("win"), round, long(w), now()))),
        () => deposit(long(d), ref),
        () => hold(long(h), "w1")
      )
      steps.foreach { s =>
        val r = act(s())
        assert(r.outcome.accepted, r.outcome.toString)
        after :+= state
      }
  }

  Then("after each the deposit pool plus the converted pool equals the real balance") { () =>
    assertEquals(after.size, 4)
    after.foreach(s => assertEquals(s.depositPool + s.convertedPool, s.realBalance, s.pools.toString))
  }

  // ── what readers see ──────────────────────────────────────────────────────

  private def wire(fact: AnyRef): String =
    fact.getClass.getSimpleName.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase

  private def field(fact: Product, name: String): Any =
    fact.productElementNames.zip(fact.productIterator).collectFirst { case (`name`, v) => v }.getOrElse(fail(s"no field $name on $fact"))

  private var fact: Product = null

  Then("the {string} fact carries parts deposit {string} and converted {string}") { (kind: String, d: String, c: String) =>
    val facts = lastEvents.flatMap(Facts.moneyTransactions(state.walletId, _)).map(_.fact)
    fact = facts.find(x => wire(x) == kind).getOrElse(fail(s"no $kind fact in ${facts.map(wire)}")).asInstanceOf[Product]
    assertEquals((field(fact, "depositPartCents"), field(fact, "convertedPartCents")), (Some(long(d)), Some(long(c))))
  }

  Then("it carries the deposit pool after as {string} and the converted pool after as {string}") { (d: String, c: String) =>
    assertEquals((field(fact, "depositPoolAfter"), field(fact, "convertedPoolAfter")), (Some(long(d)), Some(long(c))))
  }

  When("the balance is read by the operator") { () => balance = walletKit.call(WalletEntity.balance).replyValue }

  Then("the deposit pool {string} and the converted pool {string} are returned beside the real balance {string}") {
    (d: String, c: String, r: String) => assertEquals((balance.depositPoolBalance, balance.convertedPoolBalance, balance.realBalance), (long(d), long(c), long(r)))
  }

  // ── the migration ─────────────────────────────────────────────────────────

  Given("the migration of brand {string} from the original") { (_: String) => noWallet = true }

  Given("a player with a real balance of {string} whose original wallet carries a deposit pool of {string} and a converted pool of {string}") {
    (r: String, d: String, c: String) => migrated = Some((long(r), Some(Pools(long(d), long(c)))))
  }

  Given("a player with a real balance of {string} whose original wallet carries no deposit pool and no converted pool") { (r: String) =>
    migrated = Some((long(r), None))
  }

  When("the migration has run") { () =>
    val (real, original) = migrated.getOrElse(fail("no wallet to migrate"))
    val opened = walletKit.call(WalletEntity.openMigrated)(OpenMigrated("migrate", real, 0L, Vector.empty, 0L, 0, 0L, now(),
      depositPool = original.map(_.deposit), convertedPool = original.map(_.converted)))
    assert(opened.replyValue.accepted, opened.replyValue.toString)
  }
