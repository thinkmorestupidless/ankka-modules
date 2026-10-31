package io.eitheror.casino.kyc.consumers

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit
import io.eitheror.casino.kyc.consumers.PolicyFlagger.Search
import io.eitheror.casino.kyc.policy.Profiles
import io.eitheror.casino.kyc.record.{CountDeposit, FlagForPolicy, FlagReason, KycRecord}
import io.eitheror.casino.kyc.record.KycEvent.Flagged

import java.time.Instant

/** FR-022: a policy that newly puts a player over a threshold flags them, once. */
class PolicyFlaggerSuite extends munit.FunSuite:
  private val at  = Instant.parse("2026-10-30T12:00:00Z")
  private val mga = Profiles.Mga // EUR, a deposit threshold of 200000

  test("a threshold set, lowered or moved to another currency is searched for; raised or unchanged is not"):
    val none = mga.copy(depositThreshold = None, sofThreshold = None)
    assertEquals(PolicyFlagger.searches(none, none.copy(depositThreshold = Some(1000))), Vector(Search.Threshold("EUR", 1000)))
    assertEquals(PolicyFlagger.searches(none.copy(sofThreshold = Some(900)), none.copy(sofThreshold = Some(500))), Vector(Search.Sof("EUR", 500)))
    assertEquals(PolicyFlagger.searches(mga, mga.copy(depositThreshold = Some(300000))), Vector.empty)
    assertEquals(PolicyFlagger.searches(mga, mga), Vector.empty)
    assertEquals(PolicyFlagger.searches(mga, mga.copy(currency = Some("GBP"))).collect { case s: Search.Threshold => s }, Vector(Search.Threshold("GBP", 200000)))

  test("no KYC currency, no search"):
    assertEquals(PolicyFlagger.searches(mga, mga.copy(currency = None, depositThreshold = Some(1))), Vector.empty)

  test("a player over a lowered threshold is flagged once, however often the policy is saved"):
    val kit     = EventSourcedTestKit.of(KycRecord, "p-1")
    kit.call(KycRecord.countDeposit)(CountDeposit("p-1", "EUR", 5000, 1, mga, at)): Unit
    val lowered = mga.copy(depositThreshold = Some(1000))
    val first   = kit.call(KycRecord.flagForPolicy)(FlagForPolicy(lowered, at))
    assertEquals(first.events.collect { case f: Flagged => f.reason }, Vector(FlagReason.Threshold))
    assertEquals(kit.call(KycRecord.flagForPolicy)(FlagForPolicy(lowered, at.plusSeconds(1))).events, Vector.empty)

  test("a player under the new threshold is not flagged"):
    val kit = EventSourcedTestKit.of(KycRecord, "p-2")
    kit.call(KycRecord.countDeposit)(CountDeposit("p-2", "EUR", 500, 1, mga, at)): Unit
    assertEquals(kit.call(KycRecord.flagForPolicy)(FlagForPolicy(mga.copy(depositThreshold = Some(1000)), at)).events, Vector.empty)
