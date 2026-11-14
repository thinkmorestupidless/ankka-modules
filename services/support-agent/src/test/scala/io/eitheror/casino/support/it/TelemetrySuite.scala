package io.eitheror.casino.support.it

import com.thinkmorestupidless.ankka.agent.Json as AgentJson
import com.thinkmorestupidless.ankka.runtime.Observability
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.*

/**
 * What the service logs and traces (T060; FR-020): a conversation with a tool call, an approval decided with
 * feedback and a guardrail rejection, each of whose texts carries a marker, then the log captured during it and every
 * span recorded, searched for the markers. None may carry a message's text, a reply, the feedback or a tool's
 * argument values.
 */
class TelemetrySuite extends munit.FunSuite with LogCapturing:
  override val munitTimeout = 180.seconds
  private lazy val kit = SharedKit.kit

  override def beforeEach(context: BeforeEach): Unit =
    kit.reset()
    kit.configure(): Unit

  private val Message  = "my lucky word is pelican"
  private val Reply    = "Your lucky heron balance is fine."
  private val Feedback = "osprey says not today"
  private val Amount   = 739113L
  private val Refused  = "what does kestrel.watch@example.com have?"
  private val Markers  = Seq("pelican", "heron", "osprey", Amount.toString, "kestrel")

  private def spans: Vector[String] =
    val o = Observability(kit.testKit.service.system)
    o.recorder.snapshot().map(s =>
      Seq(o.names.nameOf(s.componentRef), o.names.nameOf(s.handlerRef), s.subject, s.attributes, s.outcome).mkString(" "))

  test("no log line and no span carries a message, a reply, the feedback or a tool's argument values"):
    kit.model.expectToolCall("get_wallet_balance", AgentJson.obj(), "call-r").expectText(Reply)
    assertEquals(kit.chat("p-tel", "tel-1", Message).text(), Reply)

    kit.model.expectToolCall("request_withdrawal", AgentJson.obj("amount" -> AgentJson.num(Amount.toDouble),
      "currency" -> AgentJson.str("GBP")), "call-w")
    assertEquals(kit.chat("p-tel", "tel-2", s"withdraw $Amount pence, $Message").names(), Vector("confirm_action", "done"))
    kit.model.expectText(Reply)
    assertEquals(kit.resume("p-tel", "tel-2", "reject", Some(Feedback)).names().last, "done")

    assertEquals(kit.chat("p-tel", "tel-3", Refused).names(), Vector("error"))

    // The audit's records of these are published by now, so whatever their consumers logged is in the capture.
    kit.eventually("the rejection's record", within = 1.minute)(kit.audits.find(_.correlationId == "tel-3"))
    val log = capturedLog
    assert(log.nonEmpty, "nothing was captured: the suite would pass whatever the service logged")
    Markers.foreach { m =>
      assert(!log.contains(m), s"a log line carries '$m'")
      spans.find(_.contains(m)).foreach(s => fail(s"a span carries '$m': $s"))
    }
    assert(spans.nonEmpty, "no span was recorded")
