package io.eitheror.casino.wallet.api

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import io.eitheror.casino.wallet.entity.{Outcome, Refusals}

import scala.concurrent.duration.*

/** Spec 022 research R4: a flow started under a key answers its first outcome, `KEY_REUSED`, or pending. */
class FlowsSuite extends munit.FunSuite:

  /** A flow as the route sees it: `start` claims it once; `read` is its state. */
  private final class FakeFlow(var seen: Option[Flows.Seen] = None):
    def start(digest: String, amount: Long, decided: Option[Outcome]): Unit =
      if seen.isDefined then throw CommandError("already started", ErrorCode.Conflict)
      seen = Some(Flows.Seen(digest, Some(amount), decided))
    def read: Flows.Seen = seen.get

  private val done = Outcome(commandType = "settle", amount = Some(600), transactionId = Some("t-1"), realBalance = 400)

  test("the first start answers the flow's outcome") {
    val flow = FakeFlow()
    assertEquals(Flows.answer(flow.start("d1", 600, Some(done)), flow.read, "d1", "settle", "t1"), done)
  }

  test("a repeat with the same command answers the first outcome and starts nothing") {
    val flow = FakeFlow(Some(Flows.Seen("d1", Some(600), Some(done))))
    assertEquals(Flows.answer(flow.start("d1", 600, None), flow.read, "d1", "settle", "t1"), done)
  }

  test("a changed command under the key is KEY_REUSED naming the first command's type and amount") {
    val flow = FakeFlow(Some(Flows.Seen("d1", Some(600), Some(done))))
    val o    = Flows.answer(flow.start("d2", 700, None), flow.read, "d2", "settle", "t1")
    assertEquals(o.code, Some(Refusals.KeyReused))
    assertEquals(o.message, Some("Key t1 was used by a settle of 600"))
    assertEquals(o.details, Map("key" -> "t1"))
  }

  test("a flow still deciding past the bound answers pending") {
    val flow = FakeFlow()
    assertEquals(Flows.answer(flow.start("d1", 600, None), flow.read, "d1", "settle", "t1", bound = 5.millis), Flows.pending)
  }

  test("a start that fails other than by Conflict propagates") {
    intercept[CommandError](Flows.answer(throw CommandError("down", ErrorCode.Internal), Flows.Seen("d1", None, None), "d1", "settle", "t1"))
  }
