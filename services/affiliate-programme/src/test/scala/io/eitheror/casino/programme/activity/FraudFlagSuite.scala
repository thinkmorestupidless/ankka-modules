package io.eitheror.casino.programme.activity

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}

import java.time.Instant

class FraudFlagSuite extends munit.FunSuite with LogCapturing:
  private val at   = Instant.parse("2026-11-01T10:15:00Z")
  private val id   = FraudFlag.flagId("goldvibe", "ps-1", FraudFlag.Chargeback)
  private val kit  = () => KeyValueEntityTestKit.of(FraudFlag, FraudFlag.entityId("goldvibe", id).toString)
  private val c    = RaiseFlag("goldvibe", "ps-1", "aff-1", FraudFlag.Chargeback, "2+ chargebacks", "2026-11", at)

  test("the id is the player's, brand's and reason's; a flag is raised once") {
    assertEquals(FraudFlag.flagId("goldvibe", "ps-1", FraudFlag.Chargeback), id)
    assertNotEquals(FraudFlag.flagId("goldvibe", "ps-1", FraudFlag.BonusCost), id)
    val k = kit()
    assertEquals(k.call(FraudFlag.raise)(c).replyValue.flagId, id)
    assert(!k.call(FraudFlag.raise)(c.copy(description = "3+ chargebacks")).changed)
  }

  test("dismissed once, by whom and why; a flag never raised is not found") {
    val k = kit()
    k.call(FraudFlag.raise)(c): Unit
    val d = k.call(FraudFlag.dismiss)(DismissFlag("mgr-1", "known player", at)).replyValue
    assertEquals((d.dismissed, d.dismissedBy, d.dismissalReason), (true, Some("mgr-1"), Some("known player")))
    assert(!k.call(FraudFlag.dismiss)(DismissFlag("mgr-2", "again", at)).changed)
    assertEquals(KeyValueEntityTestKit.of(FraudFlag, "goldvibe/none").call(FraudFlag.dismiss)(DismissFlag("m", "r", at)).errorMessage, "not_found")
  }
