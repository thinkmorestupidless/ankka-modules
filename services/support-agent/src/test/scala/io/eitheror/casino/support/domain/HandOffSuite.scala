package io.eitheror.casino.support.domain

import org.scalacheck.{Gen, Prop}

/**
 * `HandOff` across a stream's `delta`s (FR-025; R12): what each part sends never ends in a beginning of the marker,
 * so no part of it reaches the player, and what was held back is sent when it turns out not to be the marker.
 */
class HandOffSuite extends munit.ScalaCheckSuite:
  private val message = "Our support team can help, on live chat every day from 9 to 21."
  private val marker  = Frames.HandOffMarker

  test("a part ending in the marker's beginning is held back until the next part says what it was"):
    val h = HandOff(message)
    assertEquals(h.feed("Sorry. ⟦hand"), "Sorry. ")
    assertEquals(h.feed("off⟧"), message)
    assertEquals((h.finish(), h.handedOff), ("", true))

  test("held text that was not the marker is sent with the next part, or at the end"):
    val h = HandOff(message)
    assertEquals(h.feed("a ⟦ha"), "a ")
    assertEquals(h.feed("t⟧ b"), "⟦hat⟧ b")
    assertEquals(h.feed(" ⟦"), " ")
    assertEquals(h.finish(), "⟦")
    assert(!h.handedOff)

  test("after the marker the rest of the reply is dropped"):
    val h = HandOff(message)
    assertEquals(h.feed(s"No. $marker"), s"No. $message")
    assertEquals(h.feed(" and more"), "")

  property("whatever the parts, what is sent is the reply up to the marker and then the message, or the reply whole"):
    Prop.forAll(Gen.listOf(Gen.oneOf("a", " ", "⟦", "h", "and", "off", "⟧", "x", marker))) { parts =>
      val h     = HandOff(message)
      val sent  = parts.map(h.feed).mkString + h.finish()
      val reply = parts.mkString
      reply.indexOf(marker) match
        case -1 => sent == reply && !h.handedOff
        case i  => sent == reply.take(i) + message && h.handedOff
    }
