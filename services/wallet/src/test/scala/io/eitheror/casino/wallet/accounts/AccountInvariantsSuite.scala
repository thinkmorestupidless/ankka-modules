package io.eitheror.casino.wallet.accounts

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}

import java.time.Instant

/**
 * data-model.md's account invariants, after every step of random sequences: the balance never below
 * the floor, no credit line on a pool account, the owner never changed, a key kept while fewer than
 * `RecentKeys` later keys have been seen.
 */
class AccountInvariantsSuite extends ScalaCheckSuite:

  override def scalaCheckTestParameters = super.scalaCheckTestParameters.withMinSuccessfulTests(200)

  private val t0    = Instant.parse("2026-10-25T10:00:00Z")
  private val owner = "service:p/owner"

  private sealed trait Step
  private final case class ClaimS(by: String) extends Step
  private final case class DebitS(amount: Long, by: String) extends Step
  private final case class CreditS(amount: Long, by: String) extends Step
  private final case class AdjustS(amount: Long) extends Step
  private final case class LineS(line: Long, by: String) extends Step
  private final case class RepeatS(back: Int) extends Step

  private val who = Gen.frequency(9 -> Gen.const(owner), 1 -> Gen.const("service:p/other"))

  private val step: Gen[Step] = Gen.frequency(
    1 -> who.map(ClaimS.apply),
    4 -> Gen.zip(Gen.choose(1L, 800L), who).map(DebitS.apply),
    4 -> Gen.zip(Gen.choose(1L, 800L), who).map(CreditS.apply),
    2 -> Gen.choose(-800L, 800L).map(AdjustS.apply),
    2 -> Gen.zip(Gen.choose(0L, 1000L), who).map(LineS.apply),
    2 -> Gen.choose(1, 5).map(RepeatS.apply)
  )

  private def check(s: AccountState, firstOwner: Option[String], seenBefore: Set[String], where: String): Unit =
    assert(s.balance >= s.floor, s"balance ${s.balance} below the floor ${s.floor} $where")
    assert(s.creditLine >= 0, s"a negative credit line $where")
    if s.kind == AccountState.Pool then assertEquals(s.creditLine, 0L, s"a pool account with a credit line $where")
    firstOwner.foreach(o => assertEquals(s.owner, o, s"the owner changed $where"))
    assert(seenBefore.subsetOf(s.seen.seen.keySet), s"a seen key was forgotten $where")

  private var moved = 0L

  for accountId <- Vector("pool/m1/EUR", "clearing/north/EUR") do
    property(s"$accountId: every invariant holds after every step of any sequence") {
      Prop.forAll(Gen.choose(5, 60).flatMap(n => Gen.listOfN(n, step))) { steps =>
        val kit  = EventSourcedTestKit.of(AccountEntity, accountId)
        var sent = Vector.empty[(Step, String)]
        var firstOwner = Option.empty[String]
        steps.zipWithIndex.foreach { (s, i) =>
          val key = s"k-$i"
          val seenBefore = kit.currentState.seen.seen.keySet
          def send(step: Step, key: String): Unit = step match
            case ClaimS(by)     => kit.call(AccountEntity.claim)(Claim(by, t0)): Unit
            case DebitS(a, by)  => kit.call(AccountEntity.debit)(Debit(key, by, a, "merge", "ada", key, t0)): Unit
            case CreditS(a, by) => kit.call(AccountEntity.credit)(Credit(key, by, a, "split", "ada", key, t0)): Unit
            case AdjustS(a)     => kit.call(AccountEntity.adjust)(AdjustAccount(key, a, "a reason", "correction", "op-1", t0)): Unit
            case LineS(l, by)   => kit.call(AccountEntity.setCreditLine)(SetCreditLine(l, by, t0)): Unit
            case RepeatS(back)  => sent.lift(sent.size - back).foreach((first, firstKey) => send(first, firstKey))
          send(s, key)
          if !s.isInstanceOf[RepeatS] then sent :+= (s -> key)
          if firstOwner.isEmpty && kit.currentState.created then firstOwner = Some(kit.currentState.owner)
          check(kit.currentState, firstOwner, seenBefore, s"after step $i: $s")
        }
        moved += kit.allEvents.count(e => e.isInstanceOf[AccountEvent.AccountDebited] || e.isInstanceOf[AccountEvent.AccountCredited])
        true
      }
    }

  test("the sequences moved money") {
    assert(moved > 0, "a generator that never moved money would pass every invariant while testing nothing")
  }

  test("the checks catch a corrupted account") {
    intercept[AssertionError](check(AccountState("pool/m1/EUR", created = true, balance = -1), None, Set.empty, "planted"))
    intercept[AssertionError](check(AccountState("pool/m1/EUR", creditLine = 10), None, Set.empty, "planted"))
    intercept[AssertionError](check(AccountState("clearing/n/EUR", owner = "b"), Some("a"), Set.empty, "planted"))
  }
