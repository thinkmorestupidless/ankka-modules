package io.eitheror.casino.ingest.requests

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
/** FR-009, research R7: who a ticket and a payment belong to. */
class OwnershipSuite extends munit.FunSuite with LogCapturing:

  test("a ticket's ownership records the sub-brand, request id, record id and ticket; a later set adds the payment and keeps the rest") {
    val kit = KeyValueEntityTestKit.of(Ownership, "ticket/tok")
    kit.call(Ownership.set)(OwnershipState("sb-1", Some("t-1"), "r1", Some("tok"))): Unit
    kit.call(Ownership.set)(OwnershipState("sb-9", None, "r9", Some("tok"), Some("pay-1"))): Unit
    assertEquals(kit.call(Ownership.read).replyValue, Some(OwnershipState("sb-1", Some("t-1"), "r1", Some("tok"), Some("pay-1"))))
  }

  test("a payment's ownership names its owner, and expires") {
    val kit = KeyValueEntityTestKit.of(Ownership, "payment/pay-1")
    val r   = kit.call(Ownership.set)(OwnershipState("sb-1", Some("t-1"), "r1", Some("tok"), Some("pay-1")))
    assert(r.retention.isDefined)
    assertEquals(kit.call(Ownership.read).replyValue.map(_.subBrand), Some("sb-1"))
  }

  test("an ownership nobody set reads as none") {
    assertEquals(KeyValueEntityTestKit.of(Ownership, "ticket/none").call(Ownership.read).replyValue, None)
  }
