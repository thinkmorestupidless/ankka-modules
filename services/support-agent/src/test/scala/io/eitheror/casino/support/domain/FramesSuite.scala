package io.eitheror.casino.support.domain

import org.scalacheck.{Gen, Prop}

import java.nio.charset.StandardCharsets.UTF_8

/** contracts/routes.md's events, and the hand-off marker replaced however the reply is split (R12). */
class FramesSuite extends munit.ScalaCheckSuite:

  private def data(e: com.thinkmorestupidless.ankka.http.SseEvent): String = e.toString

  test("the events carry the original's names and fields"):
    val d = Frames.delta("Hi")
    assert(data(d).contains("delta") && data(d).contains("\"token\":\"Hi\""), data(d))
    val c = data(Frames.confirmAction("apply_self_exclusion", "Self-exclude", "s-1"))
    assert(c.contains("confirm_action") && c.contains("\"action_name\":\"apply_self_exclusion\"") && c.contains("\"session_id\":\"s-1\""), c)
    val r = data(Frames.actionResult("request_withdrawal", false, "KEY_REUSED"))
    assert(r.contains("action_result") && r.contains("\"success\":false") && r.contains("\"detail\":\"KEY_REUSED\""), r)
    assert(data(Frames.done).contains("done"))
    assert(data(Frames.error("busy")).contains("\"message\":\"busy\""))

  private val message = "I can't help with that here. Our support team can, on live chat every day."
  private val reply   = s"Sorry, that one is outside what I can do. ${Frames.HandOffMarker}"

  private def stream(parts: Seq[String]): (String, Boolean) =
    val h   = HandOff(message)
    val out = parts.map(h.feed).mkString + h.finish()
    (out, h.handedOff)

  test("the marker is replaced by the hand-off message"):
    assertEquals(stream(Seq(reply)), ("Sorry, that one is outside what I can do. " + message, true))

  test("a reply without the marker is sent whole, a lone bracket too"):
    assertEquals(stream(Seq("The answer is ⟦ not a marker")), ("The answer is ⟦ not a marker", false))
    assertEquals(stream(Seq("ends with ⟦hand")), ("ends with ⟦hand", false))

  property("however the reply is split into parts, the marker is found and nothing of it is sent"):
    Prop.forAll(Gen.listOf(Gen.choose(1, reply.length - 1))) { cuts =>
      val points = (0 +: cuts.distinct.sorted :+ reply.length).distinct
      val parts  = points.zip(points.tail).map((a, b) => reply.substring(a, b))
      val (out, found) = stream(parts)
      found && out == "Sorry, that one is outside what I can do. " + message && !out.getBytes(UTF_8).sameElements(reply.getBytes(UTF_8))
    }
