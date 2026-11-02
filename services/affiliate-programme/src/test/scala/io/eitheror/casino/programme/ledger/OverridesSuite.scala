package io.eitheror.casino.programme.ledger

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.testkit.{EventSourcedTestKit, LogCapturing}
import io.eitheror.casino.programme.accounts.{AffiliateState, Membership, MembershipState}
import io.eitheror.casino.programme.brands.BrandState
import io.eitheror.casino.programme.commission.FactType

import java.time.Instant
import scala.collection.mutable

class OverridesSuite extends munit.FunSuite with LogCapturing:
  private val at = Instant.parse("2026-10-15T10:00:00Z")

  /** A tree of (id, parent, override bp), with the members at goldvibe named. */
  private final class Calls(tree: Seq[(String, Option[String], Int)], members: Set[String]) extends OverrideCalls:
    private val byId = tree.map((id, p, bp) => id -> AffiliateState(id, parent = p, overrideBp = bp)).toMap
    val accounts     = mutable.Map.empty[String, EventSourcedTestKit[CommissionAccount, AccountState, CommissionAccount.Event]]
    def affiliate(id: String): AffiliateState = byId.getOrElse(id, AffiliateState())
    def membership(a: String, b: String): MembershipState =
      MembershipState(a, b, Option.when(members(a))(Membership.Active))
    def brand(slug: String): BrandState = BrandState()
    def recompute(account: EntityId, c: RecomputeOverride): Unit =
      accounts.getOrElseUpdate(account.toString, EventSourcedTestKit.of(CommissionAccount, account.toString))
        .call(CommissionAccount.recomputeOverride)(c): Unit
    def overrideOf(aff: String): Long =
      accounts.get(s"$aff/goldvibe/EUR").fold(0L)(_.call(CommissionAccount.get).replyValue.sums.amount(FactType.Override))

  private def earn(c: Calls, source: String, earnings: Long) =
    OverrideWriter(c, () => at).write(s"$source/goldvibe/EUR", CommissionAccount.EarningsChanged("2026-10", earnings))

  // T1's share is the override on T2 (5%), T2's the override on T3 (3%), as the original walks it.
  private val tiers = Seq(("t1", None, 0), ("t2", Some("t1"), 500), ("t3", Some("t2"), 300))

  test("three tiers: each ancestor is written its share of the source's earnings, then the delta when they change") {
    val c = Calls(tiers, Set("t1", "t2", "t3"))
    earn(c, "t3", 100_000)
    assertEquals((c.overrideOf("t2"), c.overrideOf("t1")), (3_000L, 5_000L))
    earn(c, "t3", 150_000)
    assertEquals((c.overrideOf("t2"), c.overrideOf("t1")), (4_500L, 7_500L))
    val facts = c.accounts("t1/goldvibe/EUR").call(CommissionAccount.get).replyValue.unsettled
    assertEquals(facts.map(f => (f.id, f.amount, f.sourceAffiliate)),
      Vector(("override/t3/2026-10/1", 5_000L, Some("t3")), ("override/t3/2026-10/2", 2_500L, Some("t3"))))
  }

  test("an ancestor with no active membership at the brand is passed over; the walk goes on above it") {
    val c = Calls(tiers, Set("t1", "t3"))
    earn(c, "t3", 100_000)
    assertEquals((c.overrideOf("t2"), c.overrideOf("t1")), (0L, 5_000L))
  }

  test("a step at 0% writes nothing and does not stop the walk") {
    val c = Calls(Seq(("t1", None, 0), ("t2", Some("t1"), 500), ("t3", Some("t2"), 0)), Set("t1", "t2", "t3"))
    earn(c, "t3", 100_000)
    assertEquals((c.overrideOf("t2"), c.overrideOf("t1")), (0L, 5_000L))
  }

  test("a parent chain that forms a cycle visits each ancestor once and ends") {
    val c = Calls(Seq(("a", Some("c"), 1_000), ("b", Some("a"), 1_000), ("c", Some("b"), 1_000)), Set("a", "b", "c"))
    earn(c, "a", 10_000)
    assertEquals(c.accounts.keySet.toSet, Set("b/goldvibe/EUR", "c/goldvibe/EUR"))
    assertEquals((c.overrideOf("c"), c.overrideOf("b")), (1_000L, 1_000L))
  }
