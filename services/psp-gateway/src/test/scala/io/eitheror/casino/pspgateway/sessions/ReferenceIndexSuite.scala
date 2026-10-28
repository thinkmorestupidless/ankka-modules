package io.eitheror.casino.pspgateway.sessions

import com.thinkmorestupidless.ankka.testkit.{KeyValueEntityTestKit, LogCapturing}
/** Research R4: a provider's own reference to a session, written once. */
class ReferenceIndexSuite extends munit.FunSuite with LogCapturing:

  test("put once, never updated; get answers the kind and key") {
    val kit = KeyValueEntityTestKit.of(ReferenceIndex, ReferenceIndex.key(Provider.T365, "abc123"))
    kit.call(ReferenceIndex.put)(Put(Indexed(Indexed.Deposit, "abc-123"))): Unit
    kit.call(ReferenceIndex.put)(Put(Indexed(Indexed.Payout, "other"))): Unit
    assertEquals(kit.call(ReferenceIndex.get).replyValue, Some(Indexed(Indexed.Deposit, "abc-123")))
  }

  test("a missing id is none") {
    val kit = KeyValueEntityTestKit.of(ReferenceIndex, "t365/none")
    assertEquals(kit.call(ReferenceIndex.get).replyValue, None)
  }

  test("the key is the provider's path and the reference") {
    assertEquals(ReferenceIndex.key(Provider.Morphosis, "tok"), "morphosis/tok")
  }
