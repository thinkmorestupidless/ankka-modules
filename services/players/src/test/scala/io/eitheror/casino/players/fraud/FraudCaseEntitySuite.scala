package io.eitheror.casino.players.fraud

import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

import java.time.Instant

/** The fraud case entity (T013). */
class FraudCaseEntitySuite extends munit.FunSuite:

  private val at = Instant.parse("2026-10-25T10:00:00Z")

  private def opened =
    val kit = EventSourcedTestKit.of(FraudCaseEntity, "c-1")
    kit.call(FraudCaseEntity.open)(OpenCase(Set("p-1", "p-2"), Set("identity"), at)): Unit
    kit

  test("Opened with players and signals; a second Opened under the id answers the first") {
    val kit = opened
    assertEquals((kit.currentState.playerIds, kit.currentState.signals, kit.currentState.status),
      (Set("p-1", "p-2"), Set("identity"), FraudCaseStatus.Open))
    val again = kit.call(FraudCaseEntity.open)(OpenCase(Set("p-9"), Set("device"), at.plusSeconds(1)))
    assert(!again.persisted)
    assertEquals(again.replyValue.state.playerIds, Set("p-1", "p-2"))
  }

  test("Reviewed with the operator and time, once") {
    val kit = opened
    assert(kit.call(FraudCaseEntity.review)(DecideCase("op-1", at.plusSeconds(60))).persisted)
    assertEquals((kit.currentState.status, kit.currentState.reviewedBy, kit.currentState.reviewedAt),
      (FraudCaseStatus.Reviewed, Some("op-1"), Some(at.plusSeconds(60))))
    assert(!kit.call(FraudCaseEntity.review)(DecideCase("op-2", at.plusSeconds(61))).persisted)
    assertEquals(FraudCaseStatus.wire(kit.currentState.status), "REVIEWED")
  }

  test("Dismissed from open or reviewed, once; a dismissed case is not reviewed; an unknown case is refused") {
    val kit = opened
    assert(kit.call(FraudCaseEntity.dismiss)(DecideCase("op-1", at)).persisted)
    assert(!kit.call(FraudCaseEntity.dismiss)(DecideCase("op-1", at)).persisted)
    assertEquals(kit.call(FraudCaseEntity.review)(DecideCase("op-1", at)).replyValue.refusal, Some("case_closed"))
    val unknown = EventSourcedTestKit.of(FraudCaseEntity, "c-x")
    assertEquals(unknown.call(FraudCaseEntity.dismiss)(DecideCase("op-1", at)).replyValue.refusal, Some("case_not_found"))
  }
