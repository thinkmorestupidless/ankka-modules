package io.eitheror.casino.wallet.entity

import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.wallet.funding.Status
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}

import java.time.Instant

/**
 * data-model.md's invariants, held after every step of random sequences of every money command:
 * restricted balance is the sum of open records', no record goes negative, an open record's open
 * round count is the open bets drawing on it, at most one hold is pending, deposit turnover pending
 * never falls below zero, real balance never goes below zero without a correction debit, and a key,
 * once seen, is kept while fewer than `RecentKeys` later keys have been seen (every sequence here is far shorter).
 */
class InvariantsSuite extends ScalaCheckSuite with LogCapturing:

  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(200)

  private val t0 = Instant.parse("2026-10-09T10:00:00Z")

  private sealed trait Step
  private final case class DepositS(amount: Long, multiplier: Long) extends Step
  private final case class BetS(amount: Long, round: Int) extends Step
  private final case class WinS(amount: Long, round: Int) extends Step
  private final case class RollbackS(round: Int) extends Step
  private final case class LossS(round: Int) extends Step
  private final case class AwardS(amount: Long, multiplier: Long, policy: String, order: String) extends Step
  private case object ForfeitS extends Step
  private case object CancelS extends Step
  private case object CloseCapS extends Step
  private final case class HoldS(amount: Long) extends Step
  private final case class ReleaseS(as: String) extends Step
  private case object ConfirmS extends Step
  private case object ReverseS extends Step
  private final case class AdjustS(target: String, amount: Long) extends Step
  private final case class CreditS(amount: Long) extends Step
  private final case class RepeatS(back: Int) extends Step
  /** A provider's correction debit: a bet that may take real balance negative (spec 025 FR-003). */
  private final case class CorrectionS(amount: Long, round: Int) extends Step

  private val step: Gen[Step] = Gen.frequency(
    4 -> Gen.zip(Gen.choose(0L, 5000L), Gen.oneOf(0L, 0L, 1L)).map(DepositS.apply),
    8 -> Gen.zip(Gen.choose(0L, 3000L), Gen.choose(1, 4)).map(BetS.apply),
    4 -> Gen.zip(Gen.choose(0L, 6000L), Gen.choose(1, 4)).map(WinS.apply),
    2 -> Gen.choose(1, 4).map(RollbackS.apply),
    2 -> Gen.choose(1, 4).map(LossS.apply),
    3 -> Gen.zip(Gen.choose(1L, 3000L), Gen.choose(0L, 3L), Gen.oneOf("", "none", "funds", "cancel_promotion"),
      Gen.oneOf("", "restricted_first", "proportional")).map(AwardS.apply),
    1 -> Gen.const(ForfeitS),
    1 -> Gen.const(CancelS),
    1 -> Gen.const(CloseCapS),
    2 -> Gen.choose(1L, 4000L).map(HoldS.apply),
    1 -> Gen.oneOf("cancelled", "rejected", "failed").map(ReleaseS.apply),
    1 -> Gen.const(ConfirmS),
    1 -> Gen.const(ReverseS),
    1 -> Gen.zip(Gen.oneOf("real", "restricted"), Gen.choose(-2000L, 2000L)).map(AdjustS.apply),
    1 -> Gen.choose(0L, 1000L).map(CreditS.apply),
    2 -> Gen.choose(1, 5).map(RepeatS.apply)
  )

  private def run(kit: EventSourcedTestKit[WalletEntity, WalletState, WalletEvent], steps: List[Step],
      checkStep: (WalletState, Set[String], String) => Unit = check(_, _, _)): Unit =
    var sent = Vector.empty[MoneyCommand]
    steps.zipWithIndex.foreach { (s, i) =>
      val at  = t0.plusSeconds(i.toLong * 60)
      val key = s"k-$i"
      val st  = kit.currentState
      def pending = st.pendingRequestId.getOrElse("none")
      def confirmed = st.withdrawals.values.find(_.status == RequestStatus.Confirmed).fold("none")(_.requestId)
      val command: MoneyCommand = s match
        case DepositS(a, m)   => Deposit(key, a, "psp", "t1", "psp", "card", TurnoverTerms(m, "gross_wagered"), at)
        case BetS(a, r)       => PlaceBet(key, s"r$r", "slot", "s1", "prov", "slots", a, allowNegative = false, BetSwitches(), at)
        case CorrectionS(a, r) => PlaceBet(key, s"r$r", "slot", "s1", "prov", "slots", a, allowNegative = true, BetSwitches(), at)
        case WinS(a, r)       => CreditWin(key, s"r$r", a, at)
        case RollbackS(r)     => RollbackBet(key, s"r$r", None, at)
        case LossS(r)         => SettleLoss(key, s"r$r", at)
        case AwardS(a, m, p, o) => Award(key, s"bonus-$i", a, m, a * 2, i.toLong, None, Rules(forfeitOnWithdrawal = p, fundingOrder = o), None, at)
        case ForfeitS         => ForfeitAward(key, None, at)
        case CancelS          => CancelAward(key, None, at)
        case CloseCapS        => ClosePendingCap(key, at)
        case HoldS(a)         => Hold(key, a, playerConfirmed = true, at)
        case ReleaseS(as)     => Release(key, pending, as, at)
        case ConfirmS         => Confirm(key, pending, at)
        case ReverseS         => Reverse(key, confirmed, at)
        case AdjustS(t, a)    => Adjust(key, t, a, "a reason", "correction", "op-1", at)
        case CreditS(a)       => ProviderCredit(key, a, s"pr-$i", "free spins", "st8", at)
        case RepeatS(back)    => sent.lift(sent.size - back).getOrElse(Deposit(key, 1, "psp", "t1", "psp", "card", TurnoverTerms(0, "gross_wagered"), at))
      val seenBefore = kit.currentState.seen.seen.keySet
      call(kit, command)
      sent :+= command
      if s.isInstanceOf[CorrectionS] then sawCorrection = true
      checkStep(kit.currentState, seenBefore, s"after step $i: $s")
    }

  private def call(kit: EventSourcedTestKit[WalletEntity, WalletState, WalletEvent], c: MoneyCommand): Unit = c match
    case x: Deposit         => kit.call(WalletEntity.deposit)(x): Unit
    case x: PlaceBet        => kit.call(WalletEntity.placeBet)(x): Unit
    case x: CreditWin       => kit.call(WalletEntity.creditWin)(x): Unit
    case x: RollbackBet     => kit.call(WalletEntity.rollbackBet)(x): Unit
    case x: SettleLoss      => kit.call(WalletEntity.settleLoss)(x): Unit
    case x: Award           => kit.call(WalletEntity.award)(x): Unit
    case x: ForfeitAward    => kit.call(WalletEntity.forfeit)(x): Unit
    case x: CancelAward     => kit.call(WalletEntity.cancel)(x): Unit
    case x: ClosePendingCap => kit.call(WalletEntity.closePendingCap)(x): Unit
    case x: Hold            => kit.call(WalletEntity.hold)(x): Unit
    case x: Release         => kit.call(WalletEntity.release)(x): Unit
    case x: Confirm         => kit.call(WalletEntity.confirm)(x): Unit
    case x: Reverse         => kit.call(WalletEntity.reverse)(x): Unit
    case x: Adjust          => kit.call(WalletEntity.adjust)(x): Unit
    case x: ProviderCredit  => kit.call(WalletEntity.providerCredit)(x): Unit
    case other              => fail(s"not generated: $other")

  private def check(s: WalletState, seenBefore: Set[String], where: String): Unit =
    val open = s.records.filter(a => Status.isOpen(a.status))
    assertEquals(s.restrictedBalance, open.map(_.record.balance).sum, s"restricted balance is the sum of open records', $where")
    assert(s.records.forall(_.record.balance >= 0), s"a record went negative $where: ${s.records}")
    open.foreach { a =>
      val drawing = s.openBets.count(_.recordLegs.exists(_.recordId == a.id)).toLong
      assertEquals(a.record.openRoundCount, drawing, s"record ${a.id}'s open round count $where")
    }
    assert(s.withdrawals.values.count(_.status == RequestStatus.Pending) <= 1, s"two holds pending $where")
    assertEquals(s.pendingRequestId.isDefined, s.withdrawals.values.exists(_.status == RequestStatus.Pending), where)
    assert(s.depositTurnoverPending >= 0, s"deposit turnover pending below zero $where")
    assert(s.realBalance >= 0, s"real balance below zero with no correction debit $where: ${s.realBalance}")
    assert(seenBefore.subsetOf(s.seen.seen.keySet), s"a seen key was forgotten $where")
    checkPools(s, where)
    assert(s.convertedPool >= 0, s"converted pool below zero with no correction debit $where: ${s.pools}")

  /**
   * Spec 025 FR-001, FR-012: the two pools sum to real balance and the deposit pool is never
   * negative, after every event; and no command was refused for a drifted pair, which would mean the
   * wallet wrote one. `negativeDebit` admits the one other refusal: a bet funded proportionally on a
   * negative real balance has a negative real leg, which the original's `debit` refuses as well.
   */
  private def checkPools(s: WalletState, where: String, negativeDebit: Boolean = false): Unit =
    assertEquals(s.depositPool + s.convertedPool, s.realBalance, s"deposit + converted == real balance $where: ${s.pools}")
    assert(s.depositPool >= 0, s"deposit pool below zero $where: ${s.pools}")
    val drifted = s.seen.seen.values.map(_.outcome)
      .filter(_.code.contains(io.eitheror.casino.wallet.funding.Codes.PoolsInvariant))
      .filterNot(o => negativeDebit && o.message.exists(_.endsWith("negative debit")))
    assert(drifted.isEmpty, s"a command was refused POOLS_INVARIANT $where: $drifted")

  /** With correction debits, real balance and the converted pool may go negative; the pools still hold. */
  private def checkWithCorrections(s: WalletState, seenBefore: Set[String], where: String): Unit =
    checkPools(s, where, negativeDebit = sawCorrection)
    assert(s.convertedPool >= 0 || sawCorrection, s"converted pool negative with no correction debit $where")
    assert(seenBefore.subsetOf(s.seen.seen.keySet), s"a seen key was forgotten $where")

  private var sawCorrection = false

  private var accepted = 0L
  private var refused  = 0L

  property("every invariant holds after every step of any sequence of money commands") {
    Prop.forAll(Gen.choose(5, 60).flatMap(n => Gen.listOfN(n, step))) { steps =>
      val kit = EventSourcedTestKit.of(WalletEntity, "inv/EUR")
      kit.call(WalletEntity.deposit)(Deposit("seed", 5000, "psp", "t1", "psp", "card", TurnoverTerms(0, "gross_wagered"), t0))
      run(kit, steps)
      val outcomes = kit.currentState.seen.seen.values.map(_.outcome)
      accepted += outcomes.count(_.accepted)
      refused += outcomes.count(!_.accepted)
      true
    }
  }

  property("the pools hold after every step when correction debits take real balance negative") {
    val withCorrections = Gen.frequency(9 -> step, 2 -> Gen.zip(Gen.choose(1L, 9000L), Gen.choose(1, 4)).map(CorrectionS.apply))
    Prop.forAll(Gen.choose(5, 60).flatMap(n => Gen.listOfN(n, withCorrections))) { steps =>
      val kit = EventSourcedTestKit.of(WalletEntity, "inv/EUR")
      kit.call(WalletEntity.deposit)(Deposit("seed", 5000, "psp", "t1", "psp", "card", TurnoverTerms(0, "gross_wagered"), t0))
      sawCorrection = false
      run(kit, steps, checkWithCorrections)
      if kit.currentState.convertedPool < 0 then negativeConverted += 1
      true
    }
  }

  private var negativeConverted = 0

  test("the correction sequences reached a negative converted pool") {
    assert(negativeConverted > 0, "no sequence took the converted pool negative: the property tested nothing new")
  }

  // Found by the correction property on 2026-10-28: real -4000 after a correction debit, a
  // proportional record of 5471, a bet of 1000. The original's fundProportional gives a real leg of
  // 1000 * -4000 / 1471 = -2719, and its pools `debit` refuses a negative amount; so does the rebuild.
  test("a proportional bet on a negative real balance is refused POOLS_INVARIANT, as the original's debit refuses it") {
    val kit = EventSourcedTestKit.of(WalletEntity, "inv/EUR")
    kit.call(WalletEntity.deposit)(Deposit("seed", 5000, "psp", "t1", "psp", "card", TurnoverTerms(0, "gross_wagered"), t0))
    sawCorrection = false
    run(kit, List(CorrectionS(9000, 1), AwardS(5471, 1, "", "proportional")), checkWithCorrections)
    val before = kit.currentState.pools
    assertEquals(kit.currentState.realBalance, -4000L)
    val r = kit.call(WalletEntity.placeBet)(PlaceBet("neg-bet", "r2", "slot", "s1", "prov", "slots", 1000, allowNegative = false, BetSwitches(), t0))
    assertEquals(r.replyValue.code, Some(io.eitheror.casino.wallet.funding.Codes.PoolsInvariant))
    assertEquals(r.replyValue.message, Some("real-money pools: negative debit"))
    assertEquals(kit.currentState.pools, before)
  }

  test("the sequences exercised the money path: most commands were accepted, some refused") {
    // A generator that only produced refusals would pass every invariant while testing nothing.
    assert(accepted > refused && refused > 0, s"accepted $accepted, refused $refused")
  }

  test("the checks catch a corrupted wallet") {
    val bet = Bet("b", "r1", "t", 100, 0, Vector(io.eitheror.casino.wallet.funding.Leg("rf", 100)), Vector.empty, "bonus", BetStatus.Open, "", "", t0)
    val record = AwardedRecord(io.eitheror.casino.wallet.funding.Record("rf", balance = 50, openRoundCount = 0), "b", None, t0, None)
    intercept[AssertionError](check(WalletState("x/EUR", records = Vector(record), openBets = Vector(bet)), Set.empty, "planted"))
    intercept[AssertionError](check(WalletState("x/EUR", realBalance = -1), Set.empty, "planted"))
    intercept[AssertionError](check(WalletState("x/EUR"), Set("k-1"), "planted"))
    intercept[AssertionError](check(WalletState("x/EUR", realBalance = 100, depositPool = 60, convertedPool = 30), Set.empty, "planted"))
    intercept[AssertionError](check(WalletState("x/EUR", realBalance = 100, depositPool = -10, convertedPool = 110), Set.empty, "planted"))
  }
